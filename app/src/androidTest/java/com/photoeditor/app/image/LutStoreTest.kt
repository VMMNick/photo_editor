package com.photoeditor.app.image

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LutStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clean() { File(context.filesDir, "luts").deleteRecursively() }

    private fun cubeFile(name: String, size: Int): Uri {
        val sb = StringBuilder("TITLE \"Warm\"\nLUT_3D_SIZE $size\n")
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            sb.append("${(r / (size - 1f) * 1.1f).coerceAtMost(1f)} ${g / (size - 1f)} ${b / (size - 1f) * 0.9f}\n")
        }
        return Uri.fromFile(File(context.cacheDir, name).also { it.writeText(sb.toString()) })
    }

    @Test
    fun importListLoadDelete() {
        val store = LutStore(context)
        val info = store.import(context, cubeFile("warm.cube", 33), "warm.cube")
        assertEquals("Warm", info.title)
        assertEquals(listOf(info), LutStore(context).list())
        val t0 = System.nanoTime()
        val lut = store.load(info.id)!!
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals(33, lut.size)
        assertTrue("швидке відкриття: $ms мс", ms < 500)
        // Після збереження в 8 біт значення в межах кроку 1/255
        val s = lut.sample(0.5f, 0.5f, 0.5f)
        assertEquals(0.55f, s[0], 0.01f)
        assertEquals(0.45f, s[2], 0.01f)
        store.delete(info.id)
        assertTrue(store.list().isEmpty())
        assertNull(store.load(info.id))
    }

    @Test
    fun brokenFileIsRejectedAndNotListed() {
        val store = LutStore(context)
        val bad = Uri.fromFile(File(context.cacheDir, "bad.cube").also { it.writeText("LUT_3D_SIZE 4\n0 0 0\n") })
        assertTrue(runCatching { store.import(context, bad, "bad.cube") }.isFailure)
        assertTrue(store.list().isEmpty())
    }
}
