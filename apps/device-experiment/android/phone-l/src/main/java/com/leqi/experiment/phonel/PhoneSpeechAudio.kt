package com.leqi.experiment.phonel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Built-in phone mic, PCM16/16k/mono. No background service or persistent recording. */
internal class PhoneSpeechRecorder(private val context: Context) : SpeechRecorder {
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var generation = 0L
    @Volatile private var finishing = false
    private var record: AudioRecord? = null
    override fun start(result: (Result<FloatArray>) -> Unit) {
        cancel()
        val attempt = generation
        finishing = false
        WORKER.execute {
            val outcome = runCatching {
                check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                val created = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum, 8192)).build()
                try {
                    synchronized(lock) {
                        check(attempt == generation && !finishing)
                        record = created
                        check(created.state == AudioRecord.STATE_INITIALIZED)
                        val manager = context.getSystemService(AudioManager::class.java)
                        val phoneMic = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                            ?: error("Phone microphone unavailable")
                        check(created.setPreferredDevice(phoneMic))
                        created.startRecording()
                        check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    }
                    val samples = FloatArray(480000); var size = 0; val buffer = ShortArray(1024)
                    var phoneRouteObserved = false
                    while (generation == attempt && !finishing && size < samples.size) {
                        val count = created.read(buffer, 0, minOf(buffer.size, samples.size - size), AudioRecord.READ_BLOCKING)
                        if (count <= 0) { if (finishing || generation != attempt) break; error("AudioRecord read failed") }
                        created.routedDevice?.let { route ->
                            check(route.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) { "Unexpected input route" }
                            phoneRouteObserved = true
                        }
                        for (index in 0 until count) samples[size++] = buffer[index] / 32768f
                    }
                    check(generation == attempt)
                    require(size >= 3200) { "Recording too short" }
                    check(phoneRouteObserved) { "Input did not route to phone microphone" }
                    samples.copyOf(size)
                } finally {
                    synchronized(lock) { if (record === created) record = null }
                    runCatching { created.stop() }; created.release()
                }
            }
            handler.post { if (attempt == generation) result(outcome) }
        }
    }
    override fun finish() {
        finishing = true
        synchronized(lock) { runCatching { record?.stop() } }
    }
    override fun cancel() {
        ++generation; finishing = true
        synchronized(lock) { runCatching { record?.stop() } }
    }
    companion object { private val WORKER = Executors.newSingleThreadExecutor() }
}

/** Local PCM playback; system-media mode can route to glasses via Android Bluetooth. */
internal class PhoneSpeechSpeaker(context: Context, private val interrupted: () -> Unit) : SpeechSpeaker {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var generation = 0L
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    var usePhoneSpeaker = true
    override fun play(audio: SpeechAudio, result: (Result<String>) -> Unit) {
        stop()
        val attempt = generation
        val phone = usePhoneSpeaker
        WORKER.execute {
            val outcome = runCatching {
                audio.validate()
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                        if (change < 0 && attempt == generation) interrupted()
                    }, handler).build()
                val minimum = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                check(minimum > 0)
                val created = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(audio.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum, 8192)).build()
                try {
                    synchronized(lock) {
                        check(attempt == generation)
                        track = created; focus = request
                        check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                        if (phone) {
                            val output = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                                ?: error("Phone speaker unavailable")
                            check(created.setPreferredDevice(output))
                        }
                        created.play()
                    }
                    var offset = 0
                    while (offset < audio.samples.size) {
                        check(attempt == generation)
                        val count = created.write(audio.samples, offset, minOf(1024, audio.samples.size - offset), AudioTrack.WRITE_BLOCKING)
                        check(count > 0); offset += count
                    }
                    // A successful write means queued, not yet heard. Wait for the playback head.
                    val until = android.os.SystemClock.elapsedRealtime() + 5_000L
                    while (created.playbackHeadPosition.toLong() < audio.samples.size) {
                        check(attempt == generation && android.os.SystemClock.elapsedRealtime() < until)
                        Thread.sleep(10)
                    }
                    val route = created.routedDevice ?: error("Unknown playback route")
                    if (phone) check(route.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                    when (route.type) {
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手机扬声器"
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙媒体设备（需人工确认是否为眼镜）"
                        else -> "系统媒体设备，类型=${route.type}"
                    }
                } finally {
                    synchronized(lock) { if (track === created) { track = null; focus = null } }
                    runCatching { created.stop() }; created.release(); manager.abandonAudioFocusRequest(request)
                }
            }
            handler.post { if (attempt == generation) result(outcome) }
        }
    }
    override fun stop() {
        ++generation
        synchronized(lock) {
            runCatching { track?.pause(); track?.flush() }
            focus?.let { manager.abandonAudioFocusRequest(it) }
        }
    }
    companion object { private val WORKER = Executors.newSingleThreadExecutor() }
}
