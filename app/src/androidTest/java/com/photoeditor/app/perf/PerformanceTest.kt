package com.photoeditor.app.perf

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photoeditor.app.core.PixelImage
import com.photoeditor.app.gl.GlWorker
import com.photoeditor.app.gl.OffscreenRenderer
import com.photoeditor.app.gl.RenderParams
import com.photoeditor.app.image.ImageLoader
import com.photoeditor.app.image.toPixelImage
import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.EditState
import com.photoeditor.app.model.Presets
import com.photoeditor.app.ui.EditorViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Замір швидкодії ключових операцій на 12-Мп фото. Результати — у logcat (тег PERF) і у звіті тесту.
 * Пороги щедрі (емулятор повільніший за телефон): тест ловить регресії в рази, а не на відсотки.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private inline fun <T> timed(name: String, block: () -> T): Pair<T, Long> {
        val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i("PERF", "$name: $ms ms")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("perf", "$name: $ms ms") })
        return r to ms
    }

    @Test
    fun decode12MpPreview() {
        val (loaded, ms) = timed("load 12MP → preview") { ImageLoader.load(app, photo, EditorViewModel.PREVIEW_MAX_SIDE, EditorViewModel.PREVIEW_MAX_PIXELS) }
        assertTrue(loaded.bitmap.width <= 2048)
        assertTrue("завантаження прев'ю: $ms мс", ms < 6_000)
    }

    @Test
    fun previewToPixelImage() {
        val b = ImageLoader.load(app, photo, EditorViewModel.PREVIEW_MAX_SIDE, EditorViewModel.PREVIEW_MAX_PIXELS).bitmap
        val (_, ms) = timed("bitmap → PixelImage") { b.toPixelImage() }
        assertTrue("$ms мс", ms < 1_500)
    }

    @Test
    fun presetThumbnails() {
        val b = ImageLoader.load(app, photo, EditorViewModel.PREVIEW_MAX_SIDE, EditorViewModel.PREVIEW_MAX_PIXELS).bitmap
        val (thumbs, cold) = timed("19 preset thumbnails (cold)") { runBlocking { GlWorker.presetThumbnails(b, Presets.builtIn) } }
        assertTrue(thumbs.size == Presets.builtIn.size)
        // Другий раз — спільний рендерер уже створений, шейдер скомпільований
        val (_, warm) = timed("19 preset thumbnails (warm)") { runBlocking { GlWorker.presetThumbnails(b, Presets.builtIn) } }
        assertTrue("$cold мс", cold < 5_000)
        assertTrue("повторно: $warm мс", warm < 2_500)
    }

    @Test
    fun fullResolutionRender() {
        val full = ImageLoader.load(app, photo, 8192, 24_000_000L).bitmap
        val s = EditState(adjust = Adjustments(exposure = 0.5f, contrast = 0.3f, clarity = 0.4f, sharpen = 0.5f, noise = 0.3f))
        val (_, ms) = timed("GPU render 12MP (with detail)") {
            OffscreenRenderer().use { gl ->
                gl.setSource(full)
                val p = RenderParams.from(s, full.width, full.height, s.adjust, emptyList())
                gl.render(p, full.width, full.height).recycle()
            }
        }
        assertTrue("$ms мс", ms < 10_000)
    }

    @Test
    fun openInViewModel() {
        File(app.filesDir, "session").deleteRecursively()
        var vm: EditorViewModel? = null
        val instr = InstrumentationRegistry.getInstrumentation()
        instr.runOnMainSync { vm = EditorViewModel(app) }
        val t0 = System.nanoTime()
        instr.runOnMainSync { vm!!.open(photo) }
        while (true) {
            var done = false
            instr.runOnMainSync { done = vm!!.info != null && !vm!!.loading }
            if (done) break
            if ((System.nanoTime() - t0) / 1e6 > 30_000) break
            Thread.sleep(20)
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i("PERF", "open in ViewModel (copy + decode + install): $ms ms")
        instr.sendStatus(0, android.os.Bundle().apply { putString("perf", "open: $ms ms") })
        instr.runOnMainSync { vm!!.close() }
        assertTrue("відкриття: $ms мс", ms < 8_000)
    }

    companion object {
        private lateinit var photo: Uri

        /** Синтетичне «фото» 4000×3000 з градієнтами й деталями (JPEG 92). */
        @BeforeClass @JvmStatic
        fun makePhoto() {
            val ctx: Application = ApplicationProvider.getApplicationContext()
            val f = File(ctx.cacheDir, "perf_12mp.jpg")
            if (!f.isFile) {
                val b = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                val p = Paint()
                p.shader = LinearGradient(0f, 0f, 4000f, 3000f, Color.rgb(40, 90, 160), Color.rgb(230, 190, 120), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, 4000f, 3000f, p)
                p.shader = null
                val rnd = java.util.Random(1)
                repeat(4000) {
                    p.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
                    c.drawCircle(rnd.nextFloat() * 4000, rnd.nextFloat() * 3000, 4f + rnd.nextFloat() * 30, p)
                }
                f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                b.recycle()
            }
            photo = Uri.fromFile(f)
        }
    }
}
