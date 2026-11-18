package com.leqi.experiment.phonel

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import android.view.accessibility.AccessibilityManager
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import org.json.JSONObject

/** 手机独立体验走与眼镜相同的云服务。只有用户点击识别按钮后才上传当前预览。 */
class PictureActivity : Activity() {
    private val work = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var model: DeepSeekClient? = null
    @Volatile private var tools: DeepSeekToolClient? = null
    private lateinit var image: ImageView
    private lateinit var identify: Button
    private lateinit var camera: Button
    private lateinit var autoRead: CheckBox
    private var pendingCamera: Uri? = null
    @Volatile private var stopAudio = false
    private var speaking = false
    private var visible = false
    private lateinit var select: Button
    private lateinit var speak: Button
    private lateinit var output: TextView
    private lateinit var caption: TextView
    private lateinit var bitmap: Bitmap
    private var answer = ""
    private var busy = false
    private var reading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reading = intent.getBooleanExtra("read", false)
        pendingCamera = savedInstanceState?.getString("camera")?.let(Uri::parse)
        val body = column().apply { pad(20, 16) }
        val back = action("返回首页", Color.WHITE, forest) { finish() }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(back) }
        body.item(header)
        body.item(heading(if (reading) "阅读文字" else "看图描述", 30), 20)
        body.item(label("确认后将这张图发送给 DeepSeek。", 15, muted), 7)
        val card = column().apply { pad(14, 14); background = shape(Color.WHITE, 18) }
        caption = label("示例图片 · 图书馆公告", 16, forest, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        card.item(caption)
        bitmap = sample()
        image = ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        card.addView(image, LinearLayout.LayoutParams(-1, dp(168)).apply { topMargin = dp(10) })
        camera = action("用手机拍照", paper, forest, ::takePhoto)
        card.item(camera, 10)
        select = action("从手机选择图片", paper, forest) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 41)
        }
        card.item(select, 10); body.item(card, 18)
        identify = action(if (reading) "识别图片文字" else "描述这张图片", forest, Color.WHITE, ::recognize)
        val prefs = getSharedPreferences("accessibility", MODE_PRIVATE)
        autoRead = CheckBox(this).apply {
            text = "识别后自动朗读"; textSize = 17f; setTextColor(ink); minHeight = dp(48)
            isChecked = prefs.getBoolean("autoRead", true)
            setOnCheckedChangeListener { _, enabled -> prefs.edit().putBoolean("autoRead", enabled).apply() }
        }
        body.item(autoRead, 8)
        body.item(identify, 6)
        output = label("", 18).apply { setTextIsSelectable(true); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        speak = action("朗读结果", lime, forest) {
            if (speaking) stopPlayback() else readAloud()
        }.apply { visibility = View.GONE }
        body.item(speak, 12)
        body.item(output, 18)
        page(body)
    }

    private fun sample(): Bitmap {
        val b = Bitmap.createBitmap(960, 460, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.rgb(241, 239, 221))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = forest }
        c.drawRect(0f, 0f, 22f, 460f, p)
        p.textSize = 66f; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText("图书馆开放时间", 70f, 130f, p)
        c.drawRect(70f, 163f, 880f, 167f, p)
        p.typeface = Typeface.DEFAULT; p.textSize = 43f
        c.drawText("周一至周五   08:30 – 21:00", 70f, 258f, p)
        c.drawText("周六、周日   09:00 – 17:00", 70f, 347f, p)
        return b
    }

    private fun takePhoto() {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "leqi-" + java.util.UUID.randomUUID() + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_EXPIRES, System.currentTimeMillis() / 1000 + 3600)
        }
        try {
            val uri = checkNotNull(contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            pendingCamera = uri
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION), 42)
        } catch (_: Exception) {
            discardCamera(); output.text = "无法打开相机，请从手机选择图片。"
        }
    }

    private fun discardCamera() {
        pendingCamera?.let { uri -> runCatching { contentResolver.delete(uri, null, null) } }
        pendingCamera = null
    }
    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state); state.putString("camera", pendingCamera?.toString())
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in 41..42) return
        if (resultCode != RESULT_OK) { if (requestCode == 42) discardCamera(); return }
        val uri = (if (requestCode == 42) pendingCamera else data?.data) ?: return
        runJob("正在读取图片…", {
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                    val size = info.size
                    val ratio = minOf(1.0, 1280.0 / maxOf(size.width, size.height))
                    decoder.setTargetSize(maxOf(1, (size.width * ratio).toInt()), maxOf(1, (size.height * ratio).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } finally { if (requestCode == 42) runCatching { contentResolver.delete(uri, null, null) } }
        }) {
            if (requestCode == 42) pendingCamera = null
            bitmap = it; image.setImageBitmap(it); caption.text = if (requestCode == 42) "已拍摄，点击下方按钮识别" else "已选图片，点击下方按钮识别"
            answer = ""; output.text = ""; speak.visibility = View.GONE
        }
    }

    private fun recognize() {
        val selected = bitmap
        answer = ""; speak.visibility = View.GONE
        output.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        runJob(if (reading) "正在提取图片中的文字…" else "正在观察图片…", {
            val settings = AgentModelSettings(this)
            val key = settings.apiKey()
            val jpeg = ByteArrayOutputStream().use { out ->
                check(selected.compress(Bitmap.CompressFormat.JPEG, 88, out)); out.toByteArray()
            }
            if (reading) {
                val service = DeepSeekToolClient(key).also { tools = it }
                val result = service.recognize(jpeg) { closed }
                val pages = result.getJSONArray("pages")
                settings.recordOcr(result.optInt("page_count", pages.length()))
                (0 until pages.length()).map { pages.getJSONObject(it) }.sortedBy { it.optInt("index") }
                    .joinToString("\n") { it.optString("text") }
                    .replace(Regex("(?m)^#{1,6} +"), "")
                    .replace(Regex("<｜box｜>[\\s\\S]*?<｜/box｜>"), "").replace(Regex("<｜/?ref｜>"), "").trim()
            } else {
                val service = DeepSeekClient(key, settings.model, usage = settings::recordUsage).also { model = it }
                JSONObject(service.observe("用中文简短描述这张图片中可见的主体和位置。输出 JSON，字段为 summary。", jpeg) { closed })
                    .getString("summary").trim()
            }
        }) {
            answer = it
            output.accessibilityLiveRegion = if (autoRead.isChecked) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
            output.text = it.ifBlank { if (reading) "没有识别到清晰的文字，请换一张图片。" else "未能描述这张图片，请重试。" }
            speak.visibility = if (it.isBlank()) View.GONE else View.VISIBLE
            val screenReader = getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled
            if (autoRead.isChecked && it.isNotBlank() && visible && !screenReader) readAloud()
        }
    }

    private fun readAloud() {
        val text = answer
        speaking = true; stopAudio = false
        output.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        speak.text = "停止朗读"
        runJob("正在朗读", {
            val settings = AgentModelSettings(this)
            val service = DeepSeekToolClient(settings.apiKey()).also { tools = it }
            // 长文本分段合成，按原顺序播放。
            for (part in text.chunked(240)) {
                if (closed || stopAudio) break
                val audio = service.synthesize(part) { closed || stopAudio }
                settings.recordTts(part.length)
                if (closed || stopAudio) break
                play(audio)
            }
        }) { output.text = text }
    }

    private fun play(audio: SpeechAudio) {
        val pcm = ShortArray(audio.samples.size) { (audio.samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        try {
            check(track.write(pcm, 0, pcm.size) == pcm.size)
            if (closed || stopAudio) return
            track.play()
            val deadline = android.os.SystemClock.elapsedRealtime() + pcm.size * 1000L / audio.sampleRate + 3000
            while (!closed && !stopAudio && track.playbackHeadPosition < pcm.size) {
                check(android.os.SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(45)
            }
        } finally { track.release() }
    }

    private fun stopPlayback() {
        stopAudio = true; tools?.cancel()
    }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; if (speaking) stopPlayback(); super.onStop() }

    private fun <T> runJob(message: String, job: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true; select.isEnabled = false; camera.isEnabled = false; identify.isEnabled = false; speak.isEnabled = speaking
        output.text = if (answer.isEmpty()) message else "$answer\n\n$message"
        work.execute {
            val result = runCatching(job)
            runOnUiThread {
                if (!closed) {
                    busy = false; select.isEnabled = true; camera.isEnabled = true; identify.isEnabled = true; speak.isEnabled = true
                    val stopped = speaking && stopAudio
                    speaking = false; speak.text = "朗读结果"
                    if (stopped) { output.text = answer; return@runOnUiThread }
                    result.fold(done) { error ->
                        output.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                        val reason = when ((error as? ModelServiceException)?.code) {
                            "cloud_auth", "cloud_key_missing" -> "请在首页设置中检查 DeepSeek 密钥。"
                            "cloud_balance" -> "请检查 DeepSeek 账户余额或配额。"
                            "cloud_busy" -> "云服务繁忙，请稍后重试。"
                            "cloud_timeout" -> "云服务响应超时，请重试。"
                            else -> "未能完成，请检查网络或更换图片后重试。"
                        }
                        output.text = listOf(answer, reason).filter { it.isNotBlank() }.joinToString("\n\n")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) discardCamera()
        closed = true; model?.cancel(); tools?.cancel(); work.shutdownNow()
        super.onDestroy()
    }
}
