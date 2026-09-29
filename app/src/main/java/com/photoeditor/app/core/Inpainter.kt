package com.photoeditor.app.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Заповнення області видалення (content-aware fill) —
 * багатомасштабний PatchMatch + голосування патчів (Wexler et al. 2007, Barnes et al. 2009).
 *
 * Працює на CPU без моделей і без мережі. Складність ~ O(площа_дірки × ітерації),
 * великі області спершу опрацьовуються на зменшеній копії.
 */
object Inpainter {

    private const val P = 3 // радіус патча → 7×7
    private const val MAX_REGION_PIXELS = 900_000

    fun inpaint(image: PixelImage, mask: AlphaMap, seed: Int = 7, inPlace: Boolean = false): PixelImage {
        val w = image.width
        val h = image.height
        val m = mask.resized(w, h)
        var hole = BooleanArray(w * h) { (m.data[it].toInt() and 0xFF) > 64 }
        hole = dilate(hole, w, h, 2)

        var bx0 = w; var by0 = h; var bx1 = -1; var by1 = -1
        for (y in 0 until h) for (x in 0 until w) if (hole[y * w + x]) {
            if (x < bx0) bx0 = x
            if (x > bx1) bx1 = x
            if (y < by0) by0 = y
            if (y > by1) by1 = y
        }
        if (bx1 < 0) return image

        // Область пошуку: дірка + поле навколо (звідки беремо текстуру)
        val margin = max(24, (max(bx1 - bx0, by1 - by0) * 0.9f).toInt())
        val rx0 = max(0, bx0 - margin)
        val ry0 = max(0, by0 - margin)
        val rx1 = min(w - 1, bx1 + margin)
        val ry1 = min(h - 1, by1 + margin)
        val rw = rx1 - rx0 + 1
        val rh = ry1 - ry0 + 1

        val region = Level(rw, rh)
        for (y in 0 until rh) for (x in 0 until rw) {
            val p = image.pixels[(y + ry0) * w + x + rx0]
            val i = y * rw + x
            region.r[i] = ((p shr 16) and 0xFF).toFloat()
            region.g[i] = ((p shr 8) and 0xFF).toFloat()
            region.b[i] = (p and 0xFF).toFloat()
            region.hole[i] = hole[(y + ry0) * w + x + rx0]
        }
        if (region.hole.all { it }) return image // нема звідки брати текстуру

        // Завелика область — рахуємо на зменшеній копії
        val scale = if (rw * rh > MAX_REGION_PIXELS) sqrt(MAX_REGION_PIXELS.toFloat() / (rw * rh)) else 1f
        val work = if (scale < 1f) region.resized((rw * scale).toInt().coerceAtLeast(8), (rh * scale).toInt().coerceAtLeast(8)) else region

        val filled = solve(work, Random(seed))
        val result = if (scale < 1f) filled.resizedColorsOnly(rw, rh) else filled

        val out = if (inPlace) image else image.copy()
        for (y in 0 until rh) for (x in 0 until rw) {
            val i = y * rw + x
            if (!region.hole[i]) continue
            out.pixels[(y + ry0) * w + x + rx0] = PixelImage.argb(result.r[i], result.g[i], result.b[i])
        }
        return out
    }

    // ------------------------------------------------------------------ Внутрішнє

    private class Level(val w: Int, val h: Int) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val hole = BooleanArray(w * h)

        fun downsample(): Level {
            val nw = max(1, w / 2)
            val nh = max(1, h / 2)
            val o = Level(nw, nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
                var anyHole = false
                for (dy in 0..1) for (dx in 0..1) {
                    val xx = min(w - 1, x * 2 + dx)
                    val yy = min(h - 1, y * 2 + dy)
                    val i = yy * w + xx
                    if (hole[i]) { anyHole = true; continue }
                    sr += r[i]; sg += g[i]; sb += b[i]; n++
                }
                val j = y * nw + x
                o.hole[j] = anyHole
                if (n > 0) { o.r[j] = sr / n; o.g[j] = sg / n; o.b[j] = sb / n }
            }
            return o
        }

        /** Білінійне масштабування кольору; дірка — «хоч щось від дірки» (консервативно). */
        fun resized(nw: Int, nh: Int): Level {
            val o = Level(nw, nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                val fx = ((x + 0.5f) * w / nw - 0.5f).coerceIn(0f, w - 1f)
                val fy = ((y + 0.5f) * h / nh - 0.5f).coerceIn(0f, h - 1f)
                val x0 = fx.toInt(); val y0 = fy.toInt()
                val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
                val j = y * nw + x
                o.hole[j] = hole[y0 * w + x0] || hole[y0 * w + x1] || hole[y1 * w + x0] || hole[y1 * w + x1]
                o.r[j] = bilerp(r, fx, fy, x0, y0, x1, y1)
                o.g[j] = bilerp(g, fx, fy, x0, y0, x1, y1)
                o.b[j] = bilerp(b, fx, fy, x0, y0, x1, y1)
            }
            return o
        }

        fun resizedColorsOnly(nw: Int, nh: Int): Level = resized(nw, nh)

        fun bilerp(c: FloatArray, fx: Float, fy: Float, x0: Int, y0: Int, x1: Int, y1: Int): Float {
            val ax = fx - x0; val ay = fy - y0
            val top = c[y0 * w + x0] + (c[y0 * w + x1] - c[y0 * w + x0]) * ax
            val bot = c[y1 * w + x0] + (c[y1 * w + x1] - c[y1 * w + x0]) * ax
            return top + (bot - top) * ay
        }
    }

    private fun solve(level0: Level, rnd: Random): Level {
        // Піраміда: зменшуємо, поки дірка не стане маленькою
        val pyramid = ArrayList<Level>()
        pyramid += level0
        while (true) {
            val cur = pyramid.last()
            if (min(cur.w, cur.h) < 4 * (2 * P + 1)) break
            if (holeExtent(cur) <= 12) break
            pyramid += cur.downsample()
        }

        var prevNx: IntArray? = null
        var prevNy: IntArray? = null
        var prevW = 0

        for (li in pyramid.indices.reversed()) {
            val L = pyramid[li]
            val n = L.w * L.h
            if (li == pyramid.lastIndex) {
                diffuseFill(L)
            } else {
                // Початкове наближення — з грубішого рівня
                val C = pyramid[li + 1]
                for (y in 0 until L.h) for (x in 0 until L.w) {
                    val i = y * L.w + x
                    if (!L.hole[i]) continue
                    val cx = min(C.w - 1, x / 2)
                    val cy = min(C.h - 1, y / 2)
                    val j = cy * C.w + cx
                    L.r[i] = C.r[j]; L.g[i] = C.g[j]; L.b[i] = C.b[j]
                }
            }

            val dilated = dilate(L.hole, L.w, L.h, P)
            // Допустимі центри джерела: весь патч відомий і в межах зображення
            val valid = BooleanArray(n)
            val validList = ArrayList<Int>()
            for (y in P until L.h - P) for (x in P until L.w - P) {
                val i = y * L.w + x
                if (!dilated[i]) { valid[i] = true; validList += i }
            }
            if (validList.isEmpty()) {
                // Надто мало контексту на цьому рівні — лишаємо дифузію
                prevNx = null; prevNy = null
                continue
            }
            // Цільові центри: патчі, що зачіпають дірку
            val targets = ArrayList<Int>()
            for (i in 0 until n) if (dilated[i]) targets += i

            val nx = IntArray(n) { -1 }
            val ny = IntArray(n) { -1 }
            val dist = FloatArray(n) { Float.MAX_VALUE }

            for (t in targets) {
                val x = t % L.w
                val y = t / L.w
                var cx = -1; var cy = -1
                if (prevNx != null && prevNy != null) {
                    val pw = prevW
                    val px = min(pw - 1, x / 2)
                    val py = y / 2
                    val pi = py * pw + px
                    if (pi < prevNx.size && prevNx[pi] >= 0) {
                        cx = prevNx[pi] * 2 + (x and 1)
                        cy = prevNy[pi] * 2 + (y and 1)
                    }
                }
                if (cx < 0 || cy < 0 || cx >= L.w || cy >= L.h || !valid[cy * L.w + cx]) {
                    val v = validList[rnd.nextInt(validList.size)]
                    cx = v % L.w; cy = v / L.w
                }
                nx[t] = cx; ny[t] = cy
                dist[t] = patchDist(L, x, y, cx, cy, Float.MAX_VALUE)
            }

            val iters = when {
                li == pyramid.lastIndex -> 10
                li == 0 -> if (n > 400_000) 2 else 3
                else -> 6
            }
            val maxR = max(L.w, L.h)
            repeat(iters) { it ->
                val forward = it % 2 == 0
                val order = if (forward) targets.indices else targets.indices.reversed()
                val step = if (forward) -1 else 1
                for (k in order) {
                    val t = targets[k]
                    val x = t % L.w
                    val y = t / L.w
                    // Поширення від сусідів
                    for (dir in 0..1) {
                        val qx = if (dir == 0) x + step else x
                        val qy = if (dir == 1) y + step else y
                        if (qx < 0 || qy < 0 || qx >= L.w || qy >= L.h) continue
                        val q = qy * L.w + qx
                        if (nx[q] < 0) continue
                        val cx = nx[q] - (qx - x)
                        val cy = ny[q] - (qy - y)
                        if (cx < 0 || cy < 0 || cx >= L.w || cy >= L.h) continue
                        if (!valid[cy * L.w + cx]) continue
                        val d = patchDist(L, x, y, cx, cy, dist[t])
                        if (d < dist[t]) { dist[t] = d; nx[t] = cx; ny[t] = cy }
                    }
                    // Випадковий пошук зі спадним радіусом
                    var rad = maxR
                    while (rad >= 1) {
                        val cx = nx[t] + rnd.nextInt(-rad, rad + 1)
                        val cy = ny[t] + rnd.nextInt(-rad, rad + 1)
                        if (cx >= 0 && cy >= 0 && cx < L.w && cy < L.h && valid[cy * L.w + cx]) {
                            val d = patchDist(L, x, y, cx, cy, dist[t])
                            if (d < dist[t]) { dist[t] = d; nx[t] = cx; ny[t] = cy }
                        }
                        rad /= 2
                    }
                }
                vote(L, targets, nx, ny, dist)
                // Відстані застаріли після голосування — перераховуємо
                for (t in targets) dist[t] = patchDist(L, t % L.w, t / L.w, nx[t], ny[t], Float.MAX_VALUE)
            }
            prevNx = nx; prevNy = ny; prevW = L.w
        }
        return level0
    }

    /** SSD між патчами (x,y) і (sx,sy), нормоване на кількість пікселів; з раннім виходом. */
    private fun patchDist(L: Level, x: Int, y: Int, sx: Int, sy: Int, cutoff: Float): Float {
        var sum = 0f
        var cnt = 0
        val w = L.w
        for (dy in -P..P) {
            val ty = y + dy
            if (ty < 0 || ty >= L.h) continue
            val syy = sy + dy
            for (dx in -P..P) {
                val tx = x + dx
                if (tx < 0 || tx >= w) continue
                val a = ty * w + tx
                val b = syy * w + sx + dx
                val dr = L.r[a] - L.r[b]
                val dg = L.g[a] - L.g[b]
                val db = L.b[a] - L.b[b]
                sum += dr * dr + dg * dg + db * db
                cnt++
            }
            if (cnt > 0 && sum / (2 * P + 1) / (2 * P + 1) > cutoff) return Float.MAX_VALUE
        }
        return if (cnt == 0) Float.MAX_VALUE else sum / cnt
    }

    /** Кожен піксель дірки = зважене середнє відповідних пікселів усіх патчів, що його покривають. */
    private fun vote(L: Level, targets: List<Int>, nx: IntArray, ny: IntArray, dist: FloatArray) {
        val n = L.w * L.h
        val ar = FloatArray(n); val ag = FloatArray(n); val ab = FloatArray(n); val aw = FloatArray(n)
        // Масштаб ваг — медіанна відстань
        var sigma = 0f
        run {
            val sample = FloatArray(min(targets.size, 512)) { dist[targets[it * targets.size / min(targets.size, 512)]] }
            sample.sort()
            sigma = max(1f, sample[sample.size / 2])
        }
        for (t in targets) {
            val x = t % L.w
            val y = t / L.w
            val sx = nx[t]
            val sy = ny[t]
            if (sx < 0) continue
            val wgt = 1f / (1f + dist[t] / sigma)
            for (dy in -P..P) {
                val ty = y + dy
                if (ty < 0 || ty >= L.h) continue
                for (dx in -P..P) {
                    val tx = x + dx
                    if (tx < 0 || tx >= L.w) continue
                    val a = ty * L.w + tx
                    if (!L.hole[a]) continue
                    val b = (sy + dy) * L.w + sx + dx
                    ar[a] += L.r[b] * wgt; ag[a] += L.g[b] * wgt; ab[a] += L.b[b] * wgt; aw[a] += wgt
                }
            }
        }
        for (i in 0 until n) if (L.hole[i] && aw[i] > 0f) {
            L.r[i] = ar[i] / aw[i]; L.g[i] = ag[i] / aw[i]; L.b[i] = ab[i] / aw[i]
        }
    }

    /** «Цибулинне» заповнення: шар за шаром від країв дірки до центру середнім відомих сусідів. */
    private fun diffuseFill(L: Level) {
        val known = BooleanArray(L.w * L.h) { !L.hole[it] }
        var remaining = known.count { !it }
        if (remaining == L.w * L.h) return
        val next = ArrayList<Int>()
        while (remaining > 0) {
            next.clear()
            for (y in 0 until L.h) for (x in 0 until L.w) {
                val i = y * L.w + x
                if (known[i]) continue
                var sr = 0f; var sg = 0f; var sb = 0f; var c = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= L.w || yy >= L.h) continue
                    val j = yy * L.w + xx
                    if (!known[j]) continue
                    sr += L.r[j]; sg += L.g[j]; sb += L.b[j]; c++
                }
                if (c > 0) { L.r[i] = sr / c; L.g[i] = sg / c; L.b[i] = sb / c; next += i }
            }
            if (next.isEmpty()) break
            for (i in next) known[i] = true
            remaining -= next.size
        }
    }

    private fun holeExtent(L: Level): Int {
        var x0 = L.w; var x1 = -1; var y0 = L.h; var y1 = -1
        for (y in 0 until L.h) for (x in 0 until L.w) if (L.hole[y * L.w + x]) {
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        return if (x1 < 0) 0 else max(x1 - x0, y1 - y0) + 1
    }

    /** Квадратна дилатація (роздільна: спершу рядки, потім стовпці). */
    fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return src.copyOf()
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            var last = -1_000_000
            for (x in 0 until w) { if (src[y * w + x]) last = x; if (x - last <= r) tmp[y * w + x] = true }
            last = 1_000_000
            for (x in w - 1 downTo 0) { if (src[y * w + x]) last = x; if (last - x <= r) tmp[y * w + x] = true }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var last = -1_000_000
            for (y in 0 until h) { if (tmp[y * w + x]) last = y; if (y - last <= r) out[y * w + x] = true }
            last = 1_000_000
            for (y in h - 1 downTo 0) { if (tmp[y * w + x]) last = y; if (last - y <= r) out[y * w + x] = true }
        }
        return out
    }
}
