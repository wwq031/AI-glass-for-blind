package com.leqi.experiment.phonebt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 承载仓库 TypeScript Agent bundle 的无界面 WebView。
 *
 * 只接 [Context]：宿主是前台 [AgentHostService]，不是 Activity——Activity 会被销毁重建，运行时不能跟着走，
 * 所以这里不能持有任何 Activity。WebView 只认创建它的那个 Looper，因此所有调用都切到主线程。
 *
 * 本类只搬运信封：Native→JS 走 [send]，JS→Native 走 [onMessage]，不解码业务内容。
 */
class AgentRuntimeWebView(
    context: Context,
    private val onMessage: (JSONObject) -> Unit,
) : AutoCloseable {

    private val main = Handler(Looper.getMainLooper())
    private val waiting = ArrayDeque<String>()
    private val closed = AtomicBoolean(false)

    /** 只能从主线程读写：ready 状态和等待队列是一对。 */
    private var ready = false

    /** 只能在主线程创建：WebView 绑定创建时的 Looper。 */
    val view: WebView = WebView(context.applicationContext)

    /** Agent bundle 是否已经报过 ready；供启动自检读取。 */
    val isReady: Boolean get() = ready

    init {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = false
        view.settings.allowFileAccess = true
        view.settings.allowContentAccess = false
        view.settings.setAllowUniversalAccessFromFileURLs(false)
        view.settings.setAllowFileAccessFromFileURLs(false)
        view.settings.blockNetworkLoads = true
        view.webViewClient = object : WebViewClient() {}
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i(TAG, "JS ${message.messageLevel()} ${message.message()}")
                return true
            }
        }
        view.addJavascriptInterface(NativeInterface(), "LeQiNative")
        view.loadUrl("file:///android_asset/agent/index.html")
    }

    /** 发一个信封给 Agent；Agent 还没 ready 时按顺序排队，不丢。 */
    fun send(message: JSONObject) {
        val json = message.toString()
        main.post {
            if (closed.get()) return@post
            if (ready) deliver(json) else waiting.addLast(json)
        }
    }

    private fun deliver(json: String) {
        Log.i(TAG, "Native→Agent ${JSONObject(json).optString("type")}")
        view.evaluateJavascript("window.LeQiAgent.receive(${JSONObject.quote(json)})", null)
    }

    private inner class NativeInterface {
        @JavascriptInterface
        fun send(json: String) {
            main.post {
                if (closed.get()) return@post
                val message = try { JSONObject(json) } catch (_: Exception) { return@post }
                Log.i(TAG, "Agent→Native ${message.optString("type")}")
                if (message.optString("type") == "ready") {
                    ready = true
                    while (waiting.isNotEmpty()) deliver(waiting.removeFirst())
                }
                onMessage(message)
            }
        }
    }

    /** 销毁 WebView；幂等，重复调用无副作用。之后 [send] 不再投递。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        main.post {
            waiting.clear()
            ready = false
            view.removeJavascriptInterface("LeQiNative")
            view.destroy()
        }
    }

    private companion object {
        const val TAG = "LeqiAgentWeb"
    }
}
