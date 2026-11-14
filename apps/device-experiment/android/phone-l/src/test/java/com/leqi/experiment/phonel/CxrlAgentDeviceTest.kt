package com.leqi.experiment.phonel

import android.os.Looper
import com.rokid.cxr.session.*
import java.lang.reflect.Proxy
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CxrlAgentDeviceTest {
    private class Fixture {
        val errors = mutableListOf<String>()
        val tokens = mutableListOf<String>()
        var idle = false
        var closes = 0
        var creates = 0
        var rejectCreate = false
        private val manager = proxy<CxrSessionManager> { method, _ ->
            check(method == "create")
            check(!rejectCreate && (creates == 0 || idle))
            creates++
            proxy<CxrSession> { call, args ->
                when (call) {
                    "getState" -> if (idle) SessionState.Idle else SessionState.Terminating
                    "connect" -> { tokens += args!![0] as String; null }
                    "close" -> { closes++; null }
                    "addLifecycleCallback", "addCustomCmdCallback", "addGlassesEventListener" -> null
                    else -> error("Unexpected SDK call: $call")
                }
            }
        }
        val device = CxrlAgentDevice(manager, {}, { errors += it }, object : AbstractExecutorService() {
            override fun execute(command: Runnable) = command.run()
            override fun shutdown() = Unit
            override fun shutdownNow() = mutableListOf<Runnable>()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
        })

        fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
    }

    @Test fun reconnectWaitsForIdleAndUsesOnlyTheMostRecentToken() {
        val f = Fixture()
        f.device.connect("first")
        f.device.connect("second")
        f.device.connect("third")
        f.advance(500)
        assertEquals(1, f.creates)
        assertEquals(1, f.closes)

        f.idle = true
        f.advance(200)
        assertEquals(listOf("first", "third"), f.tokens)
        assertTrue(f.errors.isEmpty())
        f.device.close()
    }

    @Test fun closeTimeoutAllowsExplicitRetryAndCreateFailureIsRecoverable() {
        val f = Fixture()
        f.device.connect("first")
        f.device.connect("timed-out")
        f.advance(10_200)
        assertEquals(listOf("first"), f.tokens)
        assertEquals(1, f.errors.size)

        f.idle = true
        f.rejectCreate = true
        f.device.connect("rejected")
        f.advance(200)
        assertEquals(2, f.errors.size)
        f.rejectCreate = false
        f.device.connect("retry")
        assertEquals(listOf("first", "retry"), f.tokens)
        f.device.close()
    }

    companion object {
        private inline fun <reified T> proxy(crossinline invoke: (String, Array<Any?>?) -> Any?): T =
            T::class.java.cast(Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                invoke(method.name, args)
            })
    }
}
