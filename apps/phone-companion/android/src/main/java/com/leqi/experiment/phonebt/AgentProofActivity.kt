package com.leqi.experiment.phonebt

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 权限与隐私界面，外加运行时状态的观察窗。
 *
 * 运行时（仓库 Agent 的 WebView、本地模型、离线语音、导航、蓝牙通道、媒体表）全部归
 * [AgentHostService] 所有，本界面**不持有**其中任何一个：它只做三件事——
 * 1. 在可见的时候问权限（附近的设备、通知、定位）和高德隐私；
 * 2. 启动前台服务、把状态显示出来；
 * 3. 把“重新连接眼镜”“停止并释放”两个动作转给服务。
 *
 * 因此界面被销毁重建（旋转、被系统回收）不会影响正在跑的会话、已经加载的模型或正在走的导航；
 * 反过来，界面里也永远不会有需要在这里释放的资源。
 */
class AgentProofActivity : Activity() {

    private lateinit var headline: TextView
    private lateinit var details: TextView

    /** 只在主线程读写。 */
    private var host: AgentHostService? = null
    private var bound = false
    private var visible = false
    private var runtimeRequested = false

    private val listener = AgentHostService.Listener { status -> render(status) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? AgentHostService.LocalBinder ?: return
            host = binder.host
            binder.host.observe(listener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 服务被杀属于异常路径：界面继续显示上一次的状态，等用户手动重来。
            host = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headline = TextView(this).apply { text = "乐奇助盲导航正在启动…"; textSize = 18f }
        content.addView(headline)
        details = TextView(this).apply { textSize = 13f }
        content.addView(ScrollView(this).apply { addView(details) })
        content.addView(Button(this).apply {
            text = "重新连接眼镜"
            setOnClickListener { requestReconnect() }
        })
        content.addView(Button(this).apply {
            text = "停止并释放资源"
            setOnClickListener { requestStop() }
        })
        root.addView(content)
        setContentView(root)
        prepareDevice()
    }

    /** 只在可见的窗口里绑定：绑太早或忘了解绑都会让服务留着一个界面引用。 */
    override fun onStart() {
        super.onStart()
        visible = true
        if (runtimeRequested) bindHost()
    }

    override fun onStop() {
        visible = false
        unbindHost()
        super.onStop()
    }

    // ---- 权限与隐私 ---------------------------------------------------------

    /**
     * 权限与隐私的顺序：先要权限（附近的设备、通知、定位），再问高德隐私，最后才可能启动前台服务。
     *
     * 服务一启动就要进前台，而前台类型靠权限撑着，所以这一步必须在界面可见时走完。
     */
    private fun prepareDevice() {
        val needed = ArrayList<String>()
        if (!hasBluetoothPermission()) needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!PhoneLocationAdapter.hasPermission(this)) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (needed.isNotEmpty()) {
            headline.text = "请先授予权限：附近的设备（连眼镜）、通知（前台服务）、定位（导航）"
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        preparePrivacy()
    }

    private fun preparePrivacy() {
        if (AmapPrivacy.isAgreed(this)) {
            startRuntime()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("地图与定位授权")
            .setMessage(AmapPrivacy.NOTICE)
            .setPositiveButton("同意并继续") { _, _ ->
                try {
                    AmapPrivacy.agree(this)
                } catch (error: AmapPrivacyException) {
                    Log.e(TAG, "AMap privacy setup failed", error)
                    headline.text = "地图初始化失败：${error.message}"
                }
                startRuntime()
            }
            .setNegativeButton("暂不使用地图") { _, _ ->
                // 不同意只是没有地点搜索与导航：本地识别与播报照旧，服务该起还是起。
                startRuntime()
            }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (!PhoneLocationAdapter.hasPermission(this)) {
            details.text = "未取得定位权限：地点搜索与步行导航不可用，其余本地功能照旧。"
        }
        if (!hasBluetoothPermission()) {
            // 没有这个权限，前台服务连类型都声明不了，启动只会失败：当场说清楚，等用户补权限。
            headline.text = "未授予“附近的设备”权限：运行时没有启动，请允许后再点“重新连接眼镜”"
            return
        }
        preparePrivacy()
    }

    private fun hasBluetoothPermission(): Boolean =
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // ---- 启动、观察、停止 ---------------------------------------------------

    /** 从可见界面启动前台服务；失败（权限被撤、系统拒绝）都在界面上说清楚，不静默。 */
    private fun startRuntime() {
        if (!hasBluetoothPermission()) {
            headline.text = "未授予“附近的设备”权限：运行时没有启动"
            return
        }
        runtimeRequested = true
        try {
            AgentHostService.start(this)
        } catch (error: Throwable) {
            Log.e(TAG, "foreground service start failed", error)
            headline.text = "前台服务启动失败：" + (error.message ?: error.javaClass.simpleName)
            return
        }
        bindHost()
    }

    private fun bindHost() {
        if (bound || !visible) return
        bound = bindService(
            Intent(this, AgentHostService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun unbindHost() {
        if (!bound) return
        bound = false
        host?.releaseObserver(listener)
        host = null
        try {
            unbindService(connection)
        } catch (error: IllegalArgumentException) {
            // 没绑上（或已经被系统解绑）时忽略：界面本来就没有需要回收的东西。
            Log.w(TAG, "unbind ignored", error)
        }
    }

    private fun requestReconnect() {
        val running = host
        if (running != null) {
            running.requestReconnect()
            return
        }
        // 还没绑上：走一次服务 Intent，服务自己会建运行时并重连。
        try {
            AgentHostService.reconnect(this)
            bindHost()
        } catch (error: Throwable) {
            Log.e(TAG, "reconnect request failed", error)
            headline.text = "重连请求失败：" + (error.message ?: error.javaClass.simpleName)
        }
    }

    private fun requestStop() {
        val running = host
        if (running != null) {
            running.requestStop()
            return
        }
        try {
            AgentHostService.stop(this)
        } catch (error: Throwable) {
            Log.e(TAG, "stop request failed", error)
        }
    }

    /** 只显示服务报上来的真实状态：界面不自己推断“就绪”，也不编一句更乐观的话。 */
    private fun render(status: AgentHostService.Status) {
        if (status.stopped) {
            // 服务已经收干净了：立刻解绑，别把自己挂在一个不存在的运行时上，也别让界面停在旧状态。
            runtimeRequested = false
            unbindHost()
            headline.text = status.headline
            details.text = "运行时已停止，资源已释放。要重新开始请点“重新连接眼镜”。"
            return
        }
        headline.text = status.headline
        val lines = ArrayList(status.lines)
        lines.add(
            if (status.ready) "自检：全部通过"
            else "自检：还有未通过项，按上面的原因处理后重试",
        )
        status.captureBlockedReason?.let { lines.add("采集已暂停：$it") }
        details.text = lines.joinToString("\n")
    }

    private companion object {
        const val TAG = "LeqiAgentProof"
        const val REQUEST_PERMISSIONS = 41
    }
}
