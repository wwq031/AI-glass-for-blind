package com.leqi.experiment.phonel

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class BundledModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val content = "real-weight-bytes-for-installer-test".toByteArray()
    private fun manifest(): JSONObject = JSONObject().put("bytes", content.size)
        .put("sha256", MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) })
        .put("parts", JSONArray().put(JSONObject().put("name", "weights-000.modelpart").put("bytes", 10))
            .put(JSONObject().put("name", "weights-001.modelpart").put("bytes", content.size - 10)))
    private fun open(name: String) = when (name) {
        "weights-000.modelpart" -> content.copyOfRange(0, 10).inputStream()
        else -> content.copyOfRange(10, content.size).inputStream()
    }
    @Test fun streamsAllPartsAndVerifiesCacheBeforeReuse() {
        val root = temporary.newFolder()
        val first = BundledModel.install(root, manifest(), ::open) { }
        assertArrayEquals(content, first.readBytes())
        BundledModel.install(root, manifest(), { error("Valid cache must not reopen APK weights") }) { }
        first.writeBytes(ByteArray(content.size)) // Same length is insufficient evidence.
        val repaired = BundledModel.install(root, manifest(), ::open) { }
        assertArrayEquals(content, repaired.readBytes())
    }
    @Test fun truncatedOrCorruptedPartsNeverBecomeReadyModel() {
        for (truncated in listOf(true, false)) {
            val root = temporary.newFolder()
            assertThrows(IllegalStateException::class.java) {
                BundledModel.install(root, manifest(), { name ->
                    if (truncated) byteArrayOf(1).inputStream()
                    else open(name).readBytes().map { (it.toInt() xor 1).toByte() }.toByteArray().inputStream()
                }) { }
            }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }
    @Test fun malformedManifestCannotReadArbitraryAssets() {
        val invalid = manifest()
        invalid.getJSONArray("parts").getJSONObject(0).put("name", "../elsewhere")
        assertThrows(IllegalArgumentException::class.java) {
            BundledModel.install(temporary.newFolder(), invalid, { error("No reads") }) { }
        }
    }
    @Test fun cancelledPreparationDoesNotPublishPartialWeights() {
        val root = temporary.newFolder()
        assertThrows(IllegalStateException::class.java) {
            BundledModel.install(root, manifest(), ::open, cancelled = { true }) { }
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun onlyBoundedJsonSummaryCanBeDisplayedAsGemmaOutput() {
        assertEquals("桌上有杯子", GemmaObservation.parseSummary("{\"summary\":\"桌上有杯子\"}"))
        for (raw in listOf("", "```json\n{\"summary\":\"x\"}\n```", "{\"summary\":null}",
            "{\"summary\":\"\"}", "{\"summary\":\"x\",\"extra\":1}",
            "{\"summary\":\"${"字".repeat(161)}\"}")) {
            assertThrows(Exception::class.java) { GemmaObservation.parseSummary(raw) }
        }
    }
}
