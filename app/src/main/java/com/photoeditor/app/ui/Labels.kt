package com.photoeditor.app.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.photoeditor.app.R
import com.photoeditor.app.image.ExportSize
import com.photoeditor.app.model.AspectRatio
import com.photoeditor.app.model.HslBand
import com.photoeditor.app.model.Mask
import com.photoeditor.app.model.MaskKind
import com.photoeditor.app.model.Preset
import com.photoeditor.app.model.PresetCategory

/*
 * Модель (model/, core/) не знає про Android-ресурси — там лише стабільні ключі.
 * Тут вони перетворюються на локалізовані підписи (values/ — англійська, values-uk/ — українська).
 */

@StringRes
fun HslBand.labelRes(): Int = when (this) {
    HslBand.RED -> R.string.hsl_red
    HslBand.ORANGE -> R.string.hsl_orange
    HslBand.YELLOW -> R.string.hsl_yellow
    HslBand.GREEN -> R.string.hsl_green
    HslBand.AQUA -> R.string.hsl_aqua
    HslBand.BLUE -> R.string.hsl_blue
    HslBand.PURPLE -> R.string.hsl_purple
    HslBand.MAGENTA -> R.string.hsl_magenta
}

@StringRes
fun AspectRatio.labelRes(): Int = when (this) {
    AspectRatio.FREE -> R.string.aspect_free
    AspectRatio.ORIGINAL -> R.string.aspect_original
    AspectRatio.SQUARE -> R.string.aspect_square
    AspectRatio.INSTA_PORTRAIT -> R.string.aspect_insta
    AspectRatio.STORIES -> R.string.aspect_stories
    AspectRatio.WIDE -> R.string.aspect_wide
    AspectRatio.PHOTO_3_2 -> R.string.aspect_3_2
    AspectRatio.PRINT_10x15 -> R.string.aspect_print_10x15
    AspectRatio.PRINT_13x18 -> R.string.aspect_print_13x18
    AspectRatio.A4 -> R.string.aspect_a4
}

@StringRes
fun MaskKind.labelRes(): Int = when (this) {
    MaskKind.LINEAR -> R.string.mask_linear
    MaskKind.RADIAL -> R.string.mask_radial
    MaskKind.BRUSH -> R.string.mask_brush
    MaskKind.SUBJECT -> R.string.mask_subject
}

@Composable
fun maskName(m: Mask): String {
    val base = stringResource(m.kind.labelRes())
    return if (m.invert) stringResource(R.string.mask_inverted, base) else base
}

@StringRes
fun categoryLabelRes(key: String): Int? = when (key) {
    PresetCategory.BASIC -> R.string.cat_basic
    PresetCategory.CINEMA -> R.string.cat_cinema
    PresetCategory.VINTAGE -> R.string.cat_vintage
    PresetCategory.BW -> R.string.cat_bw
    PresetCategory.MOOD -> R.string.cat_mood
    PresetCategory.MINE -> R.string.cat_mine
    else -> null
}

@Composable
fun categoryLabel(key: String): String = categoryLabelRes(key)?.let { stringResource(it) } ?: key

private val PRESET_NAMES: Map<String, Int> = mapOf(
    "natural" to R.string.preset_natural,
    "vivid" to R.string.preset_vivid,
    "soft" to R.string.preset_soft,
    "teal_orange" to R.string.preset_teal_orange,
    "blockbuster" to R.string.preset_blockbuster,
    "noir" to R.string.preset_noir,
    "night_city" to R.string.preset_night_city,
    "vintage" to R.string.preset_vintage,
    "warm_film" to R.string.preset_warm_film,
    "cold_film" to R.string.preset_cold_film,
    "matte" to R.string.preset_matte,
    "sepia" to R.string.preset_sepia,
    "bw" to R.string.preset_bw,
    "bw_soft" to R.string.preset_bw_soft,
    "bw_hard" to R.string.preset_bw_hard,
    "golden_hour" to R.string.preset_golden_hour,
    "pastel" to R.string.preset_pastel,
    "moody_green" to R.string.preset_moody_green,
    "portrait" to R.string.preset_portrait,
)

/** Ресурс назви вбудованого пресета або null (для власних — назва, яку ввів користувач). */
@StringRes
fun presetNameRes(p: Preset): Int? = if (p.builtIn) PRESET_NAMES[p.id] else null

@Composable
fun presetName(p: Preset): String = presetNameRes(p)?.let { stringResource(it) } ?: p.name

@Composable
fun exportSizeLabel(s: ExportSize): String = when (s) {
    ExportSize.FULL -> stringResource(R.string.export_size_full)
    ExportSize.SOCIAL -> stringResource(R.string.export_size_social)
    else -> stringResource(R.string.export_size_px, s.maxSide)
}
