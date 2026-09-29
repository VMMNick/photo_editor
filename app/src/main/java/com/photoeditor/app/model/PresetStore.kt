package com.photoeditor.app.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Користувацькі пресети: JSON-файл у внутрішньому сховищі застосунку. */
class PresetStore(context: Context) {

    private val file = File(context.filesDir, "user_presets.json")

    fun load(): List<Preset> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Preset(
                id = o.getString("id"),
                name = o.getString("name"),
                category = PresetCategory.MINE,
                delta = adjustFromJson(o.getJSONObject("adjust")),
                builtIn = false,
            )
        }
    }.getOrDefault(emptyList())

    fun save(presets: List<Preset>) {
        val arr = JSONArray()
        presets.filter { !it.builtIn }.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("adjust", adjustToJson(p.delta)))
        }
        file.writeText(arr.toString())
    }

    companion object {
        fun adjustToJson(a: Adjustments): JSONObject = JSONObject().apply {
            put("exposure", a.exposure.toDouble())
            put("brightness", a.brightness.toDouble())
            put("contrast", a.contrast.toDouble())
            put("highlights", a.highlights.toDouble())
            put("shadows", a.shadows.toDouble())
            put("whites", a.whites.toDouble())
            put("blacks", a.blacks.toDouble())
            put("temperature", a.temperature.toDouble())
            put("tint", a.tint.toDouble())
            put("saturation", a.saturation.toDouble())
            put("vibrance", a.vibrance.toDouble())
            put("vignette", a.vignette.toDouble())
            put("grain", a.grain.toDouble())
            put("fade", a.fade.toDouble())
            put("sharpen", a.sharpen.toDouble())
            put("clarity", a.clarity.toDouble())
            put("dehaze", a.dehaze.toDouble())
            put("noise", a.noise.toDouble())
            put("colorNoise", a.colorNoise.toDouble())
            put("lensVignette", a.lensVignette.toDouble())
            put("chromatic", a.chromatic.toDouble())
            a.lut?.let { put("lut", it) }
            put("lutAmount", a.lutAmount.toDouble())
            put("hsl", JSONArray().apply {
                a.hsl.forEach { put(JSONArray().put(it.hue.toDouble()).put(it.sat.toDouble()).put(it.lum.toDouble())) }
            })
            fun wheel(w: GradeWheel) = JSONArray().put(w.hue.toDouble()).put(w.sat.toDouble()).put(w.lum.toDouble())
            put("grading", JSONObject().apply {
                put("s", wheel(a.grading.shadows))
                put("m", wheel(a.grading.midtones))
                put("h", wheel(a.grading.highlights))
                put("blending", a.grading.blending.toDouble())
                put("balance", a.grading.balance.toDouble())
            })
            if (!a.curves.isIdentity) put("curves", JSONObject().apply {
                CurveChannel.entries.forEach { ch ->
                    put(ch.name.lowercase(), JSONArray().apply {
                        a.curves[ch].points.forEach { put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
                    })
                }
            })
        }

        private fun curvesFromJson(o: JSONObject?): Curves {
            o ?: return Curves()
            var c = Curves()
            for (ch in CurveChannel.entries) {
                val arr = o.optJSONArray(ch.name.lowercase()) ?: continue
                val pts = (0 until arr.length()).map { i -> arr.getJSONArray(i).let { CurvePoint(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) } }
                if (pts.size >= 2) c = c.with(ch, ToneCurve(pts.sortedBy { it.x }))
            }
            return c
        }

        fun adjustFromJson(o: JSONObject): Adjustments {
            fun f(k: String) = o.optDouble(k, 0.0).toFloat()
            val hslArr = o.optJSONArray("hsl")
            val hsl = List(HslBand.entries.size) { i ->
                val e = hslArr?.optJSONArray(i)
                if (e == null) HslAdjust() else HslAdjust(e.optDouble(0).toFloat(), e.optDouble(1).toFloat(), e.optDouble(2).toFloat())
            }
            val g = o.optJSONObject("grading")
            fun wheel(k: String): GradeWheel {
                val e = g?.optJSONArray(k) ?: return GradeWheel()
                return GradeWheel(e.optDouble(0).toFloat(), e.optDouble(1).toFloat(), e.optDouble(2).toFloat())
            }
            return Adjustments(
                exposure = f("exposure"), brightness = f("brightness"), contrast = f("contrast"),
                highlights = f("highlights"), shadows = f("shadows"), whites = f("whites"), blacks = f("blacks"),
                temperature = f("temperature"), tint = f("tint"), saturation = f("saturation"), vibrance = f("vibrance"),
                hsl = hsl,
                grading = ColorGrading(
                    wheel("s"), wheel("m"), wheel("h"),
                    blending = g?.optDouble("blending", 0.5)?.toFloat() ?: 0.5f,
                    balance = g?.optDouble("balance", 0.0)?.toFloat() ?: 0f,
                ),
                vignette = f("vignette"), grain = f("grain"), fade = f("fade"),
                curves = curvesFromJson(o.optJSONObject("curves")),
                sharpen = f("sharpen"), clarity = f("clarity"), dehaze = f("dehaze"),
                noise = f("noise"), colorNoise = f("colorNoise"),
                lensVignette = f("lensVignette"), chromatic = f("chromatic"),
                lut = o.optString("lut").takeIf { it.isNotEmpty() },
                lutAmount = o.optDouble("lutAmount", 1.0).toFloat(),
            )
        }
    }
}
