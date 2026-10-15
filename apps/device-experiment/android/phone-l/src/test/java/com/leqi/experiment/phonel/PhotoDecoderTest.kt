package com.leqi.experiment.phonel

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoDecoderTest {
    private fun encoded(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(format, 80, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }
    @Test fun realJpegAndWebpDecodeWithDimensionsAndOriginalMime() {
        for ((format, mime) in listOf(Bitmap.CompressFormat.JPEG to "image/jpeg",
            Bitmap.CompressFormat.WEBP_LOSSLESS to "image/webp")) {
            val data = encoded(format)
            val photo = PhotoDecoder.decode(data)!!
            assertEquals(mime, photo.metadata.mime)
            assertEquals(32, photo.bitmap.width)
            assertEquals(24, photo.bitmap.height)
            assertEquals(data.size, photo.metadata.bytes)
            photo.bitmap.recycle()
        }
    }
    @Test fun emptyCorruptOversizedAndOtherFormatsAreRejected() {
        assertNull(PhotoDecoder.decode(byteArrayOf()))
        assertNull(PhotoDecoder.decode(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertNull(PhotoDecoder.decode(ByteArray(PhotoDecoder.MAX_BYTES + 1)))
        assertNull(PhotoDecoder.decode(encoded(Bitmap.CompressFormat.PNG)))
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP_LOSSLESS)) {
            val data = encoded(format)
            assertNull(PhotoDecoder.decode(data.copyOf(data.size - 2)))
        }
    }
    @Test fun modelReceivesRealJpegWhileOriginalWebpMetadataIsPreserved() {
        val webp = encoded(Bitmap.CompressFormat.WEBP_LOSSLESS)
        val photo = PhotoDecoder.decode(webp, forModel = true)!!
        assertEquals("image/webp", photo.metadata.mime)
        assertEquals(webp.size, photo.metadata.bytes)
        assertEquals(0xff.toByte(), photo.modelJpeg!![0])
        assertEquals(0xd8.toByte(), photo.modelJpeg[1])
        val modelPhoto = PhotoDecoder.decode(photo.modelJpeg)!!
        assertEquals("image/jpeg", modelPhoto.metadata.mime)
        assertEquals(photo.bitmap.width, modelPhoto.bitmap.width)
        assertEquals(photo.bitmap.height, modelPhoto.bitmap.height)
        photo.bitmap.recycle(); modelPhoto.bitmap.recycle()
    }
}
