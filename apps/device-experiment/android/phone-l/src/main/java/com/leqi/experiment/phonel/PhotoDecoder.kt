package com.leqi.experiment.phonel

import android.graphics.Bitmap
import android.graphics.BitmapFactory

internal data class PhotoMetadata(val bytes: Int, val width: Int, val height: Int, val mime: String)
internal data class DecodedPhoto(val bitmap: Bitmap, val metadata: PhotoMetadata)

internal object PhotoDecoder {
    const val MAX_BYTES = 12 * 1024 * 1024
    fun validBounds(width: Int, height: Int): Boolean =
        width in 1..4096 && height in 1..4096 && width.toLong() * height <= 12_000_000L

    fun completeContainer(data: ByteArray): Boolean {
        if (data.size < 4) return false
        if (data[0] == 0xff.toByte() && data[1] == 0xd8.toByte()) {
            return data[data.size - 2] == 0xff.toByte() && data.last() == 0xd9.toByte()
        }
        if (data.size < 20 || String(data, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(data, 8, 4, Charsets.US_ASCII) != "WEBP") return false
        var declared = 0L
        for (i in 0..3) declared = declared or ((data[4 + i].toLong() and 255) shl (i * 8))
        return declared + 8 == data.size.toLong()
    }

    fun decode(data: ByteArray): DecodedPhoto? {
        if (data.isEmpty() || data.size > MAX_BYTES || !completeContainer(data)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (!validBounds(bounds.outWidth, bounds.outHeight) ||
            bounds.outMimeType !in setOf("image/webp", "image/jpeg")) return null
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) ?: return null
        if (!validBounds(bitmap.width, bitmap.height) ||
            bitmap.width != bounds.outWidth || bitmap.height != bounds.outHeight) {
            bitmap.recycle()
            return null
        }
        return DecodedPhoto(bitmap, PhotoMetadata(data.size, bitmap.width, bitmap.height, bounds.outMimeType))
    }
}
