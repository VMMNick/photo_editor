package com.photoeditor.app.model

import com.photoeditor.app.core.AlphaMap

/*
 * Уся модель редагування — незмінні data-класи.
 * Кожна зміна створює новий EditState, тому undo/redo — це просто стек знімків.
 *
 * Системи координат:
 *  - "uv джерела": (0..1, 0..1) у вихідному (неповернутому) зображенні, (0,0) — лівий верхній кут.
 *  - "квадратні одиниці": пікселі / max(W, H). Використовуються для радіусів та еліпсів,
 *    щоб коло лишалося колом незалежно від пропорцій кадру.
 *  - "кадр": зображення після повороту на 90°, віддзеркалення, вирівнювання та перспективи.
 *    Обрізка (crop) задається в нормалізованих координатах кадру.
 */

// ---------------------------------------------------------------- Світло і колір

/** Корекція одного кольорового діапазону HSL. Усі значення -1..1. */
data class HslAdjust(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f) {
    val isNeutral get() = hue == 0f && sat == 0f && lum == 0f
}

/** Назви діапазонів — у ресурсах (ui/Labels.kt), тут лише центр відтінку та колір кружечка. */
enum class HslBand(val centerDeg: Float, val argb: Long) {
    RED(0f, 0xFFE5484D),
    ORANGE(30f, 0xFFF76B15),
    YELLOW(60f, 0xFFFFD60A),
    GREEN(120f, 0xFF30A46C),
    AQUA(180f, 0xFF12A594),
    BLUE(225f, 0xFF0090FF),
    PURPLE(270f, 0xFF8E4EC6),
    MAGENTA(315f, 0xFFD6409F),
}

/** Колесо тонування: відтінок 0..360°, насиченість 0..1, яскравість -1..1. */
data class GradeWheel(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f) {
    val isNeutral get() = sat == 0f && lum == 0f
}

data class ColorGrading(
    val shadows: GradeWheel = GradeWheel(),
    val midtones: GradeWheel = GradeWheel(),
    val highlights: GradeWheel = GradeWheel(),
    /** Наскільки широко перекриваються зони (0..1). */
    val blending: Float = 0.5f,
    /** Зсув межі тіні/світло (-1..1). */
    val balance: Float = 0f,
)

// ---------------------------------------------------------------- Криві

/** Опорна точка кривої: вхід x → вихід y, обидва 0..1. */
data class CurvePoint(val x: Float, val y: Float)

enum class CurveChannel { RGB, RED, GREEN, BLUE }

/**
 * Тонова крива: відсортовані за x опорні точки (мінімум дві — кінці).
 * Між точками — монотонний кубічний сплайн (core/CurveMath), тож крива не «перехльостує».
 */
data class ToneCurve(val points: List<CurvePoint> = IDENTITY) {
    val isIdentity get() = points.all { kotlin.math.abs(it.x - it.y) < 1e-4f }

    /** Проміжна крива між прямою (k = 0) і цією (k = 1) — для інтенсивності фільтра. */
    fun scaled(k: Float): ToneCurve = if (k == 1f) this else ToneCurve(points.map { CurvePoint(it.x, it.x + (it.y - it.x) * k) })

    companion object {
        val IDENTITY = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    }
}

/** Загальна крива (яскравість усіх каналів) + окремі криві червоного, зеленого, синього. */
data class Curves(
    val rgb: ToneCurve = ToneCurve(),
    val red: ToneCurve = ToneCurve(),
    val green: ToneCurve = ToneCurve(),
    val blue: ToneCurve = ToneCurve(),
) {
    val isIdentity get() = rgb.isIdentity && red.isIdentity && green.isIdentity && blue.isIdentity

    operator fun get(ch: CurveChannel): ToneCurve = when (ch) {
        CurveChannel.RGB -> rgb
        CurveChannel.RED -> red
        CurveChannel.GREEN -> green
        CurveChannel.BLUE -> blue
    }

    fun with(ch: CurveChannel, c: ToneCurve): Curves = when (ch) {
        CurveChannel.RGB -> copy(rgb = c)
        CurveChannel.RED -> copy(red = c)
        CurveChannel.GREEN -> copy(green = c)
        CurveChannel.BLUE -> copy(blue = c)
    }

    fun scaled(k: Float) = Curves(rgb.scaled(k), red.scaled(k), green.scaled(k), blue.scaled(k))
}

/** Глобальні корекції. Експозиція в EV (-4..4), решта -1..1 (grain/fade 0..1). */
data class Adjustments(
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val hsl: List<HslAdjust> = List(HslBand.entries.size) { HslAdjust() },
    val grading: ColorGrading = ColorGrading(),
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val fade: Float = 0f,
    val curves: Curves = Curves(),
    /** Деталізація: різкість і шумозаглушення 0..1, чіткість і димка -1..1. */
    val sharpen: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val noise: Float = 0f,
    val colorNoise: Float = 0f,
    /** Корекція об'єктива: віньєтування 0..1, хроматична аберація -1..1. */
    val lensVignette: Float = 0f,
    val chromatic: Float = 0f,
    /** Імпортований 3D LUT (id у LutStore) та його інтенсивність 0..1. */
    val lut: String? = null,
    val lutAmount: Float = 1f,
) {
    fun withHsl(band: HslBand, adj: HslAdjust): Adjustments =
        copy(hsl = hsl.toMutableList().also { it[band.ordinal] = adj })

    /** this + d * k — так фільтр накладається поверх ручних налаштувань з інтенсивністю k. */
    fun plusScaled(d: Adjustments, k: Float): Adjustments {
        if (k == 0f) return this
        fun f(a: Float, b: Float, lo: Float = -1f, hi: Float = 1f) = (a + b * k).coerceIn(lo, hi)
        return Adjustments(
            exposure = f(exposure, d.exposure, -4f, 4f),
            brightness = f(brightness, d.brightness),
            contrast = f(contrast, d.contrast),
            highlights = f(highlights, d.highlights),
            shadows = f(shadows, d.shadows),
            whites = f(whites, d.whites),
            blacks = f(blacks, d.blacks),
            temperature = f(temperature, d.temperature),
            tint = f(tint, d.tint),
            saturation = f(saturation, d.saturation),
            vibrance = f(vibrance, d.vibrance),
            hsl = hsl.indices.map { i ->
                HslAdjust(
                    f(hsl[i].hue, d.hsl[i].hue), f(hsl[i].sat, d.hsl[i].sat), f(hsl[i].lum, d.hsl[i].lum)
                )
            },
            grading = ColorGrading(
                shadows = blendWheel(grading.shadows, d.grading.shadows, k),
                midtones = blendWheel(grading.midtones, d.grading.midtones, k),
                highlights = blendWheel(grading.highlights, d.grading.highlights, k),
                blending = grading.blending,
                balance = f(grading.balance, d.grading.balance),
            ),
            vignette = f(vignette, d.vignette),
            grain = f(grain, d.grain, 0f, 1f),
            fade = f(fade, d.fade, 0f, 1f),
            sharpen = f(sharpen, d.sharpen, 0f, 1f),
            clarity = f(clarity, d.clarity),
            dehaze = f(dehaze, d.dehaze),
            noise = f(noise, d.noise, 0f, 1f),
            colorNoise = f(colorNoise, d.colorNoise, 0f, 1f),
            lensVignette = f(lensVignette, d.lensVignette, 0f, 1f),
            chromatic = f(chromatic, d.chromatic),
            lut = lut ?: d.lut,
            lutAmount = if (lut != null) lutAmount else d.lutAmount * k,
            // Криві не додаються: ручна крива має пріоритет, крива фільтра діє, лише якщо ручної немає
            curves = if (d.curves.isIdentity || !curves.isIdentity) curves else d.curves.scaled(k),
        )
    }

    companion object {
        /** Колеса додаються як вектори (hue, sat) на колірному колі. */
        fun blendWheel(a: GradeWheel, b: GradeWheel, k: Float): GradeWheel {
            val ax = a.sat * kotlin.math.cos(Math.toRadians(a.hue.toDouble())).toFloat()
            val ay = a.sat * kotlin.math.sin(Math.toRadians(a.hue.toDouble())).toFloat()
            val bx = b.sat * k * kotlin.math.cos(Math.toRadians(b.hue.toDouble())).toFloat()
            val by = b.sat * k * kotlin.math.sin(Math.toRadians(b.hue.toDouble())).toFloat()
            val x = ax + bx
            val y = ay + by
            val sat = kotlin.math.sqrt(x * x + y * y).coerceIn(0f, 1f)
            var hue = Math.toDegrees(kotlin.math.atan2(y, x).toDouble()).toFloat()
            if (hue < 0) hue += 360f
            return GradeWheel(if (sat < 1e-4f) a.hue else hue, sat, (a.lum + b.lum * k).coerceIn(-1f, 1f))
        }
    }
}

// ---------------------------------------------------------------- Геометрія

data class NRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
    val w get() = r - l
    val h get() = b - t
    val isFull get() = l <= 0f && t <= 0f && r >= 1f && b >= 1f

    companion object {
        val FULL = NRect()
    }
}

/** ratio: ширина/висота; null — вільна рамка; -1 — пропорція оригіналу. */
enum class AspectRatio(val ratio: Float?) {
    FREE(null),
    ORIGINAL(-1f),
    SQUARE(1f),
    INSTA_PORTRAIT(4f / 5f),
    STORIES(9f / 16f),
    WIDE(16f / 9f),
    PHOTO_3_2(3f / 2f),
    PRINT_10x15(2f / 3f),
    PRINT_13x18(5f / 7f),
    A4(1f / 1.4142f),
}

data class Geometry(
    /** Кількість поворотів на 90° за годинниковою стрілкою (0..3). */
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Вирівнювання горизонту в градусах (-45..45). */
    val straighten: Float = 0f,
    /** Перспектива (-1..1). */
    val perspectiveV: Float = 0f,
    val perspectiveH: Float = 0f,
    val crop: NRect = NRect.FULL,
    val aspect: AspectRatio = AspectRatio.FREE,
    /** Корекція дисторсії об'єктива (-1..1): > 0 прибирає «бочку», < 0 — «подушку». */
    val distortion: Float = 0f,
) {
    val isIdentity
        get() = quarterTurns == 0 && !flipH && !flipV && straighten == 0f &&
            perspectiveV == 0f && perspectiveH == 0f && crop.isFull && distortion == 0f
}

// ---------------------------------------------------------------- Маски

/** Локальні корекції, що застосовуються всередині маски. Експозиція -2..2 EV, решта -1..1. */
data class LocalAdjust(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
)

/** Точка мазка пензля в uv джерела. */
data class StrokePoint(val u: Float, val v: Float)

/** Мазок: радіус у квадратних одиницях, м'якість 0..1, erase=true стирає маску. */
data class BrushStroke(
    val points: List<StrokePoint>,
    val radius: Float,
    val hardness: Float = 0.5f,
    val erase: Boolean = false,
)

enum class MaskKind { LINEAR, RADIAL, BRUSH, SUBJECT }

data class Mask(
    val id: Long,
    val kind: MaskKind,
    /** LINEAR: (x0,y0) — повний ефект, (x1,y1) — нуль. Координати uv джерела. */
    val x0: Float = 0.5f, val y0: Float = 0.2f,
    val x1: Float = 0.5f, val y1: Float = 0.5f,
    /** RADIAL: центр (uv), радіуси (квадратні одиниці), кут (рад), розмиття межі 0..1. */
    val cx: Float = 0.5f, val cy: Float = 0.5f,
    val rx: Float = 0.25f, val ry: Float = 0.25f,
    val angle: Float = 0f,
    val feather: Float = 0.5f,
    val strokes: List<BrushStroke> = emptyList(),
    /** SUBJECT: карта впевненості сегментації в uv джерела. */
    val subject: AlphaMap? = null,
    val invert: Boolean = false,
    val adjust: LocalAdjust = LocalAdjust(),
    /** Уточнення за яскравістю або кольором (діє поверх форми маски, після інверсії). */
    val range: MaskRange = MaskRange(),
)

enum class RangeMode { NONE, LUMINANCE, COLOR }

/**
 * Діапазон маски: лишає ефект лише там, де яскравість джерела в [lo, hi]
 * або відтінок у межах hueWidth градусів від hue. smooth — м'якість переходу 0..1.
 */
data class MaskRange(
    val mode: RangeMode = RangeMode.NONE,
    val lo: Float = 0f,
    val hi: Float = 1f,
    val hue: Float = 30f,
    val hueWidth: Float = 30f,
    val smooth: Float = 0.3f,
)

// ---------------------------------------------------------------- Ретуш

sealed interface RetouchOp

/** Точковий коректор: центр (uv), радіус (квадратні одиниці). */
data class HealOp(val u: Float, val v: Float, val radius: Float) : RetouchOp

/** Штамп: мазок, що копіює пікселі з місця зі зсувом (du, dv) в uv. */
data class CloneOp(val stroke: BrushStroke, val du: Float, val dv: Float) : RetouchOp

/** AI-ластик: область видалення, заповнюється синтезованою текстурою. */
data class EraseOp(val mask: AlphaMap) : RetouchOp

// ---------------------------------------------------------------- Загальний стан

data class EditState(
    val adjust: Adjustments = Adjustments(),
    val geometry: Geometry = Geometry(),
    val masks: List<Mask> = emptyList(),
    val retouch: List<RetouchOp> = emptyList(),
    val presetId: String? = null,
    val presetAmount: Float = 1f,
) {
    /** Налаштування, що реально йдуть у шейдер: ручні + фільтр з інтенсивністю. */
    fun effectiveAdjust(presetLookup: (String) -> Adjustments?): Adjustments {
        val p = presetId?.let(presetLookup) ?: return adjust
        return adjust.plusScaled(p, presetAmount)
    }
}
