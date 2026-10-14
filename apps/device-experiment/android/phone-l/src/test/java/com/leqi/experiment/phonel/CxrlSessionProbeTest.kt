package com.leqi.experiment.phonel

import android.app.Activity
import android.graphics.Bitmap
import android.os.Looper
import com.rokid.cxr.session.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CxrlSessionProbeTest {
    private class DirectExecutor : AbstractExecutorService() {
        private var stopped = false
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
        override fun execute(command: Runnable) { check(!stopped); command.run() }
    }
    private class QueuedExecutor : AbstractExecutorService() {
        val work = ArrayDeque<Runnable>()
        private var stopped = false
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return work.toMutableList().also { work.clear() } }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && work.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        override fun execute(command: Runnable) { check(!stopped); work.addLast(command) }
        fun drain() { while (work.isNotEmpty()) work.removeFirst().run() }
    }
    private class Fixture(sdkExecutor: ExecutorService = DirectExecutor()) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val lines = mutableListOf<String>()
        val updates = mutableListOf<String>()
        var captures = 0
        var closes = 0
        var lastLifecycle: ISessionLifecycleCbk? = null
        var lastImages: IImageCallback? = null
        var lastEvents: IGlassesEventListener? = null
        var displayCode = SessionErrorCode.OK
        private val auth = AuthResult(true, "unit-test-token-never-logged", SessionErrorCode.OK, null)
        private fun sdkSession(): CxrSession = Proxy.newProxyInstance(CxrSession::class.java.classLoader,
            arrayOf(CxrSession::class.java)) { _, method, args ->
            when (method.name) {
                "addLifecycleCallback" -> { lastLifecycle = args!![0] as ISessionLifecycleCbk; null }
                "addImageCallback" -> { lastImages = args!![0] as IImageCallback; null }
                "addGlassesEventListener" -> { lastEvents = args!![0] as IGlassesEventListener; null }
                "removeLifecycleCallback", "removeImageCallback", "removeGlassesEventListener" -> null
                "connect" -> { lastLifecycle!!.onConnectResult(true, null); lastLifecycle!!.onSessionStarted(); null }
                "close" -> { closes++; null }
                "takePhoto" -> { captures++; SessionResult(SessionErrorCode.OK, Unit, null) }
                "customViewUpdate" -> { updates.add(args!![0] as String); SessionResult(displayCode, Unit, null) }
                else -> error("Unexpected SDK call: ${method.name}")
            }
        } as CxrSession
        private val manager = Proxy.newProxyInstance(CxrSessionManager::class.java.classLoader,
            arrayOf(CxrSessionManager::class.java)) { _, method, args ->
            when (method.name) {
                "isRokidAppInstalled", "isGlassesBtConnected" -> true
                "checkRokidAppCompatibility" -> RokidAppStatus.Compatible("1.11.0")
                "parseAuthorizationResult" -> auth
                "requestAuthorization" -> {
                    @Suppress("UNCHECKED_CAST")
                    (args!![2] as (AuthResult) -> Unit)(auth)
                    null
                }
                "create" -> sdkSession()
                else -> error("Unexpected manager call: ${method.name}")
            }
        } as CxrSessionManager
        val probe = CxrlSessionProbe(activity, manager, { lines.add(it) }, { }, DirectExecutor(), sdkExecutor)
        fun connect() {
            probe.authorize()
            probe.onActivityResult(Activity.RESULT_OK, null)
            idle()
            probe.connect()
            idle()
        }
        fun capture(): String {
            val request = probe.armPhoto()!!
            probe.confirmPhoto(request.id)
            return request.id
        }
        fun deliver(data: ByteArray) { lastImages!!.onImageReceived(data); idle() }
    }
    companion object {
        private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
        private fun jpeg(): ByteArray {
            val image = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
            return ByteArrayOutputStream().use {
                image.compress(Bitmap.CompressFormat.JPEG, 80, it)
                image.recycle()
                it.toByteArray()
            }
        }
    }
    @Test fun validImageCompletesMockAndRequiresSeparateHumanDisplayConfirmation() {
        val fixture = Fixture()
        fixture.connect()
        val id = fixture.capture()
        fixture.deliver(jpeg())
        assertEquals(1, fixture.updates.size)
        assertTrue(fixture.updates.single().contains("Mock："))
        assertTrue(fixture.updates.single().contains(id.take(8)))
        assertTrue(fixture.lines.none { it.contains("人工确认看见") })
        fixture.probe.confirmVisible()
        assertTrue(fixture.lines.any { it.contains("人工确认看见") })
        assertTrue(fixture.lines.none { it.contains("unit-test-token") })
        fixture.probe.destroy()
    }
    @Test fun corruptOrMissingImageNeverRunsMock() {
        val fixture = Fixture()
        fixture.connect(); fixture.capture()
        fixture.deliver(byteArrayOf(1, 2, 3))
        assertTrue(fixture.updates.isEmpty())
        assertEquals(1, fixture.closes)
        fixture.probe.destroy()
    }
    @Test fun cancelledOldSessionImageCannotEnterFreshCapture() {
        val fixture = Fixture()
        fixture.connect(); fixture.capture()
        val oldImages = fixture.lastImages!!
        fixture.probe.cancelPhoto()
        fixture.connect(); fixture.capture()
        oldImages.onImageReceived(jpeg()); idle()
        assertTrue(fixture.updates.isEmpty())
        fixture.deliver(jpeg())
        assertEquals(1, fixture.updates.size)
        fixture.probe.destroy()
    }
    @Test fun timeoutRequiresFreshAuthorizationAndRejectsLateImage() {
        val fixture = Fixture()
        fixture.connect(); fixture.capture()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        fixture.deliver(jpeg())
        assertTrue(fixture.updates.isEmpty())
        assertNull(fixture.probe.armPhoto())
        assertEquals(1, fixture.closes)
        fixture.probe.destroy()
    }
    @Test fun duplicateImagesAndRejectedDisplayDoNotProduceVisibleSuccess() {
        val fixture = Fixture()
        fixture.displayCode = SessionErrorCode.LINK_LOST
        fixture.connect(); fixture.capture()
        fixture.deliver(jpeg()); fixture.deliver(jpeg())
        fixture.probe.confirmVisible()
        assertEquals(1, fixture.updates.size)
        assertTrue(fixture.lines.none { it.contains("人工确认看见") })
        assertEquals(1, fixture.closes)
        fixture.probe.destroy()
    }
    @Test fun backgroundAndGlassesScreenOffCloseSessionAndRejectPendingResults() {
        for (screenOff in listOf(false, true)) {
            val fixture = Fixture()
            fixture.connect(); fixture.capture()
            if (screenOff) { fixture.lastEvents!!.onScreenOff(); idle() }
            else fixture.probe.hostBackground()
            fixture.deliver(jpeg())
            assertTrue(fixture.updates.isEmpty())
            assertNull(fixture.probe.armPhoto())
            assertEquals(1, fixture.closes)
            fixture.probe.destroy()
        }
    }
    @Test fun pauseDuringCaptureCannotResumeOldConsentAndManualRecoveryWorks() {
        val fixture = Fixture()
        fixture.connect(); fixture.capture()
        val old = fixture.lastLifecycle!!
        old.onSessionPaused(PausedReason.BT_DISCONNECTED); idle()
        old.onSessionResumed(); idle()
        assertNull(fixture.probe.armPhoto())
        fixture.probe.manualRecover()
        fixture.probe.onActivityResult(Activity.RESULT_OK, null); idle()
        fixture.probe.connect(); idle()
        fixture.capture(); fixture.deliver(jpeg())
        assertEquals(1, fixture.updates.size)
        fixture.probe.destroy()
    }
    @Test fun queuedDisplayCannotSendAfterUiTimeoutAndPrematureVisibleConfirmationIsRejected() {
        val executor = QueuedExecutor()
        val fixture = Fixture(executor)
        fixture.connect(); executor.drain(); idle(); executor.drain(); idle()
        fixture.capture(); executor.drain(); idle()
        fixture.deliver(jpeg()) // SDK display work remains queued.
        fixture.probe.confirmVisible()
        assertTrue(fixture.lines.none { it.contains("人工确认看见") })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        executor.drain(); idle()
        assertTrue(fixture.updates.isEmpty())
        assertEquals(1, fixture.closes)
        assertTrue(fixture.lines.any { it.contains("显示请求超时") })
        fixture.probe.destroy(); executor.drain()
    }
}
