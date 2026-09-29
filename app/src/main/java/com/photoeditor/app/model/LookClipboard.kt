package com.photoeditor.app.model

import android.content.Context
import org.json.JSONObject

/**
 * Скопійований «вигляд» фото: світло, колір, деталізація, криві, фільтр з інтенсивністю.
 * Маски, ретуш і кадрування прив'язані до конкретного кадру, тому не копіюються.
 */
data class Look(val adjust: Adjustments, val presetId: String?, val presetAmount: Float) {
    /** Стан для іншого фото: вигляд поверх нейтральної геометрії, без масок і ретушу. */
    fun toState(): EditState = EditState(adjust = adjust, presetId = presetId, presetAmount = presetAmount)

    /** Вставка у поточне фото: геометрія, маски й ретуш лишаються. */
    fun applyTo(s: EditState): EditState = s.copy(adjust = adjust, presetId = presetId, presetAmount = presetAmount)

    companion object {
        fun of(s: EditState) = Look(s.adjust, s.presetId, s.presetAmount)
    }
}

/** Буфер «вигляду», що переживає перезапуск застосунку. */
class LookClipboard(context: Context) {
    private val prefs = context.getSharedPreferences("look_clipboard", Context.MODE_PRIVATE)

    fun load(): Look? = runCatching {
        val raw = prefs.getString(KEY, null) ?: return null
        val o = JSONObject(raw)
        Look(
            PresetStore.adjustFromJson(o.getJSONObject("adjust")),
            o.optString("presetId").takeIf { it.isNotEmpty() },
            o.optDouble("presetAmount", 1.0).toFloat(),
        )
    }.getOrNull()

    fun save(look: Look) {
        val o = JSONObject()
            .put("adjust", PresetStore.adjustToJson(look.adjust))
            .put("presetId", look.presetId ?: "")
            .put("presetAmount", look.presetAmount.toDouble())
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private companion object {
        const val KEY = "look"
    }
}
