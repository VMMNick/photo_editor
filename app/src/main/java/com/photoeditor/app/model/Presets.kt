package com.photoeditor.app.model

/** Ключі категорій (підписи — у ресурсах). */
object PresetCategory {
    const val BASIC = "basic"
    const val CINEMA = "cinema"
    const val VINTAGE = "vintage"
    const val BW = "bw"
    const val MOOD = "mood"
    const val MINE = "mine"
}

/**
 * Фільтр = «дельта» налаштувань, що додається поверх ручних із інтенсивністю 0..1.
 * Тому фільтр не затирає зроблені вручну корекції і його можна будь-коли прибрати.
 */
data class Preset(
    val id: String,
    /** Для вбудованих — запасна назва; показується перекладена з ресурсів за id. */
    val name: String,
    /** Ключ з [PresetCategory]. */
    val category: String,
    val delta: Adjustments,
    val builtIn: Boolean = true,
)

object Presets {

    private fun hsl(vararg pairs: Pair<HslBand, HslAdjust>): List<HslAdjust> {
        val list = MutableList(HslBand.entries.size) { HslAdjust() }
        for ((b, a) in pairs) list[b.ordinal] = a
        return list
    }

    val builtIn: List<Preset> = listOf(
        // ---------- Базові
        Preset("natural", "Natural", "basic", Adjustments(contrast = 0.08f, vibrance = 0.15f, shadows = 0.1f, highlights = -0.1f)),
        Preset("vivid", "Vivid", "basic", Adjustments(contrast = 0.2f, vibrance = 0.35f, saturation = 0.1f, blacks = -0.08f, whites = 0.08f)),
        Preset("soft", "Soft", "basic", Adjustments(contrast = -0.2f, highlights = -0.25f, shadows = 0.25f, fade = 0.12f, vibrance = 0.05f)),

        // ---------- Кіно
        Preset(
            "teal_orange", "Cinema: teal & orange", "cinema",
            Adjustments(
                contrast = 0.18f, highlights = -0.2f, shadows = 0.1f, saturation = -0.05f, vibrance = 0.1f,
                hsl = hsl(
                    HslBand.ORANGE to HslAdjust(hue = -0.05f, sat = 0.15f),
                    HslBand.BLUE to HslAdjust(hue = -0.25f, sat = 0.1f),
                    HslBand.AQUA to HslAdjust(hue = 0.1f, sat = 0.1f),
                    HslBand.GREEN to HslAdjust(hue = 0.3f, sat = -0.3f),
                ),
                grading = ColorGrading(
                    shadows = GradeWheel(190f, 0.35f, -0.05f),
                    highlights = GradeWheel(35f, 0.3f, 0f),
                ),
                vignette = -0.2f,
            )
        ),
        Preset(
            "blockbuster", "Blockbuster", "cinema",
            Adjustments(
                contrast = 0.35f, blacks = -0.15f, highlights = -0.3f, saturation = -0.15f, temperature = 0.05f,
                grading = ColorGrading(
                    shadows = GradeWheel(210f, 0.4f, -0.08f),
                    midtones = GradeWheel(40f, 0.1f, 0f),
                    highlights = GradeWheel(45f, 0.25f, 0.03f),
                ),
                vignette = -0.3f, grain = 0.1f,
            )
        ),
        Preset(
            "noir", "Noir", "cinema",
            Adjustments(
                saturation = -1f, contrast = 0.5f, blacks = -0.2f, highlights = -0.15f, whites = 0.1f,
                vignette = -0.45f, grain = 0.25f,
            )
        ),
        Preset(
            "night_city", "Night city", "cinema",
            Adjustments(
                exposure = -0.15f, contrast = 0.2f, temperature = -0.25f, tint = 0.1f, vibrance = 0.2f,
                hsl = hsl(
                    HslBand.PURPLE to HslAdjust(sat = 0.3f),
                    HslBand.MAGENTA to HslAdjust(sat = 0.3f, lum = 0.1f),
                    HslBand.BLUE to HslAdjust(sat = 0.2f),
                ),
                grading = ColorGrading(shadows = GradeWheel(240f, 0.3f, 0f), highlights = GradeWheel(320f, 0.2f, 0f)),
                vignette = -0.25f,
            )
        ),

        // ---------- Плівка / вінтаж
        Preset(
            "vintage", "Vintage", "vintage",
            Adjustments(
                contrast = -0.1f, saturation = -0.25f, temperature = 0.2f, fade = 0.3f,
                grading = ColorGrading(shadows = GradeWheel(30f, 0.25f, 0.05f), highlights = GradeWheel(50f, 0.2f, 0f)),
                vignette = -0.35f, grain = 0.3f,
            )
        ),
        Preset(
            "warm_film", "Warm film", "vintage",
            Adjustments(
                contrast = 0.1f, temperature = 0.18f, tint = 0.04f, fade = 0.15f, vibrance = 0.1f,
                hsl = hsl(HslBand.GREEN to HslAdjust(hue = -0.2f, sat = -0.2f), HslBand.ORANGE to HslAdjust(sat = 0.1f)),
                grading = ColorGrading(shadows = GradeWheel(20f, 0.15f, 0f), highlights = GradeWheel(45f, 0.18f, 0f)),
                grain = 0.2f,
            )
        ),
        Preset(
            "cold_film", "Cold film", "vintage",
            Adjustments(
                contrast = 0.12f, temperature = -0.18f, saturation = -0.12f, fade = 0.18f,
                grading = ColorGrading(shadows = GradeWheel(200f, 0.2f, 0f), highlights = GradeWheel(180f, 0.08f, 0f)),
                grain = 0.2f,
            )
        ),
        Preset(
            "matte", "Matte", "vintage",
            Adjustments(contrast = -0.15f, fade = 0.4f, saturation = -0.1f, highlights = -0.15f)
        ),
        Preset(
            "sepia", "Sepia", "vintage",
            Adjustments(
                saturation = -1f, contrast = 0.1f, fade = 0.15f,
                grading = ColorGrading(
                    shadows = GradeWheel(30f, 0.35f, 0f),
                    midtones = GradeWheel(35f, 0.3f, 0f),
                    highlights = GradeWheel(45f, 0.2f, 0f),
                ),
                vignette = -0.3f, grain = 0.15f,
            )
        ),

        // ---------- Ч/Б
        Preset("bw", "B&W classic", "bw", Adjustments(saturation = -1f, contrast = 0.15f)),
        Preset("bw_soft", "B&W soft", "bw", Adjustments(saturation = -1f, contrast = -0.15f, fade = 0.2f, shadows = 0.2f)),
        Preset("bw_hard", "B&W contrast", "bw", Adjustments(saturation = -1f, contrast = 0.6f, blacks = -0.3f, whites = 0.25f, grain = 0.15f)),

        // ---------- Настрій
        Preset(
            "golden_hour", "Golden hour", "mood",
            Adjustments(
                temperature = 0.35f, tint = 0.05f, shadows = 0.15f, highlights = -0.15f, vibrance = 0.25f,
                grading = ColorGrading(highlights = GradeWheel(40f, 0.3f, 0.03f), midtones = GradeWheel(30f, 0.1f, 0f)),
                vignette = -0.2f,
            )
        ),
        Preset(
            "pastel", "Pastel", "mood",
            Adjustments(
                exposure = 0.2f, contrast = -0.3f, saturation = -0.2f, fade = 0.2f, highlights = -0.2f,
                grading = ColorGrading(shadows = GradeWheel(280f, 0.15f, 0.05f), highlights = GradeWheel(20f, 0.12f, 0f)),
            )
        ),
        Preset(
            "moody_green", "Forest mood", "mood",
            Adjustments(
                exposure = -0.1f, contrast = 0.15f, saturation = -0.1f,
                hsl = hsl(
                    HslBand.GREEN to HslAdjust(hue = -0.25f, sat = -0.35f, lum = -0.2f),
                    HslBand.YELLOW to HslAdjust(hue = -0.2f, sat = -0.2f),
                    HslBand.ORANGE to HslAdjust(sat = 0.1f),
                ),
                grading = ColorGrading(shadows = GradeWheel(160f, 0.2f, 0f)),
                vignette = -0.3f,
            )
        ),
        Preset(
            "portrait", "Portrait", "mood",
            Adjustments(
                exposure = 0.1f, contrast = -0.05f, shadows = 0.2f, highlights = -0.1f, vibrance = 0.1f,
                hsl = hsl(HslBand.ORANGE to HslAdjust(sat = -0.1f, lum = 0.15f), HslBand.RED to HslAdjust(sat = -0.1f, lum = 0.05f)),
                temperature = 0.05f, vignette = -0.15f,
            )
        ),
    )

    fun find(id: String, user: List<Preset> = emptyList()): Preset? =
        builtIn.firstOrNull { it.id == id } ?: user.firstOrNull { it.id == id }
}
