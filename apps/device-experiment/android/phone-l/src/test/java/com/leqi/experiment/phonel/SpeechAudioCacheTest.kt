package com.leqi.experiment.phonel

import org.junit.Assert.*
import org.junit.Test

class SpeechAudioCacheTest {
    @Test fun repeatedPhraseSkipsSynthesisButNewTextDoesNotReuseOldAudio() {
        val cache = SpeechAudioCache(4)
        var calls = 0
        fun generate(text: String) = cache.getOrSynthesize(text) {
            calls++; SpeechAudio(floatArrayOf(calls / 10f), 16000)
        }
        val first = generate("我在。")
        assertSame(first, generate("我在。"))
        assertEquals(1, calls)
        assertNotSame(first, generate("你好。"))
        assertEquals(2, calls)
        cache.clear()
        assertNotSame(first, generate("我在。"))
        assertEquals(3, calls)
    }

    @Test fun leastRecentlyUsedAudioIsEvictedWithinSampleBudget() {
        val cache = SpeechAudioCache(4)
        val calls = mutableListOf<String>()
        fun generate(text: String) = cache.getOrSynthesize(text) {
            calls.add(text); SpeechAudio(floatArrayOf(0.1f, 0.2f), 16000)
        }
        generate("A"); generate("B"); generate("A"); generate("C"); generate("A"); generate("B")
        assertEquals(listOf("A", "B", "C", "B"), calls)
    }

    @Test fun oversizedAudioAndFailedSynthesisAreNeverCached() {
        val cache = SpeechAudioCache(2)
        var calls = 0
        repeat(2) { cache.getOrSynthesize("长句") { calls++; SpeechAudio(FloatArray(3) { 0.1f }, 16000) } }
        repeat(2) {
            assertThrows(IllegalStateException::class.java) {
                cache.getOrSynthesize("失败") { calls++; error("synthesis failed") }
            }
        }
        assertEquals(4, calls)
    }

    @Test fun shortOpeningSentenceIsDeliveredBeforeTheRemainingIntroduction() {
        val opening = "我在。你可以说出想去的地方，也可以问我眼前的情况。"
        val chunks = SpeechChunks.split(opening)
        assertEquals("我在。", chunks.first())
        assertEquals(opening, chunks.joinToString(""))
        assertEquals(listOf("你好。"), SpeechChunks.split("你好。"))
        assertEquals("！你好。", SpeechChunks.split("！你好。后面继续说明。").first())
        val decimal = "价格是12.5元。需要继续吗？"
        assertEquals("价格是12.5元。", SpeechChunks.split(decimal).first())
        assertEquals(decimal, SpeechChunks.split(decimal).joinToString(""))
    }
}
