package com.photoeditor.app.gl

import com.photoeditor.app.core.AlphaMap
import com.photoeditor.app.core.CurveMath
import com.photoeditor.app.core.CubeLut
import com.photoeditor.app.core.GeometryMath
import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.EditState
import com.photoeditor.app.model.GradeWheel
import com.photoeditor.app.model.MaskKind
import com.photoeditor.app.model.NRect
import com.photoeditor.app.model.RangeMode
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Усе, що потрібно шейдеру для одного кадру, у вигляді простих чисел. Без android.* */
class RenderParams(
    val xform: FloatArray,
    val imgScale: FloatArray,
    val outW: Float,
    val outH: Float,
    val a: Adjustments,
    val maskCount: Int,
    val maskType: IntArray,
    val maskA: FloatArray,
    val maskB: FloatArray,
    val maskInv: FloatArray,
    val maskAdjA: FloatArray,
    val maskAdjB: FloatArray,
    val maskMaps: Array<AlphaMap?>,
    val showMask: Int,
    val bypass: Boolean,
    val gradeS: FloatArray,
    val gradeM: FloatArray,
    val gradeH: FloatArray,
    val gradeLum: FloatArray,
    val seed: Float,
    /** Текстура кривих 256×1 RGBA (core/CurveMath.combinedLut) або null, якщо криві прямі. */
    val curveLut: ByteArray? = null,
    /** Підсвітити пересвічені (червоним) і провалені (синім) пікселі. */
    val clipWarning: Boolean = false,
    /** Положення шторки «до/після» (0..1 по ширині результату) або -1. */
    val splitX: Float = -1f,
    /** Коефіцієнт дисторсії (GeometryMath.lensK). */
    val lensK: Float = 0f,
    val lensN: Float = 1f,
    /** Діапазони масок: режим (0 — ні, 1 — яскравість, 2 — колір) і параметри по 4 числа. */
    val maskRangeMode: IntArray = IntArray(4),
    val maskRange: FloatArray = FloatArray(16),
    /** Завантажений 3D LUT (null — без LUT) та інтенсивність. */
    val lut: CubeLut? = null,
    val lutAmount: Float = 0f,
) {
    val outAspect get() = outW / outH

    companion object {
        /**
         * @param maskMaps растрові карти для BRUSH/SUBJECT масок (у тому ж порядку, що й state.masks)
         * @param cropOverride під час кадрування показуємо весь кадр, а рамку малює UI
         */
        fun from(
            state: EditState,
            srcW: Int,
            srcH: Int,
            effective: Adjustments,
            maskMaps: List<AlphaMap?>,
            cropOverride: NRect? = null,
            showMask: Int = -1,
            bypass: Boolean = false,
            clipWarning: Boolean = false,
            splitX: Float = -1f,
            lut: CubeLut? = null,
        ): RenderParams {
            val gm = GeometryMath(srcW, srcH, state.geometry, cropOverride)
            val mx = max(srcW, srcH).toFloat()
            val sx = srcW / mx
            val sy = srcH / mx

            val n = minOf(state.masks.size, Shaders.MAX_MASKS)
            val type = IntArray(4)
            val ma = FloatArray(16)
            val mb = FloatArray(16)
            val inv = FloatArray(4)
            val adjA = FloatArray(16)
            val adjB = FloatArray(16)
            val maps = arrayOfNulls<AlphaMap>(4)
            val rangeMode = IntArray(4)
            val range = FloatArray(16)
            for (i in 0 until n) {
                val m = state.masks[i]
                when (m.kind) {
                    MaskKind.LINEAR -> {
                        type[i] = 0
                        ma[i * 4] = m.x0 * sx; ma[i * 4 + 1] = m.y0 * sy
                        ma[i * 4 + 2] = m.x1 * sx; ma[i * 4 + 3] = m.y1 * sy
                    }
                    MaskKind.RADIAL -> {
                        type[i] = 1
                        ma[i * 4] = m.cx * sx; ma[i * 4 + 1] = m.cy * sy
                        ma[i * 4 + 2] = m.rx; ma[i * 4 + 3] = m.ry
                        mb[i * 4] = cos(m.angle); mb[i * 4 + 1] = sin(m.angle); mb[i * 4 + 2] = m.feather
                    }
                    MaskKind.BRUSH, MaskKind.SUBJECT -> {
                        type[i] = 2
                        maps[i] = maskMaps.getOrNull(i)
                    }
                }
                inv[i] = if (m.invert) 1f else 0f
                val r = m.range
                rangeMode[i] = r.mode.ordinal
                when (r.mode) {
                    RangeMode.LUMINANCE -> { range[i * 4] = r.lo; range[i * 4 + 1] = r.hi; range[i * 4 + 2] = 0.02f + r.smooth * 0.2f }
                    RangeMode.COLOR -> { range[i * 4] = r.hue; range[i * 4 + 1] = r.hueWidth; range[i * 4 + 2] = 2f + r.smooth * 60f }
                    RangeMode.NONE -> Unit
                }
                adjA[i * 4] = m.adjust.exposure; adjA[i * 4 + 1] = m.adjust.contrast
                adjA[i * 4 + 2] = m.adjust.highlights; adjA[i * 4 + 3] = m.adjust.shadows
                adjB[i * 4] = m.adjust.saturation; adjB[i * 4 + 1] = m.adjust.temperature
                adjB[i * 4 + 2] = m.adjust.tint
            }

            val g = effective.grading
            return RenderParams(
                xform = gm.outToSource.toColumnMajorFloats(),
                imgScale = floatArrayOf(sx, sy),
                outW = gm.outW.toFloat(),
                outH = gm.outH.toFloat(),
                a = effective,
                maskCount = n,
                maskType = type,
                maskA = ma,
                maskB = mb,
                maskInv = inv,
                maskAdjA = adjA,
                maskAdjB = adjB,
                maskMaps = maps,
                showMask = if (showMask in 0 until n) showMask else -1,
                bypass = bypass,
                gradeS = wheelColor(g.shadows),
                gradeM = wheelColor(g.midtones),
                gradeH = wheelColor(g.highlights),
                gradeLum = floatArrayOf(g.shadows.lum, g.midtones.lum, g.highlights.lum),
                seed = 17.31f,
                curveLut = if (effective.curves.isIdentity) null else CurveMath.combinedLut(effective.curves),
                clipWarning = clipWarning,
                splitX = splitX,
                lensK = gm.lensK.toFloat(),
                lensN = gm.lensN.toFloat(),
                maskRangeMode = rangeMode,
                maskRange = range,
                // LUT потрібен саме той, що вибрано в налаштуваннях (захист від застарілого кешу)
                lut = lut?.takeIf { effective.lut != null },
                lutAmount = if (lut != null && effective.lut != null) effective.lutAmount else 0f,
            )
        }

        /** Відтінок колеса → зсув кольору з нульовою яскравістю. */
        fun wheelColor(w: GradeWheel): FloatArray {
            if (w.sat <= 0f) return floatArrayOf(0f, 0f, 0f)
            val rgb = hsvToRgb(w.hue / 360f, 1f, 1f)
            val l = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
            val k = w.sat * 0.22f
            return floatArrayOf((rgb[0] - l) * k, (rgb[1] - l) * k, (rgb[2] - l) * k)
        }

        fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
            fun ch(n: Float): Float {
                val k = (n + h * 6f) % 6f
                return v - v * s * max(0f, minOf(k, 4f - k, 1f))
            }
            return floatArrayOf(ch(5f), ch(3f), ch(1f))
        }
    }
}
