package com.leqi.experiment.phonel

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.*
import android.util.Base64
import android.util.Log
import com.leqi.experiment.phonebt.*
import com.rokid.cxr.session.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/** 前台服务拥有运行时。界面退出不会带走导航；进程重启不会恢复旧授权。 */
class CxrlAgentService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    private var web: AgentRuntimeWebView? = null
    private lateinit var device: CxrlAgentDevice
    private lateinit var navigation: AgentNavigationDevice
    private lateinit var connectionNotice: Notification
    private var connectionStarted = false
    private var cloud: DeepSeekClient? = null
    private var cloudTools: DeepSeekToolClient? = null
    private var utterance: CxrlSpeechPlayback? = null
    private val speechCache = SpeechAudioCache()
    @Volatile private var cloudConfigured = false
    private var counter = 0L
    private val media = LinkedHashMap<String, ByteArray>()
    private val plays = LinkedHashMap<String, (Boolean) -> Unit>()
    private var voiceTag: String? = null
    private var photoTag: String? = null
    private var microphone: IAudioCallback? = null
    private var microphoneSession: CxrSession? = null
    private var pcm: ByteArrayOutputStream? = null
    private var audioStop: (() -> Unit)? = null
    private var photoCallback: IImageCallback? = null
    private var photoSession: CxrSession? = null
    private var route: String? = null
    private var startingRoute: String? = null
    private val textTrace by lazy {
        SpeechTextDiagnostics(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d("LeqiSpeechText", it)
        }
    }

    // 只在调试包记录阶段、耗时和错误；不记录语音原文、音频或图片。
    private fun diagnostic(stage: String, detail: String) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            Log.d("LeqiAgentDiag", "$stage $detail")
    }

    /** 只记录动作种类与工具名，区分“口头提示”和“真正调用”；不记录对话或模型全文。 */
    private fun diagnosticPlan(id: String, raw: String) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val actions = runCatching { JSONObject(raw).getJSONArray("actions") }.getOrNull()
        if (actions == null) { diagnostic("model.plan", "id=$id parsed=false"); return }
        val summary = JSONArray()
        for (index in 0 until minOf(actions.length(), 4)) {
            val action = actions.optJSONObject(index) ?: continue
            val args = action.optJSONObject("arguments")
            summary.put(JSONObject().put("kind", action.optString("kind"))
                .put("tool", action.optString("tool_id"))
                .put("capability", args?.optJSONObject("parameters")?.optString("capability_id")
                    ?: args?.optString("capability_id") ?: ""))
        }
        diagnostic("model.plan", "id=$id actions=$summary")
    }

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("agent", "助盲任务", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        connectionNotice = Notification.Builder(this, "agent").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("乐奇助盲 Agent").setContentText("正在准备 DeepSeek 和眼镜连接")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "停止任务", stop).build()).build()
        updateForeground(connectionNotice)
        navigation = AgentNavigationDevice(this)
        web = AgentRuntimeWebView(this, ::handle)
        device = CxrlAgentDevice(this, ::onDevice) { reason ->
            retire()
            envelope("native.device", JSONObject().put("kind", "disconnected").put("reason", reason))
            status("眼镜断开：$reason；请重新连接")
        }
        worker.execute {
            val outcome = runCatching {
                val settings = AgentModelSettings(this)
                val key = try { settings.apiKey() } catch (_: Exception) {
                    throw ModelServiceException("cloud_key_unreadable")
                }
                if (key.isBlank()) throw ModelServiceException("cloud_key_missing")
                cloud = DeepSeekClient(key, settings.model, usage = settings::recordUsage)
                cloudTools = DeepSeekToolClient(key)
            }
            main.post {
                if (!closed) {
                    cloudConfigured = outcome.isSuccess
                    if (device.ready) device.command("status", if (cloudConfigured) "准备好了，短按开始；结束请在手机点停止任务与连接" else "请在手机检查 DeepSeek 密钥设置")
                    status(if (cloudConfigured) "DeepSeek 密钥已载入；连接后按键开始"
                        else if (outcome.exceptionOrNull() is ModelServiceException) "请在对话模型设置中填写有效的 DeepSeek 密钥"
                        else "DeepSeek 准备失败：${outcome.exceptionOrNull()!!.javaClass.simpleName}")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val token = intent.getStringExtra("token")
        if (!token.isNullOrBlank()) {
            updateForeground(connectionNotice)
            textTrace.enabled = intent.getBooleanExtra("textDiagnostics", false)
            if (connectionStarted) {
                retire()
                envelope("native.device", JSONObject().put("kind", "disconnected").put("reason", "重新连接"))
            }
            connectionStarted = true
            device.connect(token)
        }
        return START_NOT_STICKY
    }

    private fun updateForeground(notification: Notification) {
        var types = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (AmapPrivacy.isAgreed(this) && PhoneLocationAdapter.hasPermission(this) &&
            getSystemService(android.location.LocationManager::class.java).isLocationEnabled) {
            types = types or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        startForeground(1, notification, types)
    }

    private fun status(text: String) { main.post {
        if (!closed) getSystemService(NotificationManager::class.java).notify(1,
            Notification.Builder(this, "agent").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("乐奇助盲 Agent").setContentText(text).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "停止任务",
                    PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("stop"),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()).build())
    } }
    private fun envelope(type: String, payload: JSONObject) {
        web?.send(JSONObject().put("id", "cxrl-${++counter}").put("type", type).put("payload", payload))
    }
    private fun reply(id: String, value: Any? = JSONObject(), error: String? = null) {
        val packet = JSONObject().put("id", id).put("type", "response")
        if (error == null) packet.put("payload", value) else packet.put("error", error)
        web?.send(packet)
    }

    /** 一次原生操作前后都核对代号；取消期间结束的推理只能被丢弃。 */
    private fun compute(id: String, action: () -> Any) {
        val attempt = generation
        worker.execute {
            if (closed || attempt != generation) return@execute
            val result = runCatching { check(cloudConfigured) { "云服务配置尚未就绪" }; action() }
            main.post {
                if (!closed && attempt == generation) result.fold({ reply(id, it) },
                    { reply(id, error = (it as? ModelServiceException)?.code ?: "原生操作失败：${it.javaClass.simpleName}") })
            }
        }
    }

    private fun handle(message: JSONObject) {
        val id = message.optString("id")
        val payload = message.optJSONObject("payload") ?: JSONObject()
        try {
            when (message.optString("type")) {
                "ready" -> status("Agent 已加载；正在连接眼镜并读取云服务配置")
                "model.generate" -> {
                    val attempt = generation
                    compute(id) {
                        val response = checkNotNull(cloud).generate(payload.getString("prompt")) { closed || attempt != generation }
                        diagnosticPlan(id, response)
                        response
                    }
                }
                "vision.observe" -> {
                    val bytes = media.remove(payload.getString("mediaRef")) ?: error("照片已过期或已使用")
                    val attempt = generation
                    compute(id) {
                        val prompt = payload.getString("prompt")
                        val tools = checkNotNull(cloudTools)
                        val cancelled = { closed || attempt != generation }
                        when {
                            payload.optString("capabilityId") == "vision.menu" -> {
                                val result = tools.recognize(bytes, cancelled)
                                result.optInt("page_count", -1).takeIf { it >= 0 }?.let(AgentModelSettings(this)::recordOcr)
                                result.toString()
                            }
                            else -> checkNotNull(cloud).observe(prompt, bytes, cancelled)
                        }
                    }
                }
                "speech.say" -> say(payload.getString("text")) { ok ->
                    if (ok) reply(id, JSONObject().put("finished", true)) else reply(id, error = "眼镜播报未完成")
                }
                "device.send" -> deviceCommand(id, payload)
                "tool.execute" -> search(id, payload)
                "policy.navigation.start" -> startRoute(id, payload)
                "policy.navigation.stop" -> {
                    if (route != null && route != payload.getString("sessionId")) error("不能停止其他会话的导航")
                    route = null
                    startingRoute?.let { reply(it, error = "导航在开始前被取消") }; startingRoute = null
                    navigation.stopWalking { stopped -> reply(id, JSONObject().put("stopped", stopped)) }
                }
                "error" -> {
                    diagnostic("agent.error", "id=$id detail=${message.optString("error")}")
                    status("本轮任务未完成，请检查手机提示后重试")
                }
                "event.result" -> diagnostic("event.result", "id=$id rejection=${payload.optJSONObject("rejection")?.optString("code")} tools=${payload.optJSONArray("toolResults")}")
                "effect" -> Unit // speech.say 已统一负责真实播放，Effect 不重复播报。
                else -> reply(id, error = "不支持的原生请求")
            }
        } catch (error: Exception) { reply(id, error = "请求失败：${error.javaClass.simpleName}") }
    }

    private fun deviceCommand(id: String, request: JSONObject) {
        val type = request.getString("type")
        val tag = request.optString("payload")
        when (type) {
            "task.cancel" -> retire(notifyDevice = false)
            "voice.arm" -> { check(microphone == null && photoCallback == null); voiceTag = tag; photoTag = null }
            "entrance.arm" -> { check(microphone == null && photoCallback == null); photoTag = tag; voiceTag = null }
            "voice.disarm" -> { voiceTag = null; stopRecording(false) }
            "entrance.disarm" -> {
                photoTag = null
                // 图片没有请求 ID；超时/撤销后关闭该连接，防止迟到的图片被下一次拍照接收。
                if (photoCallback != null) device.fail("拍照请求已撤销，请重新连接")
            }
            else -> error("不支持的设备指令")
        }
        if (!device.ready && type in setOf("task.cancel", "voice.disarm", "entrance.disarm")) {
            reply(id, JSONObject().put("sent", false))
            return
        }
        device.command(type, tag) { it.fold({ reply(id, JSONObject().put("sent", true)) },
            { reply(id, error = "设备指令发送失败") }) }
    }

    private fun onDevice(packet: JSONObject) {
        val type = packet.optString("type")
        val tag = packet.optString("payload")
        when (type) {
            "link.ready" -> {
                status("眼镜已连接，读取云服务配置后可按键开始")
                device.command("status", if (cloudConfigured) "准备好了，短按开始；结束请在手机点停止任务与连接" else "正在读取 DeepSeek 配置，请稍候")
                return
            }
            "play.received" -> return // 收齐音频后眼镜开始播放，完成以 play.done 为准。
            "play.done", "play.failed" -> {
                diagnostic(type, "playId=$tag")
                plays.remove(tag)?.invoke(type == "play.done"); return
            }
            "user.cancel" -> retire()
            "probe.glasses" -> if (!cloudConfigured) {
                device.command("status", "云服务配置尚未就绪，请稍候"); return
            } else if (microphone != null) {
                stopRecording(true); return
            }
            "voice.confirm" -> {
                if (tag != voiceTag || microphone != null) return
                startRecording(tag); return
            }
            "voice.finish" -> { stopRecording(true); return }
            "entrance.confirm" -> {
                if (tag != photoTag || photoCallback != null) return
                envelope("native.device", JSONObject().put("kind", "event").put("type", type).put("payload", tag))
                capture(tag); return
            }
        }
        envelope("native.device", JSONObject().put("kind", "event").put("type", type).put("payload", tag))
    }

    private fun startRecording(tag: String) {
        val current = device.current()
        val attempt = generation
        val bytes = ByteArrayOutputStream()
        pcm = bytes; microphoneSession = current
        val callback = object : IAudioCallback {
            override fun onAudioReceived(data: ByteArray) { main.post {
                if (attempt != generation || pcm !== bytes) return@post
                // CXR-L PCM 上行按 16kHz/单声道/16bit 接入；限长 15 秒，实机验收须核对格式。
                if (bytes.size() + data.size > 480000) stopRecording(true) else bytes.write(data)
            } }
            override fun onAudioError(errorCode: Int, errorMessage: String?) { main.post {
                if (attempt == generation && pcm === bytes) {
                    stopRecording(false); device.fail("眼镜麦克风异常")
                }
            } }
            override fun onAudioStreamStateChanged(isStreaming: Boolean) { main.post {
                if (!isStreaming && attempt == generation) audioStop?.invoke()
            } }
        }
        microphone = callback
        current.addAudioCallback(callback)
        if (!current.startAudioStream().isSuccess) { stopRecording(false); device.fail("无法开启眼镜麦克风"); return }
        device.command("voice.started", tag)
        main.postDelayed({ if (pcm === bytes && attempt == generation) stopRecording(true) }, 15000)
    }

    private fun stopRecording(deliver: Boolean) {
        val bytes = pcm ?: return
        val tag = voiceTag
        pcm = null; voiceTag = null
        val current = microphoneSession; val callback = microphone
        if (!deliver || tag == null) {
            audioStop = null; microphoneSession = null; microphone = null
            runCatching { current?.stopAudioStream() }
            if (callback != null) current?.removeAudioCallback(callback)
            return
        }
        // SDK 音频没有请求 ID：收到停止回执之前禁止下一段录音，避免旧 PCM 串入新轮次。
        val attempt = generation
        val finish: () -> Unit = {
            audioStop = null; microphoneSession = null; microphone = null
            if (callback != null) current?.removeAudioCallback(callback)
            device.command("voice.stopped")
            transcribe(bytes.toByteArray(), tag, attempt)
        }
        audioStop = finish
        val stopped = runCatching { current?.stopAudioStream()?.isSuccess == true }.getOrDefault(false)
        if (!stopped) { device.fail("无法停止眼镜麦克风"); return }
        main.postDelayed({ if (audioStop === finish) device.fail("麦克风停止回执超时") }, 5000)
    }
    private fun transcribe(raw: ByteArray, tag: String, attempt: Long) {
        worker.execute {
            if (closed || attempt != generation) return@execute
            val result = runCatching {
                check(raw.size >= 6400 && raw.size % 2 == 0)
                val tools = checkNotNull(cloudTools)
                val response = tools.transcribe(raw) { closed || attempt != generation }
                response.optLong("duration_ms", -1L).takeIf { it >= 0 }?.let(AgentModelSettings(this)::recordAsr)
                response.opt("text") as? String ?: throw ModelServiceException("cloud_format")
            }
            main.post {
                if (!closed && attempt == generation) {
                    val payload = JSONObject().put("purpose", tag)
                    result.onSuccess {
                        textTrace.record("asr", "cxrl-${counter + 1}", it)
                        payload.put("transcript", it)
                    }
                    (result.exceptionOrNull() as? ModelServiceException)?.let { payload.put("error", it.code) }
                    envelope("native.speech", payload)
                }
            }
        }
    }

    private fun capture(tag: String) {
        val current = device.current()
        val attempt = generation
        val callback = object : IImageCallback {
            override fun onImageReceived(data: ByteArray) {
                val receivedAt = Instant.now().toString()
                main.post {
                if (attempt != generation || photoTag != tag || photoCallback !== this) return@post
                clearPhoto()
                if (data.size !in 1..2097152) { device.fail("图片大小异常"); return@post }
                worker.execute {
                    val result = runCatching {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                        require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
                        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("图片解码失败")
                        try { ByteArrayOutputStream().use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)); output.toByteArray()
                        } } finally { bitmap.recycle() }
                    }
                    main.post {
                        if (closed || attempt != generation) return@post
                        result.fold({ jpeg ->
                            val ref = UUID.randomUUID().toString()
                            media.clear(); media[ref] = jpeg
                            envelope("native.photo", JSONObject().put("tag", tag).put("mediaRef", ref).put("capturedAt", receivedAt))
                        }, { device.fail("图片校验失败") })
                    }
                }
            } }
            override fun onImageError(errorCode: SessionErrorCode, v1ErrorCode: Int, v1ErrorMessage: String?) {
                main.post { if (attempt == generation) device.fail("眼镜拍照失败") }
            }
        }
        photoCallback = callback; photoSession = current
        current.addImageCallback(callback)
        if (!current.takePhoto(640, 480, 80).isSuccess) device.fail("无法发起眼镜拍照")
        main.postDelayed({ if (photoCallback === callback) device.fail("拍照超时，连接已作废") }, 20000)
    }
    private fun clearPhoto() {
        photoCallback?.let { photoSession?.removeImageCallback(it) }
        photoCallback = null; photoSession = null; photoTag = null
    }

    private fun say(text: String, completed: (Boolean) -> Unit) {
        if (!device.ready || !cloudConfigured || utterance != null) { completed(false); return }
        val attempt = generation
        val speechId = UUID.randomUUID().toString()
        textTrace.record("speech", speechId, text)
        diagnostic("speech.request", "playId=$speechId chars=${text.length}")
        val pipeline = CxrlSpeechPlayback(
            synthesize = { part, done -> computeSpeech(part, attempt, speechId, done) },
            play = { audio, done -> sendAudio(audio, attempt, speechId, done) },
            completed = { ok ->
                utterance = null
                if (!ok) {
                    // 合成失败或取消后，旧播放回执和超时不能影响下一轮任务。
                    plays.clear()
                    if (attempt == generation && device.ready) device.command("task.cancel")
                }
                completed(ok)
            },
        )
        utterance = pipeline
        try { pipeline.start(text) } catch (_: Exception) { pipeline.cancel() }
        // 整段播报须早于桥接层的 120 秒截止时间结束，超时后撤销本轮播报。
        main.postDelayed({ if (utterance === pipeline) pipeline.cancel() }, 110000)
    }

    private fun sendAudio(audio: SpeechAudio, attempt: Long, speechId: String, completed: (Boolean) -> Unit) {
        val playId = UUID.randomUUID().toString()
        diagnostic("speech.part", "speechId=$speechId playId=$playId")
        plays[playId] = completed
        val pcm = ByteArray(audio.samples.size * 2)
        audio.samples.forEachIndexed { index, value ->
            val n = (value * 32767f).toInt().coerceIn(-32768, 32767)
            pcm[index * 2] = n.toByte(); pcm[index * 2 + 1] = (n shr 8).toByte()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it.toInt() and 255) }
        val header = JSONObject().put("id", playId).put("bytes", pcm.size).put("rate", audio.sampleRate).put("sha256", hash)
        diagnostic("audio.send.start", "playId=$playId bytes=${pcm.size} rate=${audio.sampleRate} audioMs=${audio.samples.size * 1000L / audio.sampleRate}")
        fun failed() { plays.remove(playId)?.invoke(false) }
        device.command("play.start", header.toString()) { result ->
            if (result.isFailure) failed() else {
                // 逐块等待 SDK 接受再发送下一块，避免无限堆积原生队列。
                fun sendChunk(offset: Int) {
                    if (attempt != generation || !plays.containsKey(playId)) return
                    if (offset == pcm.size) {
                        diagnostic("audio.send.complete", "playId=$playId")
                        device.command("play.end", playId) { if (it.isFailure) failed() }
                        return
                    }
                    val count = minOf(3000, pcm.size - offset)
                    device.command("play.chunk", Base64.encodeToString(pcm, offset, count, Base64.NO_WRAP)) {
                        if (it.isFailure) failed() else sendChunk(offset + count)
                    }
                }
                sendChunk(0)
            }
        }
        main.postDelayed({ if (plays.containsKey(playId)) { failed(); device.fail("眼镜播放回执超时") } }, 110000)
    }
    private fun computeSpeech(text: String, attempt: Long, playId: String, done: (SpeechAudio?) -> Unit) {
        worker.execute {
            if (closed || attempt != generation) return@execute
            val started = SystemClock.elapsedRealtime()
            textTrace.record("tts.part", playId, text)
            diagnostic("tts.start", "playId=$playId")
            val result = runCatching {
                val tools = checkNotNull(cloudTools)
                speechCache.getOrSynthesize("deepseek:stella:pcm:$text") {
                    SpeechTransportAudio.prepare(tools.synthesize(text) { closed || attempt != generation }).also {
                        AgentModelSettings(this).recordTts(text.codePointCount(0, text.length))
                    }
                }
            }
            diagnostic("tts.complete", "playId=$playId elapsedMs=${SystemClock.elapsedRealtime() - started} success=${result.isSuccess} error=${result.exceptionOrNull()?.javaClass?.simpleName}")
            val audio = result.getOrNull()
            main.post { if (!closed && attempt == generation) {
                done(audio)
                if (audio == null) {
                    val message = when ((result.exceptionOrNull() as? ModelServiceException)?.code) {
                        "cloud_auth" -> "语音密钥未获授权，请检查手机的对话模型设置。"
                        "cloud_balance" -> "DeepSeek 余额不足，请检查账户。"
                        "cloud_timeout", "cloud_busy" -> "语音服务暂时繁忙，请稍后再按键。"
                        "cloud_network" -> "语音服务连接失败，请检查手机网络再按键。"
                        else -> "语音播报失败，请稍后再按键。"
                    }
                    status(message)
                    if (device.ready) device.command("service.alert", message)
                }
            } }
        }
    }

    private fun search(id: String, call: JSONObject) {
        val tool = call.getString("toolId")
        val attempt = generation
        val result = JSONObject().put("callId", "${call.getJSONObject("plan").getString("planId")}:${call.getInt("actionIndex")}")
            .put("sessionId", call.getString("sessionId")).put("toolId", tool).put("facts", JSONArray())
        check(tool == "navigation.search_destination") { "不支持的原生工具" }
        val query = call.getJSONObject("arguments").getString("query")
        AmapNavigationPanel.failedQuery = null
        fun failure(code: String, message: String, retryable: Boolean) {
            val hint = if (code == "navigation_not_configured" && AmapNavigationPanel.canOpen(this)) {
                AmapNavigationPanel.failedQuery = query
                "可在手机的高德导航设置中用高德地图搜索。"
            } else ""
            result.put("status", "failed").put("output", JSONObject()).put("error",
                JSONObject().put("code", code).put("message", message + hint).put("retryable", retryable))
        }
        if (!AmapPrivacy.isAgreed(this) || !PhoneLocationAdapter.hasPermission(this)) {
            result.put("completedAt", Instant.now().toString())
            failure("permission_denied", "请先在手机的高德导航设置中开启地点搜索与定位。", false)
            reply(id, result)
            return
        }
        if (!AmapNavigationPanel.hasKey(this)) {
            result.put("completedAt", Instant.now().toString())
            failure("navigation_not_configured", "手机尚未配置高德 Android Key。", false)
            reply(id, result)
            return
        }
        navigation.searchDestination(query) { found ->
            if (attempt != generation) return@searchDestination
            result.put("completedAt", Instant.now().toString())
            when (found) {
                is AgentNavigationDevice.SearchResult.Success -> {
                    val candidates = JSONArray()
                    found.candidates.forEach { candidate ->
                        candidates.put(JSONObject().put("candidate_id", candidate.poiId?.takeIf { it.isNotBlank() }
                            ?: "amap:${candidate.latitude},${candidate.longitude}").put("name", candidate.name)
                            .put("provider", "amap").put("location", JSONObject().put("lat", candidate.latitude).put("lng", candidate.longitude)))
                    }
                    result.put("status", "succeeded").put("output", JSONObject().put("candidates", candidates))
                }
                is AgentNavigationDevice.SearchResult.Failure -> failure(found.code, found.message, found.retryable)
            }
            reply(id, result)
        }
    }

    private fun startRoute(id: String, payload: JSONObject) {
        check(route == null) { "已有导航任务" }
        val location = payload.getJSONObject("candidate").getJSONObject("location")
        val lat = location.getDouble("lat"); val lng = location.getDouble("lng")
        require(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0))
        val session = payload.getString("sessionId")
        val attempt = generation
        route = session; startingRoute = id
        fun current() = !closed && attempt == generation && route == session
        fun send(kind: String, detail: String = "", distance: Int? = null, weak: Boolean? = null) {
            if (!current()) return
            val event = JSONObject().put("sessionId", session).put("kind", kind).put("detail", detail)
            distance?.let { event.put("remainDistanceM", it) }; weak?.let { event.put("weak", it) }
            envelope("native.navigation", event)
        }
        navigation.startWalking(lat, lng, object : AgentNavigationDevice.NavigationCallback {
            override fun onRouteReady(distanceM: Int, timeSec: Int) {
                if (!current() || startingRoute != id) return
                startingRoute = null
                reply(id, JSONObject().put("started", true).put("distanceM", distanceM).put("timeSec", timeSec))
            }
            override fun onRouteFailed(message: String) {
                if (!current()) return
                if (startingRoute == id) {
                    startingRoute = null
                    reply(id, JSONObject().put("started", false).put("error", JSONObject()
                        .put("code", "navigation_unavailable").put("message", message).put("retryable", false)))
                }
                send("route_failed", message); route = null
            }
            override fun onNavigationText(text: String) = send("navigation_text", text)
            override fun onOffRoute() = send("off_route")
            override fun onRerouted() = send("rerouted")
            override fun onLocationWeak(weak: Boolean, detail: String) = send("location_weak", detail, weak = weak)
            override fun onProgress(remainDistanceM: Int) = send("progress", distance = remainDistanceM)
            override fun onArrived() { send("arrived"); route = null }
        })
    }

    private fun retire(notifyDevice: Boolean = true) {
        AmapNavigationPanel.failedQuery = null
        generation++
        cloud?.cancel()
        cloudTools?.cancel()
        utterance?.cancel(); utterance = null
        voiceTag = null; photoTag = null
        stopRecording(false)
        // 也清理处于“已提交停止、尚未回执”的音频窗口。
        audioStop = null
        microphone?.let { microphoneSession?.removeAudioCallback(it) }
        runCatching { microphoneSession?.stopAudioStream() }
        microphone = null; microphoneSession = null
        clearPhoto(); media.clear()
        val pending = plays.values.toList(); plays.clear(); pending.forEach { it(false) }
        route = null
        startingRoute?.let { reply(it, error = "导航已取消") }; startingRoute = null
        navigation.stopWalking { }
        if (notifyDevice && device.ready) device.command("task.cancel")
    }
    override fun onDestroy() {
        textTrace.enabled = false
        retire(); closed = true
        device.close(); navigation.close(); web?.close(); web = null
        worker.execute { speechCache.clear() }
        worker.shutdown()
        super.onDestroy()
    }
}

/** 用户开启的调试连接才记录文字；JSON 转义保留原文且不会把换行变成伪造日志。 */
internal class SpeechTextDiagnostics(private val debuggable: Boolean, private val write: (String) -> Unit) {
    @Volatile var enabled = false

    fun record(stage: String, id: String, text: String) {
        if (debuggable && enabled)
            write(JSONObject().put("stage", stage).put("id", id).put("text", text).toString())
    }
}
