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
    private class FakeObservation : PhotoObservation {
        var loaded = true
        var busy = false
        var closed = false
        var calls = 0
        var jpeg: ByteArray? = null
        var callback: ((Result<String>) -> Unit)? = null
        override val ready get() = loaded && !busy && !closed
        override fun observe(jpeg: ByteArray, result: (Result<String>) -> Unit) {
            check(ready); busy = true; calls++; this.jpeg = jpeg; callback = result
        }
        fun finish(result: Result<String>) { busy = false; callback!!.invoke(result) }
        override fun cancel() { }
        override fun close() { closed = true }
    }
    private class Fixture(sdkExecutor: ExecutorService = DirectExecutor(), observation: PhotoObservation? = null) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val lines = mutableListOf<String>()
        val updates = mutableListOf<String>()
        var captures = 0
        var closes = 0
        var creates = 0
        var closedIdle = true
        var closeThrows = false
        var lastLifecycle: ISessionLifecycleCbk? = null
        var lastImages: IImageCallback? = null
        var lastEvents: IGlassesEventListener? = null
        var displayCode = SessionErrorCode.OK
        var initialIdleEvents = false
        var directAuthorization = true
        var authRequests = 0
        var parseCalls = 0
        val authCallbacks = mutableListOf<(AuthResult) -> Unit>()
        val auth = AuthResult(true, "unit-test-token-never-logged", SessionErrorCode.OK, null)
        private fun sdkSession(): CxrSession = Proxy.newProxyInstance(CxrSession::class.java.classLoader,
            arrayOf(CxrSession::class.java)) { _, method, args ->
            when (method.name) {
                "addLifecycleCallback" -> { lastLifecycle = args!![0] as ISessionLifecycleCbk; null }
                "addImageCallback" -> { lastImages = args!![0] as IImageCallback; null }
                "addGlassesEventListener" -> { lastEvents = args!![0] as IGlassesEventListener; null }
                "removeLifecycleCallback", "removeImageCallback", "removeGlassesEventListener" -> null
                "connect" -> {
                    if (initialIdleEvents) {
                        lastEvents!!.onLauncherResumed()
                        lastEvents!!.onGlassesAppPaused()
                        lastEvents!!.onScreenOff()
                    }
                    lastLifecycle!!.onConnectResult(true, null); lastLifecycle!!.onSessionStarted(); null
                }
                "close" -> { closes++; if (closeThrows) error("close failed"); null }
                "getState" -> if (closedIdle) SessionState.Idle else SessionState.Terminating
                "takePhoto" -> { captures++; SessionResult(SessionErrorCode.OK, Unit, null) }
                "customViewUpdate" -> { updates.add(args!![0] as String); SessionResult(displayCode, Unit, null) }
                else -> error("Unexpected SDK call: ${method.name}")
            }
        } as CxrSession
        private val manager: CxrSessionManager = Proxy.newProxyInstance(CxrSessionManager::class.java.classLoader,
            arrayOf(CxrSessionManager::class.java)) { _, method, args ->
            when (method.name) {
                "isRokidAppInstalled", "isGlassesBtConnected" -> true
                "checkRokidAppCompatibility" -> RokidAppStatus.Compatible("1.11.0")
                "parseAuthorizationResult" -> { parseCalls++; authCallbacks.last().invoke(auth); auth }
                "requestAuthorization" -> {
                    authRequests++
                    @Suppress("UNCHECKED_CAST")
                    val callback = args!![2] as (AuthResult) -> Unit
                    authCallbacks.add(callback)
                    if (directAuthorization) callback(auth)
                    else probe.onAuthorizationActivityLaunched(1000 + authRequests)
                    null
                }
                "create" -> { creates++; sdkSession() }
                else -> error("Unexpected manager call: ${method.name}")
            }
        } as CxrSessionManager
        val probe: CxrlSessionProbe = CxrlSessionProbe(activity, manager, { lines.add(it) }, { }, DirectExecutor(), sdkExecutor, observation = observation)
        fun connect() {
            probe.authorize()
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
        val run = fixture.probe.exportRecords().getJSONArray("runs").getJSONObject(0)
        assertEquals(id, run.getString("request_id"))
        assertEquals("human_confirmed", run.getString("outcome"))
        assertTrue(run.getJSONObject("timestamps_ms").has("human_visible"))
        assertEquals("image/jpeg", run.getJSONObject("image").getString("mime"))
        assertTrue(fixture.lines.none { it.contains("unit-test-token") })
        fixture.probe.destroy()
    }
    @Test fun corruptOrMissingImageNeverRunsMock() {
        val fixture = Fixture()
        fixture.connect(); fixture.capture()
        fixture.deliver(byteArrayOf(1, 2, 3))
        assertTrue(fixture.updates.isEmpty())
        assertEquals(1, fixture.closes)
        assertEquals("decode", fixture.probe.exportRecords().getJSONArray("runs")
            .getJSONObject(0).getString("failure_stage"))
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
        idle()
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
    @Test fun initialIdleLauncherAndScreenEventsDoNotAbortSessionStartup() {
        val fixture = Fixture()
        fixture.initialIdleEvents = true
        fixture.connect(); fixture.capture(); fixture.deliver(jpeg())
        assertEquals(0, fixture.closes)
        assertEquals(1, fixture.updates.size)
        fixture.probe.destroy()
    }
    @Test fun cachedGrantWithoutAnyActivityResultCanRecoverAndCaptureAgain() {
        val fixture = Fixture()
        fixture.connect()
        fixture.probe.manualRecover(); idle()
        fixture.probe.connect(); idle()
        fixture.capture(); fixture.deliver(jpeg())
        assertEquals(2, fixture.authRequests)
        assertEquals(0, fixture.parseCalls)
        assertEquals(1, fixture.updates.size)
        assertTrue(fixture.lines.none { it.contains("等待上一次") })
        fixture.probe.destroy()
    }
    @Test fun callbackCancelledBeforeDeliveryCannotBlockRetryOrReviveOldAuthorization() {
        val fixture = Fixture()
        fixture.probe.authorize() // Its cached result is queued on the main looper.
        fixture.probe.stop()
        fixture.probe.authorize()
        idle()
        fixture.probe.connect(); idle()
        assertEquals(2, fixture.authRequests)
        assertNotNull(fixture.probe.armPhoto())
        assertEquals(1, fixture.lines.count { it.contains("用户授权成功") })
        fixture.probe.destroy()
    }
    @Test fun interactiveAuthorizationUsesActualRequestCodeAndIgnoresUnrelatedResults() {
        val fixture = Fixture()
        fixture.directAuthorization = false
        fixture.probe.authorize()
        fixture.probe.onActivityResult(1234, Activity.RESULT_OK, null); idle()
        assertEquals(0, fixture.parseCalls)
        fixture.probe.onActivityResult(1001, Activity.RESULT_OK, null); idle()
        fixture.probe.connect(); idle()
        assertNotNull(fixture.probe.armPhoto())
        assertEquals(1, fixture.parseCalls)
        assertEquals(1, fixture.lines.count { it.contains("用户授权成功") })
        fixture.probe.destroy()
    }
    @Test fun timedOutInteractiveGrantCannotBeMappedToANewerAuthorization() {
        val fixture = Fixture()
        fixture.directAuthorization = false
        fixture.probe.authorize()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        fixture.probe.manualRecover()
        assertEquals(1, fixture.authRequests) // Old external UI must return first.
        fixture.probe.onActivityResult(1001, Activity.RESULT_OK, null); idle()
        assertEquals(0, fixture.parseCalls)
        fixture.probe.manualRecover()
        fixture.authCallbacks.first().invoke(fixture.auth)
        fixture.probe.onActivityResult(1001, Activity.RESULT_OK, null); idle()
        assertEquals(0, fixture.parseCalls)
        fixture.probe.onActivityResult(1002, Activity.RESULT_OK, null); idle()
        fixture.probe.connect(); idle()
        assertEquals(2, fixture.authRequests)
        assertNotNull(fixture.probe.armPhoto())
        fixture.probe.destroy()
    }
    @Test fun nextPhotoRecyclesSessionAndRequiresFreshConsent() {
        val fixture = Fixture()
        fixture.connect(); val first = fixture.capture(); fixture.deliver(jpeg())
        val oldImages = fixture.lastImages!!
        var next: PhotoGate.Request? = null
        fixture.probe.preparePhoto { next = it }; idle()
        assertEquals(1, fixture.closes)
        assertEquals(2, fixture.creates)
        assertEquals(2, fixture.authRequests)
        assertNotNull(next)
        assertNotEquals(first, next!!.id)
        assertEquals(1, fixture.captures) // Reconnection never implies camera consent.
        oldImages.onImageReceived(jpeg()); idle()
        assertEquals(1, fixture.updates.size)
        fixture.probe.confirmPhoto(first)
        assertEquals(1, fixture.captures)
        fixture.probe.confirmPhoto(next!!.id); fixture.deliver(jpeg())
        assertEquals(2, fixture.captures)
        assertEquals(2, fixture.updates.size)
        assertTrue(fixture.updates.last().contains(next!!.id.take(8)))
        fixture.probe.destroy()
    }
    @Test fun asynchronousCloseMustReachIdleBeforeAuthorizationAndCreate() {
        val fixture = Fixture()
        fixture.connect(); fixture.capture(); fixture.deliver(jpeg())
        fixture.closedIdle = false
        var next: PhotoGate.Request? = null
        fixture.probe.preparePhoto { next = it }; idle()
        fixture.probe.preparePhoto { error("Duplicate click must not replace pending consent") }
        assertEquals(1, fixture.closes)
        assertEquals(1, fixture.creates)
        assertEquals(1, fixture.authRequests)
        assertNull(next)
        fixture.closedIdle = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
        assertEquals(2, fixture.creates)
        assertNotNull(next)
        assertEquals(1, fixture.captures)
        fixture.probe.destroy()
    }
    @Test fun cancelledOrBackgroundedRecycleCannotRearmAfterIdle() {
        for (background in listOf(false, true)) {
            val fixture = Fixture()
            fixture.connect(); fixture.capture(); fixture.deliver(jpeg())
            fixture.closedIdle = false
            fixture.probe.preparePhoto { error("Cancelled preparation cannot show confirmation") }; idle()
            if (background) fixture.probe.hostBackground() else fixture.probe.cancelPhoto()
            fixture.closedIdle = true
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101))
            assertEquals(1, fixture.creates)
            assertEquals(1, fixture.authRequests)
            assertEquals(1, fixture.captures)
            fixture.probe.destroy()
        }
    }
    @Test fun closeFailureOrTimeoutDoesNotCreateAnotherSession() {
        val fixture = Fixture()
        fixture.connect(); fixture.capture(); fixture.deliver(jpeg())
        fixture.closedIdle = false; fixture.closeThrows = true
        fixture.probe.preparePhoto { error("Failed cleanup cannot show confirmation") }; idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertEquals(1, fixture.creates)
        assertEquals(1, fixture.authRequests)
        assertTrue(fixture.lines.any { it.contains("回收超时") })
        fixture.closedIdle = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, fixture.creates)
        fixture.probe.manualRecover(); idle(); fixture.probe.connect(); idle()
        assertEquals(2, fixture.creates)
        assertEquals(1, fixture.captures)
        fixture.probe.destroy()
    }
    @Test fun nextPhotoCannotInterruptCaptureOrPendingDisplay() {
        val fixture = Fixture()
        fixture.connect(); fixture.capture()
        fixture.probe.preparePhoto { error("Active capture cannot be recycled") }; idle()
        assertEquals(0, fixture.closes)
        assertEquals(1, fixture.creates)
        fixture.probe.destroy()
    }
    @Test fun queuedCleanupCompletesBeforeNewSessionAndCameraRemainsUncalled() {
        val executor = QueuedExecutor()
        val fixture = Fixture(executor)
        fixture.connect(); executor.drain(); idle(); executor.drain(); idle()
        fixture.capture(); executor.drain(); idle(); fixture.deliver(jpeg())
        executor.drain(); idle()
        var next: PhotoGate.Request? = null
        fixture.probe.preparePhoto { next = it }; idle()
        assertEquals(0, fixture.closes)
        assertEquals(1, fixture.creates)
        executor.drain(); idle(); executor.drain(); idle(); executor.drain(); idle()
        assertEquals(1, fixture.closes)
        assertEquals(2, fixture.creates)
        assertNotNull(next)
        assertEquals(1, fixture.captures)
        fixture.probe.destroy(); executor.drain()
    }
    @Test fun recycleCanCompleteInteractiveAuthorizationWithoutLosingFreshConsent() {
        val fixture = Fixture()
        fixture.connect(); fixture.capture(); fixture.deliver(jpeg())
        fixture.directAuthorization = false
        var next: PhotoGate.Request? = null
        fixture.probe.preparePhoto { next = it }; idle()
        fixture.probe.hostBackground() // Official authorization Activity is in front.
        assertEquals(1, fixture.creates)
        assertNull(next)
        fixture.probe.onActivityResult(1002, Activity.RESULT_OK, null); idle()
        assertEquals(2, fixture.creates)
        assertNotNull(next)
        assertEquals(1, fixture.captures)
        fixture.probe.cancelPhoto()
        var replacement: PhotoGate.Request? = null
        fixture.probe.preparePhoto { replacement = it }
        assertNotNull(replacement)
        assertEquals(2, fixture.creates) // Cancelled unconfirmed request can use this fresh scene.
        fixture.probe.destroy()
    }
    @Test fun gemmaUsesDecodedImageAndExportsInferenceWithoutMockTimings() {
        val model = FakeObservation()
        val fixture = Fixture(observation = model)
        fixture.connect(); val id = fixture.capture(); fixture.deliver(jpeg())
        assertEquals(1, model.calls)
        assertTrue(PhotoDecoder.completeContainer(model.jpeg!!))
        assertTrue(fixture.updates.isEmpty())
        model.finish(Result.success("桌上有杯子")); idle()
        assertEquals(1, fixture.updates.size)
        assertTrue(fixture.updates.single().contains("Gemma：桌上有杯子 #${id.take(8)}"))
        assertTrue(fixture.lines.none { it.contains("Mock 开始") })
        fixture.probe.confirmVisible()
        val records = fixture.probe.exportRecords()
        assertEquals("gemma", records.getString("mode"))
        val run = records.getJSONArray("runs").getJSONObject(0)
        assertEquals("human_confirmed", run.getString("outcome"))
        assertTrue(run.getJSONObject("timestamps_ms").has("inference_start"))
        assertFalse(run.getJSONObject("timestamps_ms").has("mock_start"))
        fixture.probe.destroy(); assertTrue(model.closed)
    }
    @Test fun visionFailureExportsSafeCodeWithoutNativeMessageOrFalseDisplay() {
        val model = FakeObservation()
        val fixture = Fixture(observation = model)
        fixture.connect(); fixture.capture(); fixture.deliver(jpeg())
        model.finish(Result.failure(com.leqi.experiment.phonebt.GemmaBridgeException(
            "private image prompt and native details", null, "vision_generation")))
        idle()
        val exported = fixture.probe.exportRecords()
        val run = exported.getJSONArray("runs").getJSONObject(0)
        assertEquals("inference", run.getString("failure_stage"))
        assertEquals("vision_generation", run.getString("failure_code"))
        assertFalse(exported.toString().contains("private image"))
        assertTrue(fixture.lines.none { it.contains("private image") })
        assertTrue(fixture.lines.any { it.contains("vision_generation") })
        assertTrue(fixture.updates.isEmpty())
        fixture.probe.destroy()
    }
    @Test fun lateGemmaResultAfterBackgroundIsNotDisplayedOrReused() {
        val model = FakeObservation()
        val fixture = Fixture(observation = model)
        fixture.connect(); fixture.capture(); fixture.deliver(jpeg())
        fixture.probe.hostBackground()
        model.finish(Result.success("过期画面")); idle()
        assertTrue(fixture.updates.isEmpty())
        assertEquals(1, fixture.closes)
        fixture.probe.destroy()
    }
    @Test fun modelNotReadyNeverArmsCameraOrFallsBackToMock() {
        val model = FakeObservation(); model.loaded = false
        val fixture = Fixture(observation = model)
        fixture.connect()
        fixture.probe.preparePhoto { error("No model, no capture confirmation") }
        assertEquals(0, fixture.captures)
        assertEquals(0, model.calls)
        assertTrue(fixture.updates.isEmpty())
        fixture.probe.destroy()
    }
    @Test fun gemmaFailureAndTimeoutAreInferenceFailuresWithoutMockSuccess() {
        for (timeout in listOf(false, true)) {
            val model = FakeObservation()
            val fixture = Fixture(observation = model)
            fixture.connect(); fixture.capture(); fixture.deliver(jpeg())
            if (timeout) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(121))
            else model.finish(Result.failure(IllegalStateException("secret detail not logged")))
            idle()
            assertTrue(fixture.updates.isEmpty())
            val run = fixture.probe.exportRecords().getJSONArray("runs").getJSONObject(0)
            assertEquals("failed", run.getString("outcome"))
            assertEquals("inference", run.getString("failure_stage"))
            assertTrue(fixture.lines.none { it.contains("secret detail") || it.contains("Mock 完成") })
            fixture.probe.destroy()
        }
    }
    @Test fun consecutiveGemmaRoundsRecycleSdkAndNeverReusePreviousImageOrReply() {
        val model = FakeObservation()
        val fixture = Fixture(observation = model)
        fixture.connect(); fixture.capture(); fixture.deliver(jpeg())
        val oldReply = model.callback!!
        model.finish(Result.success("第一张")); idle()
        var second: PhotoGate.Request? = null
        fixture.probe.preparePhoto { second = it }; idle()
        fixture.probe.confirmPhoto(second!!.id); fixture.deliver(jpeg())
        oldReply(Result.success("旧画面迟到")); idle()
        assertEquals(1, fixture.updates.size)
        model.finish(Result.success("第二张")); idle()
        assertEquals(2, fixture.updates.size)
        assertTrue(fixture.updates.last().contains("第二张 #${second!!.id.take(8)}"))
        assertEquals(2, model.calls)
        fixture.probe.destroy()
    }
}
