package com.leqi.experiment.phonel

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
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
    text = title; textSize = 16f; isAllCaps = false; setTextColor(fg); minHeight = dp(48)
    letterSpacing = 0f; stateListAnimator = null; pad(14, 8)
    background = RippleDrawable(ColorStateList.valueOf(0x22000000), shape(color, 14), null)
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
    val feedback = host.label("", 15, muted)
    private val badge = host.label("", 14, lime, true)
    private val hint = host.label("", 16, Color.WHITE)
    private val transcript = host.label("", 16)
    private val captions = host.column()
    private val connection = host.action("连接眼镜", lime, forest) {
        if (GuideSession.state.active) stop() else connect()
    }
    init {
        val body = host.column().apply { pad(20, 16) }
        val bar = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(host.heading("乐奇助盲", 28), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(host.action("设置", Color.WHITE, forest, settings))
        body.item(bar)
        val hero = host.column().apply { background = host.shape(forest, 24); pad(22, 20) }
        val head = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(badge, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ImageView(host).apply { setImageResource(R.drawable.guide_glasses); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(host.dp(70), host.dp(37)))
        hero.item(head)
        hero.item(host.heading("眼镜助手", 32, Color.WHITE), 4)
        hint.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        hero.item(hint, 9); hero.item(connection, 16)
        body.item(hero, 16)
        feedback.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        body.item(feedback, 5)
        captions.apply { background = host.shape(Color.WHITE, 18); pad(16, 14)
            item(host.heading("本轮对话", 17, forest)); item(transcript, 8) }
        body.item(captions, 6)
        body.item(host.heading("手机体验", 21), 16)
        fun row(title: String, detail: String, icon: Int, click: () -> Unit) {
            val row = object : LinearLayout(host) {
                override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
            }.apply {
                gravity = Gravity.CENTER_VERTICAL; pad(15, 14); isFocusable = true; isScreenReaderFocusable = true
                background = RippleDrawable(ColorStateList.valueOf(0x15000000), host.shape(Color.WHITE, 17), null)
                setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); click() }; contentDescription = "$title，$detail"
            }
            row.addView(ImageView(host).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageResource(icon); setColorFilter(forest); background = host.shape(0xffe9f0df.toInt(), 13); pad(9, 9)
            }, LinearLayout.LayoutParams(host.dp(43), host.dp(43)))
            val words = host.column().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS; item(host.label(title, 18, ink, true)); item(host.label(detail, 14, muted), 4) }
            row.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = host.dp(13) })
            row.addView(host.label("›", 25, muted).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            body.item(row, 9)
        }
        row("看图描述", "听听照片里有什么", R.drawable.guide_scene) { picture(false) }
        row("阅读文字", "提取并朗读图片中的文字", R.drawable.guide_read) { picture(true) }
        row("附近地点", "搜索目的地，查看步行路线", R.drawable.guide_pin, nearby)
        host.page(body)
    }
    fun render(s: GuideState) {
        badge.text = when { s.ready -> "●  眼镜已就绪"; s.active -> "◌  正在准备"; else -> "○  眼镜未连接" }
        badge.contentDescription = badge.text.toString().substringAfter("  ")
        val nextHint = if (s.phase.isNotBlank()) s.phase else "连接 Rokid 眼镜，用语音问答、看图或导航。"
        if (hint.text.toString() != nextHint) hint.text = nextHint
        connection.text = if (s.active) "停止任务与连接" else "连接眼镜"
        captions.visibility = if (s.active) View.VISIBLE else View.GONE
        transcript.text = listOfNotNull(s.question.takeIf { it.isNotBlank() }?.let { "你　$it" },
            s.answer.takeIf { it.isNotBlank() }?.let { "助手　$it" }).joinToString("\n\n").ifBlank { "开始说话后，这里会显示本轮提问和助手回复。" }
    }
}
