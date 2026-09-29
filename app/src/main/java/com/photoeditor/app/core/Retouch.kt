package com.photoeditor.app.core

import com.photoeditor.app.model.CloneOp
import com.photoeditor.app.model.EraseOp
import com.photoeditor.app.model.HealOp
import com.photoeditor.app.model.RetouchOp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Деструктивні операції ретушу над пікселями джерела.
 * Координати операцій нормалізовані, тому однаковий список відтворюється
 * і на прев'ю (~2 Мп), і на повній роздільності під час експорту.
 */
object Retouch {

    /**
     * @param inPlace дозволити змінювати [image] замість копіювання. Для експорту в повній роздільності
     *   це економить по ~100 МБ на кожну операцію (24 Мп × 4 байти). Прев'ю кешує проміжні стани,
     *   тому там завжди false.
     */
    fun apply(image: PixelImage, op: RetouchOp, inPlace: Boolean = false): PixelImage = when (op) {
        is HealOp -> heal(image, op, inPlace)
        is CloneOp -> clone(image, op) // штамп читає з незміненого знімка, тож копія потрібна завжди
        is EraseOp -> Inpainter.inpaint(image, op.mask, inPlace = inPlace)
    }

    fun applyAll(image: PixelImage, ops: List<RetouchOp>, inPlace: Boolean = false, onProgress: (Int) -> Unit = {}): PixelImage {
        var cur = image
        ops.forEachIndexed { i, op ->
            cur = apply(cur, op, inPlace)
            onProgress(i + 1)
        }
        return cur
    }

    private fun pxRadius(img: PixelImage, r: Float) = r * max(img.width, img.height)

    // ------------------------------------------------------------------ Точковий коректор

    /**
     * «Healing brush» у спрощеному вигляді:
     * 1) шукаємо поруч ділянку з найбільш схожим оточенням (SSD по кільцю навколо плями);
     * 2) переносимо її текстуру;
     * 3) вирівнюємо колір/яскравість за різницею на межі (мембранна інтерполяція),
     *    щоб латка не відрізнялася тоном від навколишньої шкіри/фону.
     */
    fun heal(src: PixelImage, op: HealOp, inPlace: Boolean = false): PixelImage {
        val cx = op.u * src.width
        val cy = op.v * src.height
        val r = max(2f, pxRadius(src, op.radius))
        val best = findHealSource(src, cx, cy, r)
        // Донорська ділянка щонайменше за 2.2r від цілі — області не перетинаються, писати на місці безпечно
        return blendPatch(src, src, cx, cy, best[0], best[1], r, correctTone = true, hardness = 0.75f, out = if (inPlace) src else null)
    }

    private fun findHealSource(img: PixelImage, cx: Float, cy: Float, r: Float): FloatArray {
        val ringIn = r * 1.05f
        val ringOut = r * 1.6f
        // Вибірка кільця навколо цілі
        val samples = 48
        val tgt = Array(samples * 2) { FloatArray(3) }
        val offs = Array(samples * 2) { FloatArray(2) }
        for (i in 0 until samples * 2) {
            val a = (i % samples) * 2 * PI / samples
            val rr = if (i < samples) ringIn else ringOut
            offs[i][0] = (cos(a) * rr).toFloat()
            offs[i][1] = (sin(a) * rr).toFloat()
            img.sample(cx + offs[i][0], cy + offs[i][1], tgt[i])
        }
        // Текстура в центрі цілі нас не цікавить (там дефект), тому порівнюємо тільки оточення
        // і штрафуємо кандидатів, чий центр контрастний (щоб не перенести інший дефект).
        val tmp = FloatArray(3)
        var bestScore = Float.MAX_VALUE
        var bx = cx + 2.5f * r
        var by = cy
        val dists = floatArrayOf(2.2f, 2.8f, 3.6f, 4.6f)
        for (dk in dists) {
            for (ai in 0 until 24) {
                val a = ai * 2 * PI / 24
                val sx = cx + (cos(a) * r * dk).toFloat()
                val sy = cy + (sin(a) * r * dk).toFloat()
                if (sx - ringOut < 0 || sy - ringOut < 0 || sx + ringOut >= img.width || sy + ringOut >= img.height) continue
                var ssd = 0f
                for (i in offs.indices) {
                    img.sample(sx + offs[i][0], sy + offs[i][1], tmp)
                    for (c in 0..2) {
                        val d = tmp[c] - tgt[i][c]
                        ssd += d * d
                    }
                }
                val score = ssd / offs.size + centerVariance(img, sx, sy, r) * 0.6f + dk * 4f
                if (score < bestScore) {
                    bestScore = score
                    bx = sx
                    by = sy
                }
            }
        }
        return floatArrayOf(bx, by)
    }

    private fun centerVariance(img: PixelImage, x: Float, y: Float, r: Float): Float {
        val tmp = FloatArray(3)
        var sum = 0f
        var sum2 = 0f
        var n = 0
        val step = max(1f, r / 4f)
        var dy = -r
        while (dy <= r) {
            var dx = -r
            while (dx <= r) {
                if (dx * dx + dy * dy <= r * r) {
                    img.sample(x + dx, y + dy, tmp)
                    val l = 0.299f * tmp[0] + 0.587f * tmp[1] + 0.114f * tmp[2]
                    sum += l
                    sum2 += l * l
                    n++
                }
                dx += step
            }
            dy += step
        }
        if (n == 0) return 0f
        val m = sum / n
        return max(0f, sum2 / n - m * m)
    }

    /**
     * Переносить круглу латку з (sx, sy) в (cx, cy) з м'яким краєм.
     * @param from зображення-джерело пікселів (для штампа — знімок до мазка)
     */
    private fun blendPatch(
        dst: PixelImage, from: PixelImage,
        cx: Float, cy: Float, sx: Float, sy: Float, r: Float,
        correctTone: Boolean, hardness: Float = 0.35f, out: PixelImage? = null,
    ): PixelImage {
        val res = out ?: dst.copy()
        // Різниця тону на межі — інтерполюється всередину латки
        val nb = 32
        val boundary = Array(nb) { FloatArray(3) }
        val mean = FloatArray(3)
        if (correctTone) {
            val a = FloatArray(3)
            val b = FloatArray(3)
            for (i in 0 until nb) {
                val ang = i * 2 * PI / nb
                val ox = (cos(ang) * r).toFloat()
                val oy = (sin(ang) * r).toFloat()
                // Усереднення по невеликій дузі, щоб шум не «просочувався» в корекцію
                for (c in 0..2) boundary[i][c] = 0f
                for (k in -1..1) {
                    val rr = r * (1f + 0.12f * k)
                    val kx = ox / r * rr
                    val ky = oy / r * rr
                    dst.sample(cx + kx, cy + ky, a)
                    from.sample(sx + kx, sy + ky, b)
                    for (c in 0..2) boundary[i][c] += (a[c] - b[c]) / 3f
                }
                for (c in 0..2) mean[c] += boundary[i][c] / nb
            }
        }
        val x0 = max(0, floor(cx - r).toInt())
        val x1 = min(dst.width - 1, ceil(cx + r).toInt())
        val y0 = max(0, floor(cy - r).toInt())
        val y1 = min(dst.height - 1, ceil(cy + r).toInt())
        val tmp = FloatArray(3)
        val inner = hardness.coerceIn(0f, 0.95f)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d = sqrt(dx * dx + dy * dy) / r
                if (d >= 1f) continue
                val alpha = if (d <= inner) 1f else {
                    val t = 1f - (d - inner) / (1f - inner)
                    t * t * (3 - 2 * t)
                }
                from.sample(sx + dx, sy + dy, tmp)
                if (correctTone) {
                    // Мембрана: від середнього в центрі до кутового значення на межі
                    var ang = atan2(dy, dx)
                    if (ang < 0) ang += (2 * PI).toFloat()
                    val fi = ang / (2 * PI).toFloat() * nb
                    val i0 = fi.toInt() % nb
                    val i1 = (i0 + 1) % nb
                    val t = fi - floor(fi)
                    val wEdge = d * d
                    for (c in 0..2) {
                        val edge = boundary[i0][c] * (1 - t) + boundary[i1][c] * t
                        tmp[c] += mean[c] * (1 - wEdge) + edge * wEdge
                    }
                }
                val idx = y * dst.width + x
                val p = res.pixels[idx]
                val r0 = (p shr 16) and 0xFF
                val g0 = (p shr 8) and 0xFF
                val b0 = p and 0xFF
                res.pixels[idx] = PixelImage.argb(
                    r0 + (tmp[0] - r0) * alpha,
                    g0 + (tmp[1] - g0) * alpha,
                    b0 + (tmp[2] - b0) * alpha,
                )
            }
        }
        return res
    }

    // ------------------------------------------------------------------ Штамп

    fun clone(src: PixelImage, op: CloneOp): PixelImage {
        val pts = op.stroke.points
        if (pts.isEmpty()) return src
        val out = src.copy()
        val r = max(2f, pxRadius(src, op.stroke.radius))
        val dx = op.du * src.width
        val dy = op.dv * src.height
        val spacing = max(1f, r * 0.2f)
        // Беремо пікселі з незміненого знімка, щоб мазок не «клонував сам себе»
        var px = pts[0].u * src.width
        var py = pts[0].v * src.height
        blendPatch(src, src, px, py, px + dx, py + dy, r, false, op.stroke.hardness, out)
        for (i in 1 until pts.size) {
            val x = pts[i].u * src.width
            val y = pts[i].v * src.height
            val dist = sqrt((x - px) * (x - px) + (y - py) * (y - py))
            val n = (dist / spacing).roundToInt()
            for (k in 1..n) {
                val t = k / n.toFloat()
                val ix = px + (x - px) * t
                val iy = py + (y - py) * t
                blendPatchOnto(out, src, ix, iy, ix + dx, iy + dy, r, op.stroke.hardness)
            }
            if (n > 0) {
                px = x
                py = y
            }
        }
        return out
    }

    /** Накладання відбитка штампа з max-альфою в межах одного мазка. */
    private fun blendPatchOnto(out: PixelImage, from: PixelImage, cx: Float, cy: Float, sx: Float, sy: Float, r: Float, hardness: Float) {
        blendPatch(out, from, cx, cy, sx, sy, r, false, hardness, out)
    }
}
