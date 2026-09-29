package com.photoeditor.app.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RawPreviewExtractorTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Мінімальний JPEG-«скелет»: SOI, SOFn з розмірами, SOS, ентропійні дані, EOI. */
    private fun jpeg(w: Int, h: Int, payload: ByteArray = ByteArray(200) { 0x55 }, sof: Int = 0xC0, withExif: Boolean = false): ByteArray {
        val soi = b(0xFF, 0xD8)
        // EXIF-блок з вкладеною мініатюрою — парсер не має на ній «спіткнутися»
        val exif = if (withExif) {
            val thumb = jpeg(16, 16)
            val len = thumb.size + 2
            b(0xFF, 0xE1, len shr 8, len and 0xFF) + thumb
        } else ByteArray(0)
        val sofSeg = b(0xFF, sof, 0, 11, 8, h shr 8, h and 0xFF, w shr 8, w and 0xFF, 1, 1, 0x11, 0)
        val sos = b(0xFF, 0xDA, 0, 8, 1, 1, 0, 0, 0x3F, 0)
        return soi + exif + sofSeg + sos + payload + b(0xFF, 0xD9)
    }

    @Test
    fun picksLargestByPixelsNotByBytes() {
        val thumb = jpeg(160, 120, ByteArray(5000) { 0x11 }) // більше байтів, але мало пікселів
        val preview = jpeg(6000, 4000, ByteArray(300) { 0x22 })
        val file = ByteArray(128) + thumb + ByteArray(64) + preview + ByteArray(32)
        val r = RawPreviewExtractor.largestJpeg(file)!!
        assertEquals(128 + thumb.size + 64, r.first)
        assertEquals(preview.size, r.second)
    }

    @Test
    fun handlesStuffedBytesAndRestartMarkers() {
        // FF00 (екранований байт) та RST0 усередині ентропійних даних не є кінцем файлу
        val payload = b(0x12, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56, 0xFF, 0xFF, 0xD7, 0x78)
        val j = jpeg(100, 100, payload)
        val r = RawPreviewExtractor.largestJpeg(ByteArray(10) + j)!!
        assertEquals(10, r.first)
        assertEquals(j.size, r.second)
    }

    @Test
    fun nestedExifThumbnailDoesNotBreakParsing() {
        val j = jpeg(3000, 2000, withExif = true)
        val r = RawPreviewExtractor.largestJpeg(j)!!
        assertEquals(0, r.first)
        assertEquals(j.size, r.second)
    }

    @Test
    fun progressiveJpegAccepted() {
        assertNotNull(RawPreviewExtractor.largestJpeg(jpeg(800, 600, sof = 0xC2)))
    }

    @Test
    fun losslessRawDataIsIgnored() {
        // CR2 зберігає сирі дані як lossless JPEG (SOF3) — BitmapFactory його не декодує
        val lossless = jpeg(6000, 4000, sof = 0xC3)
        val preview = jpeg(1600, 1067)
        val r = RawPreviewExtractor.largestJpeg(lossless + preview)!!
        assertEquals(lossless.size, r.first)
    }

    @Test
    fun noJpegOrTruncated() {
        assertNull(RawPreviewExtractor.largestJpeg(ByteArray(1000) { (it * 7).toByte() }))
        assertNull(RawPreviewExtractor.largestJpeg(ByteArray(0)))
        val full = jpeg(100, 100)
        assertNull(RawPreviewExtractor.largestJpeg(full.copyOf(full.size - 2)))
    }
}
