package com.leqi.experiment.phonebt

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 手机端 Agent 运行时的宿主前台服务。
 *
 * 运行时的所有权在这里，不在界面里：仓库 Agent bundle（无界面 WebView）、本地模型与离线语音的 worker、
 * 高德导航、眼镜蓝牙通道、有界媒体表都在本服务里。Activity 只做权限/隐私询问、观察状态、通过 binder 发号施令，
 * 因此界面销毁重建（旋转、被系统回收）不会带走会话、模型或正在走的导航。
 *
 * 线程约定：
 * - WebView、通知、观察者回调都在主线程（WebView 只认创建它的 Looper）；
 * - 模型加载、语音合成、识别都在唯一的 worker 线程上；
 * - 蓝牙回调在蓝牙线程上，落回主线程再改状态。
 *
 * 边界：本服务是**宿主**，不是第二个决策者。任务状态机、目的地确认策略、播报内容全部仍由仓库
 * TypeScript Agent 决定，这里只把设备事实搬成信封、把 Effect 落到真实设备上。
 *
 * 生命周期：START_NOT_STICKY。被系统回收之后不会被拉起；即使被空的 Intent 拉起（进程重启），也只把服务
 * 收干净，绝不静默恢复上一次的会话、录音授权或导航——用户必须重新按眼镜按键开始。
 */
class AgentHostService : Service() {

    /**
     * 对外状态快照。Activity 只读它，不碰运行时对象，所以界面重建不会影响运行时。
     */
    class Status(
        val headline: String,
        /** 逐项自检结果，例如“本地模型：已加载 …”。 */
        val lines: List<String>,
        /** 所有前置条件都已通过；任何一项待查或阻塞都不算就绪。 */
        val ready: Boolean,
        val glassesConnected: Boolean,
        val agentLoaded: Boolean,
        /** 采集链路被阻塞的原因；不为 null 时不允许开始录音/拍摄。 */
        val captureBlockedReason: String?,
        /**
         * 运行时已经收干净了：调用方据此解绑、把界面交还给用户，并且允许重新发起一次启动。
         * 服务被停止和“服务还活着但被系统挡在绑定里”是两回事，界面必须能分辨。
         */
        val stopped: Boolean,
    )

    /** 状态观察者；回调在主线程触发。 */
    fun interface Listener {
        fun onStatus(status: Status)
    }

    /** 本进程内的绑定接口（同进程，不需要 AIDL）。 */
    inner class LocalBinder : Binder() {
        val host: AgentHostService get() = this@AgentHostService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, WORKER_THREAD_NAME).apply { isDaemon = true }
    }
    private val nativeEnvelopeCounter = AtomicLong(0)
    private val cleanedUp = AtomicBoolean(false)

    /**
     * 在途原生操作的代号：模型推理（文本与视觉）、语音转写、播报准备都算一次操作。
     *
     * 每一次“这件事不再成立了”（task.cancel、断开、手动重连、停止）都自增一次。worker 上的任务在**入队时**
     * 和**阻塞调用返回之后**各比一次代号，所以旧任务既不会在重连后的新通道上冒出来，也不会把结果回给
     * 已经被作废的会话。新任务一律用当前代号。
     */
    private val operationGeneration = AtomicLong(0)

    /**
     * 模型和离线语音的关闭专用线程。
     *
     * 这两个引擎的 close 和 initialize/infer 共用同一把锁，而推理可能跑几分钟：如果就地关，主线程会被
     * 一起卡住。这里单独一条线程去关，谁先拿到锁谁先走，主线程照常收蓝牙、播报和 WebView。
     */
    private val teardown: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, TEARDOWN_THREAD_NAME).apply { isDaemon = true }
    }

    /** 只能在主线程创建：构造期拿不到 Service 的 Context（系统先 new 再 attach）。 */
    private lateinit var readiness: RuntimeReadiness

    /** 观察者只在主线程读写。 */
    private val observers = ArrayList<Listener>()

    // ---- 运行时（全部归本服务所有） ----------------------------------------

    @Volatile private var agent: AgentRuntimeWebView? = null
    @Volatile private var model: GemmaLocalBridge? = null

    /** 离线语音引擎；懒建在 worker 线程上，收尾时释放。 */
    @Volatile private var tts: OfflineTtsBridge? = null

    /** 播报出口；必须在蓝牙通道之后建，蓝牙线程也会读它。 */
    @Volatile private var speech: AgentSpeechOutput? = null

    @Volatile private var navigation: AgentNavigationDevice? = null
    @Volatile private var transport: GlassesTransport? = null
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var inForeground = false

    /** 蓝牙通道现在是不是连着。只听设备层回调。 */
    @Volatile private var glassesConnected = false

    /** 通道形态；自检按它判 OK/PENDING/BLOCKED——“有配对设备”不等于“连上了”。 */
    @Volatile private var glassesLink = RuntimeReadiness.LinkState.DOWN

    /** 眼镜通道的状态说明，直接进自检报告。 */
    @Volatile private var glassesState = "未连接"

    /** 当前状态标题。 */
    @Volatile private var headline = "正在启动…"

    /**
     * 眼镜刚传来的照片，键是不透明 id。
     *
     * 只活在本服务的内存里：既不落磁盘，也不进 JS，日志里也不出现字节。照片要在哪一步用、
     * 传给谁，由上层拿 mediaRef 决定。
     */
    private val media = LinkedHashMap<String, ByteArray>()

    /**
     * 模型/语音引擎的交接锁：谁把引擎从字段里拿走，谁就负责关它。
     *
     * worker 上刚加载完的引擎和收尾线程要同时动这两个字段，没有这把锁就会出现“两边都以为对方会关”
     * 或者“两边都关一次”。拿不到就说明对方已经接管，自己什么都不用做。
     */
    private val bridgeLock = Any()

    /**
     * 本机同时只可能有一起导航（高德导航是单例实例），所以只留一个槽位。
     * 只在主线程读写：JS 桥、定位回调和 SDK 回调都在主线程。
     */
    private var activeNavigation: PendingNavigation? = null

    override fun onCreate() {
        super.onCreate()
        readiness = RuntimeReadiness(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** 界面解绑就丢掉观察者：服务绝不留着任何一个 Activity 引用。 */
    override fun onUnbind(intent: Intent?): Boolean {
        main.post { observers.clear() }
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!enterForeground()) {
                    stopHost("已停止：前台服务起不来")
                    return START_NOT_STICKY
                }
                if (!hasBluetoothPermission()) {
                    // 前台服务类型靠这个权限撑着，缺了就不能假装服务是有效的。
                    publish("缺少“附近的设备”权限：未启动运行时")
                    // 已经有运行时就不在这里拆：这个 Intent 起不来不代表用户想停掉正在跑的东西。
                    if (agent == null) stopHost("已停止：缺少“附近的设备”权限")
                    return START_NOT_STICKY
                }
                ensureRuntime()
            }

            ACTION_RECONNECT -> {
                if (!enterForeground()) {
                    stopHost("已停止：前台服务起不来")
                    return START_NOT_STICKY
                }
                requestReconnect()
            }

            ACTION_STOP -> stopHost()

            else -> {
                // 没有动作（进程被重启拉起）或未知动作：进前台再立刻收干净。
                // 会话、录音授权和导航一律不自动恢复，必须由用户重新开始。
                enterForeground()
                stopHost()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ---- 给 Activity 的接口 -------------------------------------------------

    /** 订阅状态；会立刻回一次当前快照。回调在主线程。 */
    fun observe(listener: Listener) {
        main.post {
            if (cleanedUp.get()) return@post
            if (!observers.contains(listener)) observers.add(listener)
            listener.onStatus(snapshot())
        }
    }

    fun releaseObserver(listener: Listener) {
        main.post { observers.remove(listener) }
    }

    /**
     * 手动重连眼镜：作废正在等待的退避，立刻从头再连一次。
     *
     * 只是重新建链：不会补发任何旧的录音/拍摄授权，也不会让刚被取消的任务自己续上。
     *
     * 换通道等于把旧的通道当场作废，所以在 [GlassesTransport.connect] 之前先把这件事办完：作废在途操作、
     * 清掉照片、告诉上层断开。否则上层会看到“任务还在跑，通道却悄悄换了人”。
     * 运行时还没建起来时走 [ensureRuntime]，那条路自己会连一次——不在这里连第二遍。
     */
    fun requestReconnect() {
        main.post {
            if (cleanedUp.get()) return@post
            if (agent == null) {
                ensureRuntime()
                return@post
            }
            retireForReconnect()
            connectGlasses()
        }
    }

    /** 手动重连前的当场作废：在途操作、照片、正在播的句子，以及一条如实的断开。 */
    private fun retireForReconnect() {
        invalidateOperations("手动重连")
        // 清队列不够：出口自己记着旧通道和旧代号，重连之后它还能接着往新 socket 上写。
        // 出口整个作废、语音引擎留着，下一次播报按 [openSpeech] 重建出口并复用同一个引擎。
        detachSpeech()?.close()
        glassesConnected = false
        glassesState = "正在重新连接"
        // 先报断开再连：上层据此裁决正在跑的任务，绝不会以为这条新通道还接着旧任务。
        forwardDevice(JSONObject().put("kind", KIND_DISCONNECTED).put("reason", "用户手动重连"))
    }

    /** 停止整个运行时并退出前台服务；和通知上的“停止”是同一件事。 */
    fun requestStop() = stopHost()

    // ---- 运行时搭建 ---------------------------------------------------------

    /** 建立运行时；幂等，重复调用只保证设备侧在连线。必须在主线程调用（WebView）。 */
    private fun ensureRuntime() {
        if (cleanedUp.get()) return
        if (agent != null) return
        acquireWakeLock()
        val created = try {
            AgentRuntimeWebView(this) { message -> onAgentMessage(message) }
        } catch (error: Throwable) {
            Log.e(TAG, "Agent runtime creation failed", error)
            publish("运行时创建失败：" + describe(error))
            stopHost("已停止：运行时创建失败")
            return
        }
        agent = created
        publish("运行时已启动，正在自检…")
        prepareDevice()
        // 自检里包含真正加载模型和语音，都阻塞，所以整段放 worker。
        submit { runStartupChecks() }
    }

    /**
     * 把一段阻塞工作交给 worker。
     *
     * 收尾之后不能再往线程池里塞东西（池已经 shutdown，塞进去只会抛 RejectedExecutionException），
     * 所以排队中的回调在这里被安静地丢掉，而不是把异常抛回调用方。
     */
    private fun submit(task: () -> Unit) {
        if (cleanedUp.get()) return
        try {
            worker.execute(task)
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "worker is gone, task dropped: " + describe(error))
        }
    }

    /**
     * 当场作废在途的原生操作：模型推理（文本与视觉）、语音转写、播报准备。
     *
     * 自增一次代号就够：worker 上的任务在阻塞调用返回之后会拿它和入队时的代号比对，对不上就不回话。
     * 照片也一起清掉——旧会话的照片不该被新会话取用。本机不为模型留任何跨轮记忆，所以模型侧没有第二份
     * 需要作废的状态：挡住旧回话只需要这一个代号。
     */
    private fun invalidateOperations(reason: String) {
        val (generation, retiredSpeech) = synchronized(bridgeLock) {
            val next = operationGeneration.incrementAndGet()
            val old = speech
            speech = null
            next to old
        }
        retiredSpeech?.close()
        Log.i(TAG, "native operations retired reason=$reason generation=$generation")
        synchronized(media) { media.clear() }
    }

    /**
     * 启动自检：先查文件，再真的把本地模型和离线语音加载一遍，最后把结果如实写进状态。
     *
     * 手机模型文件不随 APK 分发：文件不在就直说是缺文件、该放哪个目录，而不是等到第一次识别时才报错，
     * 也不能因为"文件在"就说已就绪——加载失败是另一种说法。整个方法在 worker 线程上跑。
     */
    private fun runStartupChecks() {
        if (cleanedUp.get()) return
        readiness.staticItems().forEach(readiness::mark)
        publish("正在检查启动条件…")

        if (GemmaLocalBridge.findModelFile(this) != null) {
            publish("正在加载本地模型…")
            try {
                val bridge = GemmaLocalBridge(this)
                try {
                    bridge.initialize()
                } catch (error: Throwable) {
                    runCatching { bridge.close() }
                    throw error
                }
                // 加载是阻塞的，回来时可能已经在收尾了：这时刚建好的桥既不能登记，也不能漏着不关。
                if (!adoptModel(bridge)) {
                    Log.i(TAG, "startup cancelled after model load, closing bridge")
                    runCatching { bridge.close() }
                    return
                }
                readiness.mark(
                    RuntimeReadiness.Item(
                        RuntimeReadiness.Capability.MODEL,
                        RuntimeReadiness.Level.OK,
                        "已加载 " + bridge.modelPath,
                    ),
                )
            } catch (error: Throwable) {
                Log.w(TAG, "local model load failed", error)
                readiness.mark(
                    RuntimeReadiness.Item(
                        RuntimeReadiness.Capability.MODEL,
                        RuntimeReadiness.Level.BLOCKED,
                        "本地模型加载失败：" + describe(error),
                    ),
                )
            }
        }

        if (OfflineTtsBridge.findModelDir(this) != null) {
            publish("正在加载离线语音…")
            try {
                val bridge = OfflineTtsBridge(this)
                try {
                    bridge.initialize()
                } catch (error: Throwable) {
                    runCatching { bridge.close() }
                    throw error
                }
                if (!adoptTts(bridge)) {
                    Log.i(TAG, "startup cancelled after tts load, closing bridge")
                    runCatching { bridge.close() }
                    return
                }
                readiness.mark(
                    RuntimeReadiness.Item(
                        RuntimeReadiness.Capability.SPEECH,
                        RuntimeReadiness.Level.OK,
                        "已加载 " + bridge.modelPath,
                    ),
                )
            } catch (error: Throwable) {
                Log.w(TAG, "offline tts load failed", error)
                readiness.mark(
                    RuntimeReadiness.Item(
                        RuntimeReadiness.Capability.SPEECH,
                        RuntimeReadiness.Level.BLOCKED,
                        "离线语音加载失败：" + describe(error),
                    ),
                )
            }
        }

        publishStatus()
    }

    /** 只负责建链和报状态；权限、蓝牙开关、配对设备这些前置条件由 [connectGlasses] 的返回值当场说清。 */
    private fun prepareDevice() {
        if (!hasBluetoothPermission()) {
            glassesConnected = false
            glassesLink = RuntimeReadiness.LinkState.DOWN
            glassesState = "未连接（缺少“附近的设备”权限）"
            publish("缺少“附近的设备”权限：未连接眼镜")
            return
        }
        connectGlasses()
    }

    private fun connectGlasses() {
        publish("正在连接眼镜…")
        glassesLink = RuntimeReadiness.LinkState.CONNECTING
        val failure = glassesTransport().connect()
        if (failure != null) {
            // connect() 当场就能说清的失败（权限、蓝牙开关、没有配对设备）不会再有回调，也不进自动重连。
            Log.w(TAG, "glasses connect rejected: $failure")
            glassesConnected = false
            glassesLink = RuntimeReadiness.LinkState.DOWN
            glassesState = "未连接（$failure）"
            publish(failure)
        }
    }

    private fun glassesTransport(): GlassesTransport {
        val existing = transport
        if (existing != null) return existing
        return GlassesTransport(this, TransportListener()).also { transport = it }
    }

    private fun navigationDevice(): AgentNavigationDevice {
        val existing = navigation
        if (existing != null) return existing
        return AgentNavigationDevice(this).also { navigation = it }
    }

    /** 本地模型端口：要么给一个能推理的桥，要么给一句能直接展示的失败原因。 */
    private class ModelPort(val bridge: GemmaLocalBridge?, val reason: String = "")

    /** 登记一个刚加载好的模型桥；false 表示已经在收尾，调用方必须自己把它关掉。 */
    private fun adoptModel(bridge: GemmaLocalBridge): Boolean = synchronized(bridgeLock) {
        if (cleanedUp.get()) {
            false
        } else {
            model = bridge
            true
        }
    }

    /** 登记一个刚加载好的语音引擎；false 表示已经在收尾，调用方必须自己把它关掉。 */
    private fun adoptTts(bridge: OfflineTtsBridge): Boolean = synchronized(bridgeLock) {
        if (cleanedUp.get()) {
            false
        } else {
            tts = bridge
            true
        }
    }

    /** 把模型桥从字段里接管出来；拿到的人负责关，收尾线程不会重复关。 */
    private fun takeModel(): GemmaLocalBridge? = synchronized(bridgeLock) {
        val bridge = model
        model = null
        bridge
    }

    /** 把语音引擎从字段里接管出来；拿到的人负责关。 */
    private fun takeTts(): OfflineTtsBridge? = synchronized(bridgeLock) {
        val bridge = tts
        tts = null
        bridge
    }

    /**
     * 把播报出口从字段里摘下来；摘下的人负责 [AgentSpeechOutput.close]。
     *
     * 和 [openSpeech] 的登记共用一把锁，所以“刚摘掉又被登记回来”不会发生。task.cancel、断开、
     * 手动重连和收尾都必须走这里，而不是只清队列：出口自己记着通道和代号，不清掉它，取消之后
     * 的句子还会从旧出口写出去。[OfflineTtsBridge] 不在这里关——引擎仍归字段持有，下一次播报
     * 直接复用，省掉一次秒级的重新加载。
     */
    private fun detachSpeech(): AgentSpeechOutput? = synchronized(bridgeLock) {
        val output = speech
        speech = null
        output
    }

    /**
     * 懒建本地模型桥。
     *
     * 模型文件缺失时**不能**去 new [GemmaLocalBridge]（它的构造函数会因为找不到文件直接抛），
     * 所以先查文件再建，缺文件就返回一句"文件不随 APK 分发、该放哪"的说明。
     */
    private fun openModel(): ModelPort {
        val existing = model
        if (existing != null) return ModelPort(existing)
        if (cleanedUp.get()) return ModelPort(null, "运行时已停止")
        if (GemmaLocalBridge.findModelFile(this) == null) {
            return ModelPort(null, GemmaLocalBridge.missingModelMessage(this))
        }
        return try {
            val bridge = GemmaLocalBridge(this)
            if (adoptModel(bridge)) {
                ModelPort(bridge)
            } else {
                runCatching { bridge.close() }
                ModelPort(null, "运行时已停止")
            }
        } catch (error: Throwable) {
            ModelPort(null, "本地模型不可用：" + describe(error))
        }
    }

    // ---- Agent 信封 ---------------------------------------------------------

    /** 只分发信封；业务内容由仓库 Agent 解释，本服务不认识它们的含义。 */
    private fun onAgentMessage(message: JSONObject) {
        when (message.optString("type")) {
            "ready" -> {
                Log.i(TAG, "repository Agent ready")
                publishStatus()
            }
            "effect" -> {
                val effect = message.optJSONObject("payload")
                val kind = effect?.optString("type") ?: "unknown"
                val state = effect?.optJSONObject("payload")?.optString("status") ?: ""
                Log.i(TAG, "Effect type=$kind status=$state")
                // 说什么由策略决定，怎么说出去由本机决定。Effect 是单向的，所以不等回执、不回 response。
                if (kind == "speech") {
                    val spoken = effect?.optJSONObject("payload")
                    speak(
                        message.optString("id"),
                        spoken?.optString("text").orEmpty(),
                        isUrgent(spoken?.optString("priority").orEmpty()),
                        false,
                    )
                }
            }
            "error" -> publish("仓库 Agent 运行失败：${message.optString("error")}")
            "event.result" -> Log.i(TAG, "Agent event result id=${message.optString("id")}")
            "tool.execute" -> handleToolExecute(message)
            // 策略层确认过目的地之后才发这两个信封；本机只负责把坐标落到 SDK，不自己决定去哪。
            "policy.navigation.start" -> handlePolicyNavigationStart(message)
            "policy.navigation.stop" -> handlePolicyNavigationStop(message)
            // 上层要本机说一句话，并等一个明确的“说完了”。没有通道或没有离线模型时明确回 error。
            "speech.say" -> {
                val payload = message.optJSONObject("payload")
                speak(
                    message.optString("id"),
                    payload?.optString("text").orEmpty(),
                    isUrgent(payload?.optString("priority").orEmpty()),
                    true,
                    payload?.optBoolean("allowRepeat") == true,
                )
            }
            // 什么时候允许录音、什么时候允许拍照，都由 Agent/策略决定；本机只把指令原样写上线。
            "device.send" -> handleDeviceSend(message)
            // 上层拿 native.photo 给的 mediaRef 回来要看这张照片；照片只认本机真收到过的那张。
            "vision.observe" -> handleVisionObserve(message)
            "model.generate" -> handleModelGenerate(message)
        }
    }

    private fun sendToAgent(message: JSONObject) {
        val web = agent
        if (web == null || cleanedUp.get()) {
            Log.w(TAG, "runtime is gone, drop ${message.optString("type")}")
            return
        }
        web.send(message)
    }

    /** 拒绝一律用 error 字段：对面 requestNative 收到 error 会 reject，不会误当成成功。 */
    private fun rejectRequest(id: String, reason: String) {
        Log.w(TAG, "request rejected: $reason")
        sendToAgent(JSONObject().put("id", id).put("type", "response").put("error", reason))
    }

    // ---- 模型端口 -----------------------------------------------------------

    /**
     * 仓库 Agent 的模型请求：本机只做一次推理，输出原样回给 Agent 去校验。
     *
     * payload 只认 {prompt, sessionId}：提示词怎么写、输出怎么解释全由 Agent 决定，本机不认识其中
     * 任何含义。每次请求都是一次性推理，本机不为模型保留跨轮记忆：跨轮历史由 Agent core 有界地放进
     * prompt，sessionId 只是这次请求的传输身份（原样交给桥记日志），本机不会拿它攒对话状态。
     */
    private fun handleModelGenerate(message: JSONObject) {
        val id = message.optString("id")
        val payload = message.optJSONObject("payload")
        val prompt = payload?.optString("prompt") ?: ""
        val sessionId = payload?.optString("sessionId").orEmpty()
        Log.i(
            TAG,
            "Model generate request received promptChars=${prompt.length} " +
                "session=${sessionId.ifEmpty { "（未提供）" }}",
        )
        val generation = operationGeneration.get()
        submit {
            if (generation != operationGeneration.get()) {
                Log.i(TAG, "model request retired before it started id=$id")
                return@submit
            }
            val reply = JSONObject().put("id", id).put("type", "response")
            val port = openModel()
            val bridge = port.bridge
            if (bridge == null) {
                reply.put("error", port.reason)
            } else {
                try {
                    val output = bridge.generateText(prompt, sessionId)
                    Log.i(TAG, "Model generate output=${output.take(4000)}")
                    reply.put("payload", output)
                } catch (error: Throwable) {
                    reply.put("error", error.message ?: error.javaClass.simpleName)
                }
            }
            // 推理是阻塞的：回来时这一轮可能已经被取消，旧答案绝不能落到新会话上。
            if (generation != operationGeneration.get()) {
                Log.i(TAG, "model request retired while running id=$id")
                return@submit
            }
            sendToAgent(reply)
        }
    }

    /**
     * 只实现 vision.observe：把本机缓存里那张照片和 Agent 给的提示交给离线模型，回模型说出来的原文。
     *
     * payload 只认 {mediaRef, prompt}：提示词怎么写、输出是什么形状、算不算成立，全部由 Agent 按仓库
     * 合同决定；本机不生成提示、不解释画面、不把输出解析成观察或事实，payload 就是模型输出的字符串。
     *
     * mediaRef 必须是 [media] 里还挂着的那一张——取字节和删缓存是同一把锁里的一步，所以同一张照片
     * 只可能被取走一次，模型失败也不放回去：宁可让上层重新拍，也不让一次拍摄被读成两段结果。
     * 引用不存在（拍完太久被挤掉、或者已经用过一次）一律回明确 error；缺提示词是协议错误，
     * 在取走照片之前就报出来，免得白白烧掉一次拍摄。
     *
     * 模型没答上来（加载不了、输出为空、调用失败）也只回 error，绝不由本机拼一段结果出来。
     */
    private fun handleVisionObserve(message: JSONObject) {
        val id = message.optString("id")
        val payload = message.optJSONObject("payload")
        val mediaRef = payload?.optString("mediaRef").orEmpty()
        if (mediaRef.isEmpty()) {
            rejectRequest(id, "vision.observe 缺少 payload.mediaRef")
            return
        }
        val prompt = payload?.optString("prompt").orEmpty()
        if (prompt.isBlank()) {
            rejectRequest(id, "vision.observe 缺少 payload.prompt")
            return
        }
        val jpeg = synchronized(media) { media.remove(mediaRef) }
        if (jpeg == null) {
            rejectRequest(id, "本机没有 mediaRef=$mediaRef 对应的照片：已用过或已过期")
            return
        }
        Log.i(TAG, "vision.observe mediaRef=$mediaRef bytes=${jpeg.size} promptChars=${prompt.length}")
        val generation = operationGeneration.get()
        submit {
            if (generation != operationGeneration.get()) {
                Log.i(TAG, "vision.observe retired before it started mediaRef=$mediaRef")
                return@submit
            }
            val reply = JSONObject().put("id", id).put("type", "response")
            val port = openModel()
            val bridge = port.bridge
            if (bridge == null) {
                reply.put("error", port.reason)
            } else {
                try {
                    val observed = bridge.generateVision(jpeg, prompt)
                    Log.i(TAG, "vision.observe outputChars=${observed.length}")
                    reply.put("payload", observed)
                } catch (error: Throwable) {
                    // 只记异常类型和它自己的说明，不记照片字节，也不把任何东西当成观察结果回上去。
                    Log.w(
                        TAG,
                        "vision.observe failed error=${error.javaClass.simpleName} reason=${error.message}",
                    )
                    reply.put("error", error.message ?: error.javaClass.simpleName)
                }
            }
            if (generation != operationGeneration.get()) {
                Log.i(TAG, "vision.observe retired while running mediaRef=$mediaRef")
                return@submit
            }
            sendToAgent(reply)
        }
    }

    // ---- 工具调用 -----------------------------------------------------------

    /**
     * 只实现 navigation.search_destination：真实定位 + 真实高德搜索，结果按 ToolResult 原样回给 Agent。
     *
     * 失败一律回 failed ToolResult 并带上 error，不编造候选；其它工具明确回 unsupported，
     * 免得 Agent 侧一直等到 requestNative 超时。
     */
    private fun handleToolExecute(message: JSONObject) {
        val id = message.optString("id")
        val call = message.optJSONObject("payload")
        val toolId = call?.optString("toolId").orEmpty()
        val sessionId = call?.optString("sessionId").orEmpty()
        val planId = call?.optJSONObject("plan")?.optString("planId").orEmpty()
        val actionIndex = call?.optInt("actionIndex", -1) ?: -1
        val callId = "$planId:$actionIndex"
        if (planId.isEmpty() || actionIndex < 0 || sessionId.isEmpty()) {
            Log.w(TAG, "tool.execute without plan.planId/actionIndex/sessionId")
            replyToolResult(
                id,
                failedToolResult(
                    sessionId, toolId, callId, "unknown",
                    "tool.execute 缺少 plan.planId、actionIndex 或 sessionId", false,
                ),
            )
            return
        }
        if (toolId != TOOL_SEARCH_DESTINATION) {
            replyToolResult(
                id,
                failedToolResult(sessionId, toolId, callId, "unsupported", "本机没有实现工具 $toolId", false),
            )
            return
        }
        val query = call?.optJSONObject("arguments")?.optString("query").orEmpty()
        Log.i(TAG, "tool.execute $toolId queryChars=${query.length}")
        navigationDevice().searchDestination(query) { result ->
            val reply = when (result) {
                is AgentNavigationDevice.SearchResult.Success -> {
                    val candidates = JSONArray()
                    for (candidate in result.candidates) candidates.put(candidateJson(candidate))
                    Log.i(TAG, "search_destination candidates=${candidates.length()}")
                    toolResult(sessionId, toolId, callId, "succeeded", JSONObject().put("candidates", candidates))
                }
                is AgentNavigationDevice.SearchResult.Failure -> {
                    Log.w(TAG, "search_destination failed code=${result.code} message=${result.message}")
                    failedToolResult(sessionId, toolId, callId, result.code, result.message, result.retryable)
                }
            }
            replyToolResult(id, reply)
        }
    }

    private fun replyToolResult(id: String, result: JSONObject) {
        sendToAgent(JSONObject().put("id", id).put("type", "response").put("payload", result))
    }

    /** 字段按 Agent 侧 ToolResult 的形状给，坐标沿用 destination-candidates 合同的 lng/lat。 */
    private fun candidateJson(candidate: AmapSearchAdapter.Candidate): JSONObject {
        val json = JSONObject()
            .put("candidate_id", candidateId(candidate))
            .put("name", candidate.name)
            .put("provider", PROVIDER_AMAP)
            .put("location", JSONObject().put("lng", candidate.longitude).put("lat", candidate.latitude))
        // 高德没给距离时不写 0：0 米会被读成“就在脚下”，宁可缺字段。
        if (candidate.distanceM > 0) json.put("distance_m", candidate.distanceM)
        return json
    }

    /** 高德偶尔不给 poiId，就用它自己给的坐标兜一个稳定 ID，不另外编造地点信息。 */
    private fun candidateId(candidate: AmapSearchAdapter.Candidate): String {
        val poiId = candidate.poiId
        if (!poiId.isNullOrBlank()) return poiId
        return String.format(Locale.ROOT, "amap:%.6f,%.6f", candidate.latitude, candidate.longitude)
    }

    private fun failedToolResult(
        sessionId: String,
        toolId: String,
        callId: String,
        code: String,
        message: String,
        retryable: Boolean,
    ): JSONObject = toolResult(sessionId, toolId, callId, "failed", JSONObject())
        .put("error", JSONObject().put("code", code).put("message", message).put("retryable", retryable))

    /** Agent 侧 ToolResult 的线上形状：这次调用没有可陈述的事实，facts 恒为空数组。 */
    private fun toolResult(
        sessionId: String,
        toolId: String,
        callId: String,
        status: String,
        output: JSONObject,
    ): JSONObject = JSONObject()
        .put("callId", callId)
        .put("sessionId", sessionId)
        .put("toolId", toolId)
        .put("status", status)
        .put("completedAt", Instant.now().toString())
        .put("output", output)
        .put("facts", JSONArray())

    // ---- 导航 ---------------------------------------------------------------

    /**
     * 只实现策略发起的 navigation.start：校验候选坐标 → 交给设备真的起 GPS 步行导航 →
     * 拿到 onRouteReady 才回 {started:true,distanceM,timeSec}。
     *
     * 去哪个地点、要不要去，都是 Agent/策略层已经决定的事；这里只把坐标落到 SDK。
     * 坐标非法、本机已有导航在跑、算路失败一律回 error，绝不假装 started。
     */
    private fun handlePolicyNavigationStart(message: JSONObject) {
        val id = message.optString("id")
        val payload = message.optJSONObject("payload")
        val sessionId = payload?.optString("sessionId").orEmpty()
        if (sessionId.isEmpty()) {
            rejectRequest(id, "policy.navigation.start 缺少 payload.sessionId")
            return
        }
        val candidate = payload?.optJSONObject("candidate")
        if (candidate == null) {
            rejectRequest(id, "policy.navigation.start 缺少 payload.candidate")
            return
        }
        val candidateId = candidate.optString("candidate_id").orEmpty()
        if (candidateId.isEmpty()) {
            rejectRequest(id, "policy.navigation.start 的 candidate 缺少 candidate_id")
            return
        }
        val location = candidate.optJSONObject("location")
        val longitude = if (location == null) null else coordinate(location, "lng")
        val latitude = if (location == null) null else coordinate(location, "lat")
        if (latitude == null || longitude == null) {
            rejectRequest(id, "policy.navigation.start 的 candidate.location.lng/lat 不是数字")
            return
        }
        if (!isUsableTarget(latitude, longitude)) {
            rejectRequest(id, "候选坐标不可用（lat 需在 ±90 内、lng 需在 ±180 内，且不能是 0,0）")
            return
        }
        // 地图这一项不通过（Key 没配、隐私没同意、没有定位权限，或者还在查）就不起导航：
        // 起了也只会在 SDK 里失败一次，还不如当场说清楚。
        val blocked = currentReport().navigationBlockedReason()
        if (blocked != null) {
            rejectRequest(id, "无法开始导航：$blocked")
            return
        }
        val running = activeNavigation
        if (running != null) {
            // 高德这边只跑一起导航：既不替策略决定改去新地点，也不偷偷把旧导航顶掉。
            rejectRequest(id, "本机已在为会话 ${running.sessionId} 导航，未开始新的导航")
            return
        }
        val pending = PendingNavigation(id, sessionId)
        activeNavigation = pending
        Log.i(TAG, "policy.navigation.start session=$sessionId candidate=$candidateId")
        navigationDevice().startWalking(
            latitude,
            longitude,
            object : AgentNavigationDevice.NavigationCallback {
                override fun onRouteReady(distanceM: Int, timeSec: Int) {
                    // 设备层只有在 GPS 导航真的起来之后才回调这里，所以这句话这时候才成立。
                    if (!settle(pending)) return
                    Log.i(TAG, "navigation started session=$sessionId distanceM=$distanceM timeSec=$timeSec")
                    sendToAgent(
                        JSONObject()
                            .put("id", id)
                            .put("type", "response")
                            .put(
                                "payload",
                                JSONObject().put("started", true).put("distanceM", distanceM).put("timeSec", timeSec),
                            ),
                    )
                }

                override fun onRouteFailed(reason: String) {
                    activeNavigation = null
                    if (settle(pending)) {
                        rejectRequest(id, reason)
                        return
                    }
                    // 已经回过 started 之后的失败：原样转给 WebView，怎么处理由 Agent 层决定。
                    forwardNavigation(sessionId, KIND_ROUTE_FAILED, reason)
                }

                override fun onNavigationText(text: String) {
                    forwardNavigation(sessionId, KIND_NAVIGATION_TEXT, text)
                }

                override fun onOffRoute() {
                    forwardNavigation(sessionId, KIND_OFF_ROUTE, "偏离计划路线")
                }

                override fun onRerouted() {
                    forwardNavigation(sessionId, KIND_REROUTED, "已重新规划路线")
                }

                override fun onLocationWeak(weak: Boolean, detail: String) {
                    forwardNavigation(navigationPayload(sessionId, KIND_LOCATION_WEAK, detail).put("weak", weak))
                }

                override fun onProgress(remainDistanceM: Int) {
                    forwardNavigation(
                        navigationPayload(sessionId, KIND_PROGRESS, "剩余 ${remainDistanceM} 米")
                            .put("remainDistanceM", remainDistanceM),
                    )
                }

                override fun onArrived() {
                    activeNavigation = null
                    forwardNavigation(sessionId, KIND_ARRIVED, "已到达目的地")
                }
            },
        )
    }

    /**
     * 只实现策略发起的 navigation.stop：真的停 SDK 导航，并让还在等终态的 start 立刻收到 error。
     *
     * payload.stopped 说明本机是不是真的停掉了一次进行中的导航；本来就没在导航时回 false，不假装停过。
     */
    private fun handlePolicyNavigationStop(message: JSONObject) {
        val id = message.optString("id")
        val sessionId = message.optJSONObject("payload")?.optString("sessionId").orEmpty()
        if (sessionId.isEmpty()) {
            rejectRequest(id, "policy.navigation.stop 缺少 payload.sessionId")
            return
        }
        val running = activeNavigation
        if (running != null && running.sessionId != sessionId) {
            // 不替策略去停别的会话：停错了比停不动更难查。
            rejectRequest(id, "本机在导航的会话是 ${running.sessionId}，未停止 $sessionId 的导航")
            return
        }
        activeNavigation = null
        navigationDevice().stopWalking { stopped ->
            // start 还没拿到终态就被停了：必须回一个 error，不能让对面等 requestNative 超时。
            if (running != null && settle(running)) rejectRequest(running.id, "导航在开始前被停止")
            Log.i(TAG, "policy.navigation.stop session=$sessionId stopped=$stopped")
            sendToAgent(
                JSONObject()
                    .put("id", id)
                    .put("type", "response")
                    .put("payload", JSONObject().put("stopped", stopped)),
            )
        }
    }

    /** 一次还没拿到终态回复的 navigation.start；终态只允许回一次。 */
    private class PendingNavigation(val id: String, val sessionId: String) {
        var settled = false
    }

    /** 返回 true 的那一次调用负责回 response；之后同一个请求只能转发，不能再回。 */
    private fun settle(pending: PendingNavigation): Boolean {
        if (pending.settled) return false
        pending.settled = true
        return true
    }

    /** 只认真正的 JSON 数字：字符串、null、NaN 一律当非法坐标。 */
    private fun coordinate(source: JSONObject, key: String): Double? {
        val value = source.opt(key) ?: return null
        if (value !is Number) return null
        val number = value.toDouble()
        return if (number.isFinite()) number else null
    }

    /** 和 AgentNavigationDevice 用同一条判定：越界和 (0,0) 都不送去算路。 */
    private fun isUsableTarget(latitude: Double, longitude: Double): Boolean =
        latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 &&
            !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001)

    private fun navigationPayload(sessionId: String, kind: String, detail: String): JSONObject =
        JSONObject().put("sessionId", sessionId).put("kind", kind).put("detail", detail)

    private fun forwardNavigation(sessionId: String, kind: String, detail: String) {
        forwardNavigation(navigationPayload(sessionId, kind, detail))
    }

    /** 只搬运 SDK 回调：不带 event_id、不带 sequence，也不判断任务该不该结束。 */
    private fun forwardNavigation(payload: JSONObject) {
        Log.i(
            TAG,
            "native.navigation kind=${payload.optString("kind")} session=${payload.optString("sessionId")}",
        )
        sendToAgent(
            JSONObject()
                .put("id", "native-navigation-${nativeEnvelopeCounter.incrementAndGet()}")
                .put("type", TYPE_NATIVE_NAVIGATION)
                .put("payload", payload),
        )
    }

    // ---- 眼镜蓝牙通道 -------------------------------------------------------

    /**
     * 只把设备层的回调搬成信封：不判断任务状态，不替 Agent 决定一段录音或一张照片该怎么用。
     *
     * 回调都在蓝牙后台线程上触发，所以状态更新一律切回主线程；发给 WebView 的 [AgentRuntimeWebView.send]
     * 自己也会切主线程。重连进度（第几次、等多久）只更新本机状态，不转成 native.device：
     * 重连是传输层的事，不能让 Agent 以为又多了一次设备事件，更不能让旧任务自己续上。
     */
    private inner class TransportListener : GlassesTransport.Listener {
        override fun onConnected(name: String) {
            main.post {
                glassesConnected = true
                glassesLink = RuntimeReadiness.LinkState.CONNECTED
                glassesState = "已连接（$name）"
                publishStatus()
            }
            Log.i(TAG, "glasses connected name=$name")
            forwardDevice(JSONObject().put("kind", KIND_CONNECTED).put("name", name))
        }

        override fun onDisconnected(reason: String) {
            // 断开就是这一轮的终点：在途的模型/识别/观察/播报一律作废，照片也不再留着。
            invalidateOperations("眼镜断开")
            // 播报出口连着就是这条已经断掉的通道，一起作废：不能留着它在重连后继续写新 socket。
            detachSpeech()?.close()
            main.post {
                glassesConnected = false
                glassesLink = RuntimeReadiness.LinkState.DOWN
                glassesState = "未连接（$reason）"
                publish("眼镜已断开：$reason")
            }
            Log.w(TAG, "glasses disconnected reason=$reason")
            forwardDevice(JSONObject().put("kind", KIND_DISCONNECTED).put("reason", reason))
        }

        override fun onReconnecting(attempt: Int, delayMs: Long) {
            main.post {
                glassesLink = RuntimeReadiness.LinkState.CONNECTING
                glassesState = "第 $attempt 次重连将在 ${delayMs / 1000} 秒后开始"
                publishStatus()
            }
        }

        override fun onReconnectExhausted(reason: String) {
            main.post {
                glassesLink = RuntimeReadiness.LinkState.DOWN
                glassesState = "未连接（$reason）"
                publish("眼镜未连接：$reason")
            }
        }

        override fun onEvent(type: String, payload: String, sequence: Long) {
            // 播报回执先给播报出口消费；原始事件照旧原样转给 Agent，由 Agent 层决定怎么解释。
            speech?.onGlassesEvent(type, payload)
            forwardDevice(
                JSONObject()
                    .put("kind", KIND_EVENT)
                    .put("type", type)
                    .put("payload", payload)
                    .put("sequence", sequence),
            )
        }

        /**
         * 整段 WAV 只在本机后台线程上转写成用户原话，字节和转写过程都不进 JS。
         *
         * 这里只有转写：不管这段录音是为了什么采集的，出口都是同一段原话，
         * 怎么理解、这一轮算不算数，全都由仓库里的 TypeScript Agent 决定。
         * 转写失败不编造文本：信封里只带 error，transcript 字段根本不出现。
         */
        override fun onAudio(wav: ByteArray, purpose: String) {
            Log.i(TAG, "glasses audio purpose=$purpose bytes=${wav.size}")
            val generation = operationGeneration.get()
            submit {
                if (generation != operationGeneration.get()) {
                    Log.i(TAG, "recognition retired before it started purpose=$purpose")
                    return@submit
                }
                val payload = JSONObject().put("purpose", purpose)
                val port = openModel()
                val bridge = port.bridge
                if (bridge == null) {
                    payload.put("error", port.reason)
                } else {
                    try {
                        val transcript = bridge.transcribeSpeech(wav)
                        Log.i(TAG, "speech transcribed purpose=$purpose chars=${transcript.length}")
                        payload.put("transcript", transcript)
                    } catch (error: Throwable) {
                        Log.w(
                            TAG,
                            "speech transcription failed purpose=$purpose error=${error.javaClass.simpleName}",
                        )
                        payload.put("error", error.message ?: error.javaClass.simpleName)
                    }
                }
                // 识别是阻塞的：回来时这一轮可能已经被取消或断开，结果不再属于任何人。
                if (generation != operationGeneration.get()) {
                    Log.i(TAG, "recognition retired while running purpose=$purpose")
                    return@submit
                }
                sendEnvelope(TYPE_NATIVE_SPEECH, payload)
            }
        }

        /** 照片只存在本服务内存里，JS 只拿到不透明 id 和随照片一起来的标签。 */
        override fun onPhoto(jpeg: ByteArray, tag: String) {
            val mediaRef = storeMedia(jpeg)
            Log.i(TAG, "glasses photo tag=$tag bytes=${jpeg.size} mediaRef=$mediaRef")
            sendEnvelope(
                TYPE_NATIVE_PHOTO,
                JSONObject().put("tag", tag).put("mediaRef", mediaRef),
            )
        }
    }

    /**
     * 存一张眼镜照片并返回不透明 id。
     *
     * 只留最近 [MAX_PENDING_MEDIA] 张：这是给上层短期取用的引用，不是照片仓库，内存里不该无限堆整张 JPEG。
     * 刚存进去的那张永远在最后，只会被挤掉更早的。
     */
    private fun storeMedia(jpeg: ByteArray): String {
        val mediaRef = UUID.randomUUID().toString()
        synchronized(media) {
            media[mediaRef] = jpeg
            while (media.size > MAX_PENDING_MEDIA) {
                val oldest = media.keys.firstOrNull() ?: break
                media.remove(oldest)
            }
        }
        return mediaRef
    }

    /** 通道回调统一用 native.device，靠 payload.kind 区分连上、断开和普通事件。 */
    private fun forwardDevice(payload: JSONObject) {
        sendEnvelope(TYPE_NATIVE_DEVICE, payload)
    }

    /** 只分配传输用的信封号，不参与 AgentEvent 的序号；多线程都会发，所以计数必须是原子的。 */
    private fun sendEnvelope(type: String, payload: JSONObject) {
        Log.i(TAG, "native envelope type=$type kind=${payload.optString("kind")}")
        sendToAgent(
            JSONObject()
                .put("id", "native-envelope-${nativeEnvelopeCounter.incrementAndGet()}")
                .put("type", type)
                .put("payload", payload),
        )
    }

    /**
     * 转发 JS 发来的设备指令：把 type/payload 原样写到蓝牙通道，不解析、也不加自己的判断。
     *
     * 是录音授权（voice.arm）、拍摄授权（entrance.arm）还是别的消息，本机不关心——什么时候该授权由
     * Agent/策略决定，Kotlin 不当第二套决策者。没连上或写失败一律回 error，不假装发出去了。
     *
     * 唯一的例外是采集授权本身：本机连模型或 Agent 都没起来时，这段录音/这张照片不可能被真正处理，
     * 这时必须当场回 error，而不是把会话装成开起来了（见 [RuntimeReadiness.Report.captureBlockedReason]）。
     */
    private fun handleDeviceSend(message: JSONObject) {
        val id = message.optString("id")
        val payload = message.optJSONObject("payload")
        if (payload == null) {
            rejectRequest(id, "device.send 缺少 payload")
            return
        }
        val type = payload.optString("type")
        if (type.isEmpty()) {
            rejectRequest(id, "device.send 缺少 payload.type")
            return
        }
        // 取消是**先作废再发**：上层发 task.cancel 的时候，本机在途的推理/识别/观察/播报以及缓存照片
        // 都已经属于上一件事了，必须在把这条指令写上线之前就作废，否则它们的回执会落到新会话上。
        if (type == TYPE_TASK_CANCEL) {
            invalidateOperations("task.cancel")
            // 取消的是这一轮：队列要清，出口本身也要作废——它记着这一轮的通道和代号，
            // 留着它，取消之后的那句话还会从旧出口说出去。
            detachSpeech()?.close()
        }
        if (type == TYPE_VOICE_ARM || type == TYPE_ENTRANCE_ARM) {
            val blocked = currentReport().captureBlockedReason()
            if (blocked != null) {
                rejectRequest(id, "无法开始采集：$blocked")
                return
            }
        }
        val raw = payload.opt("payload")
        val text = when {
            raw == null || raw === JSONObject.NULL -> ""
            raw is String -> raw
            // 非字符串负载按它自己的 JSON 文本发；眼镜侧本来就把它当字符串读完再 trim。
            else -> raw.toString()
        }
        Log.i(TAG, "device.send type=$type payloadChars=${text.length}")
        if (!glassesTransport().send(type, text)) {
            rejectRequest(id, "设备通道未连接或写入失败：$type")
            return
        }
        sendToAgent(
            JSONObject()
                .put("id", id)
                .put("type", "response")
                .put("payload", JSONObject().put("sent", true).put("type", type)),
        )
    }

    // ---- 播报通道 -----------------------------------------------------------

    /**
     * 本机唯一的播报入口：先把播报出口准备好（蓝牙通道 + 离线语音模型），再把文本排进队列。
     *
     * 准备和合成都可能长时间阻塞（离线模型加载是秒级的），所以整段放在 worker 上跑。没有通道、
     * 没有模型、文本为空一律回明确 error 并照实写状态，绝不假装说过了。
     *
     * [replyWhenFinished] 为 true 时，只有收到眼镜真正播放完成的回执才回 `{finished:true}`；
     * Effect 是单向的，走 false，不回 response。
     */
    private fun speak(
        id: String, text: String, urgent: Boolean, replyWhenFinished: Boolean,
        allowRepeat: Boolean = false,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            rejectSpeech(id, "要播报的文本是空的")
            return
        }
        val generation = operationGeneration.get()
        submit {
            if (generation != operationGeneration.get()) {
                Log.i(TAG, "speech retired before it started id=$id")
                return@submit
            }
            val port = openSpeech(generation)
            // 准备播报出口是阻塞的（离线模型加载是秒级）：回来时这一句可能已经被取消，不再入队。
            // 先判这一轮还算不算数，再判出口有没有建成——被作废的一轮不回任何话，也不会把
            // “运行时已作废”当成一次播报失败报给上层（openSpeech 建好的出口这时已经关掉了）。
            if (generation != operationGeneration.get()) {
                Log.i(TAG, "speech retired while preparing id=$id")
                return@submit
            }
            val output = port.output
            if (output == null) {
                rejectSpeech(id, port.reason)
                return@submit
            }
            output.say(trimmed, urgent, deduplicate = !allowRepeat, onComplete = { played ->
                // 回执可能来得比取消晚：这一轮已经被作废就不再回话，避免旧会话的回执落到新会话上。
                if (generation != operationGeneration.get()) {
                    Log.i(TAG, "speech callback retired id=$id played=$played")
                    return@say
                }
                if (replyWhenFinished) {
                    if (played) finishSpeech(id)
                    else rejectSpeech(id, "眼镜未确认完成播报")
                }
            })
        }
    }

    /** 播报出口的准备结果：要么给一个能播的出口，要么给一句能直接展示的失败原因。 */
    private class SpeechPort(val output: AgentSpeechOutput?, val reason: String = "")

    /**
     * 懒建播报出口：只有真的要播报时才建，而且必须在蓝牙通道之后建。
     *
     * 离线模型加载是秒级阻塞，只允许在 worker 线程调用。缺通道、缺模型、模型坏了都返回带原因的
     * 失败，而不是先建一个播不出去的出口——那样要等到播放时才发现，上层已经以为说完了。
     *
     * [generation] 是调用方入队时那一轮的代号。建出口是阻塞的，回来时这一轮可能已经被
     * task.cancel / 断开 / 手动重连作废（它们都会摘掉旧出口并自增代号），所以**登记必须和复查
     * 代号在同一把 [bridgeLock] 里一次做完**：否则收尾线程可能刚好在“建好”和“登记”之间跑完，
     * 于是一个已经没人认领的出口被登记进已经清空的运行时里，接着往一条关掉的通道上写。
     * 没登记上的实例由本方法当场关掉，绝不留着一个已经起在通道上的出口。
     */
    private fun openSpeech(generation: Long): SpeechPort {
        val existing = synchronized(bridgeLock) { speech }
        if (existing != null) return SpeechPort(existing)
        if (cleanedUp.get()) return SpeechPort(null, "运行时已停止")
        val channel = transport
        if (channel == null || !glassesConnected) {
            return SpeechPort(null, "眼镜蓝牙通道还没连上，无法播报")
        }
        val missing = OfflineTtsBridge.missingModelFiles(this)
        if (missing.isNotEmpty()) {
            return SpeechPort(
                null,
                "离线语音模型不可用：缺少 " + missing.joinToString("、") +
                    "（目录 " + OfflineTtsBridge.resolveModelDir(this).absolutePath + "）",
            )
        }
        val held = tts
        val bridge = held ?: OfflineTtsBridge(this)
        try {
            bridge.initialize()
        } catch (error: TtsBridgeException) {
            Log.w(TAG, "offline tts unavailable: ${error.message}")
            if (held == null) runCatching { bridge.close() }
            return SpeechPort(null, "离线语音模型不可用：" + (error.message ?: "未知原因"))
        } catch (error: Throwable) {
            Log.w(TAG, "offline tts failed", error)
            if (held == null) runCatching { bridge.close() }
            return SpeechPort(null, "离线语音模型不可用：" + error.javaClass.simpleName)
        }
        // 加载是阻塞的，回来时可能已经在收尾：刚建的引擎既不能登记，也不能漏着不关。
        if (held == null && !adoptTts(bridge)) {
            runCatching { bridge.close() }
            return SpeechPort(null, "运行时已停止")
        }
        val created = AgentSpeechOutput(
            channel,
            bridge,
            AgentSpeechOutput.Listener { message -> onSpeechStatus(message) },
        )
        val adopted = synchronized(bridgeLock) {
            if (cleanedUp.get() || generation != operationGeneration.get() || speech != null) {
                false
            } else {
                speech = created
                created.start()
                true
            }
        }
        if (!adopted) {
            runCatching { created.close() }
            return SpeechPort(null, "这一轮已经作废，未建播报出口")
        }
        Log.i(TAG, "speech output ready model=${bridge.modelPath}")
        return SpeechPort(created)
    }

    /** 播报线程上的状态回调（入队就被丢掉的那一句也走这里）：只记日志，界面文字切回主线程再改。 */
    private fun onSpeechStatus(message: String) {
        Log.i(TAG, "speech status=$message")
        publish(message)
    }

    /**
     * 眼镜回执确认整句播完才回 `{finished:true}`。
     *
     * 入队就回等于谎报：Agent 那边会以为眼镜已经说完了，接着往下走。停止之后一律不回。
     */
    private fun finishSpeech(id: String) {
        if (id.isEmpty() || cleanedUp.get()) return
        Log.i(TAG, "speech finished id=$id")
        sendToAgent(
            JSONObject()
                .put("id", id)
                .put("type", "response")
                .put("payload", JSONObject().put("finished", true)),
        )
    }

    /** 播报没进行：回明确 error 并写状态，不假装说过。 */
    private fun rejectSpeech(id: String, reason: String) {
        Log.w(TAG, "speech unavailable: $reason")
        publish("未播报：$reason")
        if (id.isNotEmpty()) rejectRequest(id, reason)
    }

    /** 只有 critical / high 插队；normal / detail 排在后面，不打断正在播的那一段。 */
    private fun isUrgent(priority: String): Boolean =
        priority == PRIORITY_CRITICAL || priority == PRIORITY_HIGH

    // ---- 状态与通知 ---------------------------------------------------------

    private fun snapshot(): Status {
        val report = currentReport()
        return Status(
            headline = headline,
            lines = report.lines(),
            ready = report.ready,
            glassesConnected = glassesConnected,
            agentLoaded = agent?.isReady == true,
            captureBlockedReason = report.captureBlockedReason(),
            stopped = cleanedUp.get(),
        )
    }

    private fun currentReport(): RuntimeReadiness.Report =
        readiness.report(
            agentReady = agent?.isReady == true,
            link = glassesLink,
            glassesState = glassesState,
        )

    /** 更新状态标题；可以在任意线程调用。 */
    private fun publish(text: String) {
        headline = text
        publishStatus()
    }

    /** 刷新通知和观察者：[Status] 里的自检项每次都重新算，界面看到的永远是真状态。 */
    private fun publishStatus() {
        main.post {
            if (cleanedUp.get()) return@post
            val current = snapshot()
            if (inForeground) {
                val manager = getSystemService(NotificationManager::class.java)
                try {
                    manager?.notify(NOTIFICATION_ID, buildNotification(current.headline))
                } catch (error: Throwable) {
                    Log.w(TAG, "notification update failed", error)
                }
            }
            for (observer in ArrayList(observers)) {
                try {
                    observer.onStatus(current)
                } catch (error: Exception) {
                    Log.w(TAG, "status observer failed", error)
                }
            }
        }
    }

    /**
     * 进入前台，并声明当前真正持有的服务类型。
     *
     * 类型按权限现算：没有蓝牙权限就不声明 connectedDevice，没有定位权限就不声明 location——
     * 声明了却没有权限会直接抛 SecurityException。一个类型都声明不了，就明确失败，不硬撑。
     */
    private fun enterForeground(): Boolean {
        val types = foregroundTypes()
        if (types == 0) {
            publish("缺少前台服务所需权限，未启动运行时")
            return false
        }
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(headline), types)
            inForeground = true
            true
        } catch (error: Throwable) {
            Log.e(TAG, "startForeground failed", error)
            publish("前台服务启动失败：" + describe(error))
            false
        }
    }

    private fun foregroundTypes(): Int {
        var types = 0
        if (hasBluetoothPermission()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (PhoneLocationAdapter.hasPermission(this)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun hasBluetoothPermission(): Boolean =
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(text: String): Notification {
        val content = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, AgentProofActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reconnect = PendingIntent.getService(
            this,
            REQUEST_RECONNECT,
            Intent(this, AgentHostService::class.java).setAction(ACTION_RECONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, AgentHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(content)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.stat_sys_data_bluetooth),
                    "重连眼镜",
                    reconnect,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "停止",
                    stop,
                ).build(),
            )
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, NOTIFICATION_TITLE, NotificationManager.IMPORTANCE_LOW)
        channel.description = "保持 Agent、本地模型与眼镜蓝牙通道在后台运行"
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    // ---- 唤醒锁与收尾 -------------------------------------------------------

    /**
     * 部分唤醒锁：服务真正跑着的时候才持有，收尾时一定释放。
     *
     * 不带超时是有意的——锁的生命周期就是服务的生命周期，而服务只能被“停止”结束，
     * 所以没有“拿着锁睡死过去”的路径；泄漏由 [releaseWakeLock] 兜底。
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val manager = getSystemService(PowerManager::class.java)
        if (manager == null) {
            Log.w(TAG, "no power manager; wake lock not held")
            return
        }
        val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        // 不计数：重复 acquire/release 不会把锁的层数搞乱，收尾时一次释放就干净。
        lock.setReferenceCounted(false)
        try {
            lock.acquire()
        } catch (error: Throwable) {
            Log.w(TAG, "wake lock acquire failed", error)
            return
        }
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (error: Throwable) {
            Log.w(TAG, "wake lock release failed", error)
        }
    }

    /**
     * 停止整个宿主：当场把运行时收干净，再退出前台、结束服务。
     *
     * 不能只 stopSelf：界面还绑着的时候系统不会销毁这个服务，onDestroy 就不会来，会话和资源会一直挂着。
     * 所以这里自己做完整套收尾（幂等），并在清掉观察者之前发一次终态——界面据此解绑、把控制权还给用户，
     * 之后重新点“重新连接眼镜”还能再来一次。
     */
    private fun stopHost(reason: String = STOP_TEXT) {
        if (cleanedUp.get()) return
        headline = reason
        notifyTerminal(reason)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (error: Throwable) {
            Log.w(TAG, "stopForeground failed", error)
        }
        inForeground = false
        cleanup()
        stopSelf()
    }

    /**
     * 给观察者发一次终态快照。
     *
     * 不走 [publishStatus]：那条路在收尾开始后会把消息丢掉，而这一条正是“已经收尾了”本身。
     * 观察者列表的清理排在它后面（同一个主线程队列），所以界面一定收得到这一条。
     */
    private fun notifyTerminal(headline: String) {
        val terminal = Status(
            headline = headline,
            lines = emptyList(),
            ready = false,
            glassesConnected = false,
            agentLoaded = false,
            captureBlockedReason = "运行时已停止，录音与拍摄都不会自动恢复",
            stopped = true,
        )
        main.post {
            for (observer in ArrayList(observers)) {
                try {
                    observer.onStatus(terminal)
                } catch (error: Exception) {
                    Log.w(TAG, "status observer failed", error)
                }
            }
        }
    }

    /**
     * 释放全部运行时资源；幂等，重复调用无副作用。
     *
     * 顺序是有讲究的：先作废播报（排队中的句子会被兑现 onComplete，但停止之后不再往外回话），
     * 再停蓝牙通道（同时作废自动重连），最后释放模型与线程池。
     */
    private fun cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return
        // 先作废代号：之后任何在途任务回来都会被挡住，不会再往 WebView 或通道上送东西。
        operationGeneration.incrementAndGet()
        inForeground = false
        // 主线程上能立刻收干净的：播报、蓝牙通道、WebView、导航、照片、唤醒锁。
        // 播报出口先摘后关：摘是要和 openSpeech 的登记互斥（不能让一个刚建好的出口登记进已经
        // 收尾的运行时），关是把它的线程和队列收掉——close 不在锁里做，它要等播报线程让开。
        detachSpeech()?.close()
        transport?.close()
        transport = null
        agent?.close()
        agent = null
        navigation?.close()
        navigation = null
        activeNavigation = null
        glassesConnected = false
        glassesLink = RuntimeReadiness.LinkState.DOWN
        glassesState = "已停止"
        synchronized(media) { media.clear() }
        releaseWakeLock()
        // 观察者留到终态发完之后再清：主线程队列是先进先出的，界面不会漏掉“已经停了”。
        main.post { observers.clear() }
        // 模型与语音的 close 和推理/加载共用一把锁，推理可能还要跑几分钟：交给收尾线程，主线程不等。
        val modelBridge = takeModel()
        val ttsBridge = takeTts()
        try {
            teardown.execute { closeEngines(modelBridge, ttsBridge) }
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "teardown already finished: " + describe(error))
        }
        teardown.shutdown()
        // worker 上排着的活已经不成立了：打断它们，不让它们占着 CPU 继续算。
        worker.shutdownNow()
    }

    /** 收尾线程上关引擎；关不动只记日志，不往外抛。 */
    private fun closeEngines(modelBridge: GemmaLocalBridge?, ttsBridge: OfflineTtsBridge?) {
        if (modelBridge != null) {
            try {
                modelBridge.close()
            } catch (error: Throwable) {
                Log.w(TAG, "model close failed", error)
            }
        }
        if (ttsBridge != null) {
            try {
                ttsBridge.close()
            } catch (error: Throwable) {
                Log.w(TAG, "tts close failed", error)
            }
        }
    }

    private fun describe(error: Throwable): String {
        val message = error.message
        return if (message.isNullOrEmpty()) error.javaClass.simpleName
        else error.javaClass.simpleName + "：" + message
    }

    companion object {
        private const val TAG = "LeqiAgentHost"

        const val ACTION_START = "com.leqi.experiment.phonebt.action.START"
        const val ACTION_STOP = "com.leqi.experiment.phonebt.action.STOP"
        const val ACTION_RECONNECT = "com.leqi.experiment.phonebt.action.RECONNECT"

        private const val CHANNEL_ID = "leqi_agent_host"
        private const val NOTIFICATION_TITLE = "乐奇助盲导航正在运行"
        private const val NOTIFICATION_ID = 41
        private const val REQUEST_OPEN = 42
        private const val REQUEST_RECONNECT = 43
        private const val REQUEST_STOP = 44

        private const val WORKER_THREAD_NAME = "leqi-agent-host"

        /** 模型与语音的关闭单独一条线程：它们的 close 会和推理抢同一把锁，不能占着主线程等。 */
        private const val TEARDOWN_THREAD_NAME = "leqi-agent-teardown"

        /** 用户按下“停止”或服务自己收尾时的终态说明；会话与采集一律不自动恢复。 */
        private const val STOP_TEXT = "已停止：录音、拍摄和导航都不会自动恢复"
        private const val WAKE_LOCK_TAG = "leqi:agent-host"

        private const val TOOL_SEARCH_DESTINATION = "navigation.search_destination"
        private const val PROVIDER_AMAP = "amap"

        /** 采集授权：本机必须先真的能处理这段录音/这张照片，才允许把眼镜打开。 */
        private const val TYPE_VOICE_ARM = "voice.arm"
        private const val TYPE_ENTRANCE_ARM = "entrance.arm"

        /**
         * 上层取消当前任务时先发的那一条。它本身要原样写到眼镜上（眼镜据此停播），
         * 同时在写之前把本机在途的原生操作全部作废。
         */
        private const val TYPE_TASK_CANCEL = "task.cancel"

        /** 本机发给 JS 的原生信封类型：只搬运设备事实，业务裁决仍在 Agent/策略层。 */
        private const val TYPE_NATIVE_DEVICE = "native.device"
        private const val TYPE_NATIVE_SPEECH = "native.speech"
        private const val TYPE_NATIVE_PHOTO = "native.photo"
        private const val TYPE_NATIVE_NAVIGATION = "native.navigation"

        /** 播报优先级，取值与 speech-effect 合同一致；只有前两档插队。 */
        private const val PRIORITY_CRITICAL = "critical"
        private const val PRIORITY_HIGH = "high"

        /** native.device 的 kind，与 GlassesTransport.Listener 的回调一一对应。 */
        private const val KIND_CONNECTED = "connected"
        private const val KIND_DISCONNECTED = "disconnected"
        private const val KIND_EVENT = "event"

        /** 本机内存里最多留几张眼镜照片。 */
        private const val MAX_PENDING_MEDIA = 4

        /** native.navigation 的 kind，与设备层 NavigationCallback 的回调一一对应。 */
        private const val KIND_NAVIGATION_TEXT = "navigation_text"
        private const val KIND_OFF_ROUTE = "off_route"
        private const val KIND_REROUTED = "rerouted"
        private const val KIND_LOCATION_WEAK = "location_weak"
        private const val KIND_PROGRESS = "progress"
        private const val KIND_ARRIVED = "arrived"
        private const val KIND_ROUTE_FAILED = "route_failed"

        /** 从可见界面启动运行时；权限与隐私必须先问过（见 AgentProofActivity）。 */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, AgentHostService::class.java).setAction(ACTION_START))
        }

        /** 停止运行时并释放资源。 */
        fun stop(context: Context) {
            context.startService(Intent(context, AgentHostService::class.java).setAction(ACTION_STOP))
        }

        /** 手动重连眼镜。 */
        fun reconnect(context: Context) {
            context.startService(Intent(context, AgentHostService::class.java).setAction(ACTION_RECONNECT))
        }
    }
}
