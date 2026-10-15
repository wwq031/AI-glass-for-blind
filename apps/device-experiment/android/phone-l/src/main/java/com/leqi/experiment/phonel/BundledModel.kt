package com.leqi.experiment.phonel

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Stream APK assets into app-owned storage; never hold weights in a ByteArray. */
internal object BundledModel {
    fun prepare(context: Context, cancelled: () -> Boolean, progress: (Int) -> Unit): File {
        val manifest = context.assets.open("gemma/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        return install(File(context.filesDir, "models"), manifest,
            { name -> context.assets.open("gemma/$name") }, cancelled, progress)
    }

    fun install(root: File, manifest: JSONObject, open: (String) -> InputStream,
        cancelled: () -> Boolean = { false }, progress: (Int) -> Unit): File {
        val expected = manifest.getString("sha256")
        require(expected.matches(Regex("[0-9a-f]{64}")))
        val bytes = manifest.getLong("bytes")
        require(bytes > 0)
        val parts = manifest.getJSONArray("parts")
        require(parts.length() in 1..32)
        val names = (0 until parts.length()).map { parts.getJSONObject(it).getString("name") }
        require(names.distinct().size == names.size && names.all { it.matches(Regex("weights-[0-9]{3}\\.modelpart")) })
        require((0 until parts.length()).sumOf { parts.getJSONObject(it).getLong("bytes").also { n -> require(n > 0) } } == bytes)
        check(root.isDirectory || root.mkdirs())
        val target = File(root, "$expected.litertlm")
        fun hash(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                check(!cancelled()) { "Model preparation cancelled" }
                val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if (target.isFile && target.length() == bytes && hash(target) == expected) { progress(100); return target }
        check(root.usableSpace >= bytes + 256L * 1024 * 1024) { "Insufficient storage" }
        val temporary = File.createTempFile("weights-", ".partial", root)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var lastPercent = -1
            temporary.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                for (index in names.indices) {
                    var partBytes = 0L
                    val expectedPartBytes = parts.getJSONObject(index).getLong("bytes")
                    open(names[index]).use { input ->
                        while (true) {
                            check(!cancelled()) { "Model preparation cancelled" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            partBytes += count
                            check(partBytes <= expectedPartBytes) { "Oversized model part" }
                            output.write(buffer, 0, count); digest.update(buffer, 0, count); total += count
                            val percent = (total * 100 / bytes).toInt()
                            if (percent != lastPercent) { lastPercent = percent; progress(percent) }
                        }
                    }
                    check(partBytes == expectedPartBytes) { "Truncated model part" }
                }
                output.fd.sync()
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(total == bytes && actual == expected) { "Model checksum mismatch" }
            check(!cancelled()) { "Model preparation cancelled" }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally { temporary.delete() }
    }
}
