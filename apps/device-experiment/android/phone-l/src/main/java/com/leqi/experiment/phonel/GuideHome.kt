package com.leqi.experiment.phonel

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*

internal val ink = Color.rgb(24, 49, 43)
internal val muted = Color.rgb(70, 97, 86)
internal val paper = Color.rgb(246, 247, 241)
internal val forest = Color.rgb(17, 65, 55)
internal val lime = Color.rgb(220, 244, 152)
internal fun Context.dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
internal fun Context.shape(color: Int, radius: Int) = GradientDrawable().apply {
    setColor(color); cornerRadius = dp(radius).toFloat()
}
internal fun Context.label(value: String, size: Int, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
    text = value; textSize = size.toFloat(); setTextColor(color)
    if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    includeFontPadding = false
}
internal fun Context.heading(title: String, size: Int, color: Int = ink) =
    label(title, size, color, true).apply { isAccessibilityHeading = true }
internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun View.pad(x: Int, y: Int) = setPadding(context.dp(x), context.dp(y), context.dp(x), context.dp(y))
internal fun LinearLayout.item(view: View, top: Int = 0) = addView(view,
    LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(top) })
internal fun Context.action(title: String, color: Int, fg: Int, click: () -> Unit) = Button(this).apply {
    text = title; textSize = 22f; isAllCaps = false; setTextColor(fg)
    minHeight = dp(76); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    letterSpacing = 0f; stateListAnimator = null; pad(16, 12)
    val enabled = android.R.attr.state_enabled
    setTextColor(ColorStateList(arrayOf(intArrayOf(-enabled), intArrayOf()), intArrayOf(muted, fg)))
    val surfaces = StateListDrawable().apply {
        addState(intArrayOf(-enabled), shape(Color.rgb(222, 227, 220), 14))
        addState(intArrayOf(android.R.attr.state_focused), shape(color, 14).apply { setStroke(dp(4), fg) })
        addState(intArrayOf(), shape(color, 14))
    }
    background = RippleDrawable(ColorStateList.valueOf(0x33000000), surfaces, null)
    setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); click() }
}
internal fun Activity.page(body: LinearLayout) {
    val scroll = ScrollView(this).apply { setBackgroundColor(paper); isFillViewport = true; addView(body) }
    window.setDecorFitsSystemWindows(false)
    val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
    scroll.setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
    }
    setContentView(scroll)
    window.insetsController?.setSystemBarsAppearance(lightBars, lightBars)
}

// 会话状态仅存在于本进程，界面离开时移除监听，断开连接后清除字幕。
internal data class GuideState(val active: Boolean = false, val ready: Boolean = false,
    val phase: String = "", val question: String = "", val answer: String = "")
internal object GuideSession {
    var state = GuideState()
        private set
    private val listeners = mutableSetOf<(GuideState) -> Unit>()
    fun watch(listener: (GuideState) -> Unit) { listeners.add(listener); listener(state) }
    fun remove(listener: (GuideState) -> Unit) { listeners.remove(listener) }
    fun show(next: GuideState) { state = next; listeners.toList().forEach { it(next) } }
}

internal class GuideHome(private val host: Activity, connect: () -> Unit, stop: () -> Unit,
    settings: () -> Unit, picture: (Boolean) -> Unit, nearby: () -> Unit) {
    val feedback = host.label("", 18, muted)
    private val hint = host.label("", 19, ink).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val connection = host.action("连接眼镜", forest, Color.WHITE) {
        if (GuideSession.state.active) stop() else connect()
    }
    init {
        val body = host.column().apply { pad(16, 14) }
        body.item(host.heading("乐奇助盲", 28))
        // 常用动作排在状态之前。连接、录音和播报过程中，按钮保持原来的位置。
        body.item(connection, 12)
        body.item(host.action("读文字", forest, Color.WHITE) { picture(true) }, 10)
        body.item(host.action("听图片描述", forest, Color.WHITE) { picture(false) }, 10)
        body.item(host.action("查附近地点", forest, Color.WHITE, nearby), 10)
        body.item(host.action("设置与使用帮助", Color.WHITE, ink, settings), 10)
        body.item(hint, 16)
        feedback.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        body.item(feedback, 8)
        host.page(body)
    }
    fun render(s: GuideState) {
        val title = if (s.active) "停止任务与连接" else "连接眼镜"
        if (connection.text.toString() != title) connection.text = title
        val state = when { s.ready -> "眼镜已就绪"; s.active -> "正在连接眼镜"; else -> "眼镜未连接" }
        if (connection.stateDescription != state) connection.stateDescription = state
        val detail = if (s.phase.isNotBlank()) "。${s.phase}" else ""
        val message = state + detail
        if (hint.text.toString() != message) hint.text = message
    }
}
