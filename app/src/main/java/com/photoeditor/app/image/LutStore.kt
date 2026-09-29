package com.photoeditor.app.image

import android.content.Context
import android.net.Uri
import com.photoeditor.app.core.CubeLut
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Імпортований LUT у списку фільтрів. */
data class LutInfo(val id: String, val title: String)

/**
 * Імпортовані 3D LUT. .cube розбирається один раз при імпорті й зберігається компактно (RGBA8),
 * тож відкриття LUT на 65³ вузлів — мілісекунди, а не секунди розбору тексту.
 */
class LutStore(context: Context) {
    private val dir = File(context.filesDir, "luts")
    private val index get() = File(dir, "index.json")

    fun list(): List<LutInfo> = runCatching {
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> LutInfo(o.getString("id"), o.getString("title")) } }
    }.getOrDefault(emptyList())

    /** @throws IllegalArgumentException для некоректного або завеликого файлу */
    fun import(context: Context, uri: Uri, displayName: String): LutInfo {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = input.readBytes()
            require(buf.size <= MAX_FILE_BYTES) { "File is too large" }
            buf
        } ?: error("openInputStream returned null")
        val lut = CubeLut.parse(bytes.toString(Charsets.UTF_8), displayName.substringBeforeLast('.'))
        dir.mkdirs()
        val info = LutInfo("lut_${System.currentTimeMillis()}", lut.title)
        write(File(dir, "${info.id}.bin"), lut)
        saveIndex(list() + info)
        return info
    }

    fun load(id: String): CubeLut? = runCatching { read(File(dir, "$id.bin")) }.getOrNull()

    fun delete(id: String) {
        File(dir, "$id.bin").delete()
        saveIndex(list().filter { it.id != id })
    }

    private fun saveIndex(items: List<LutInfo>) {
        dir.mkdirs()
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title)) }
        index.writeText(arr.toString())
    }

    private fun write(f: File, lut: CubeLut) {
        DataOutputStream(f.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(lut.title)
            out.writeInt(lut.size)
            out.write(lut.toRgba8())
        }
    }

    private fun read(f: File): CubeLut = DataInputStream(f.inputStream().buffered()).use { inp ->
        require(inp.readInt() == MAGIC) { "Bad LUT file" }
        val title = inp.readUTF()
        val size = inp.readInt()
        val n = size * size * size
        val rgba = ByteArray(n * 4)
        inp.readFully(rgba)
        val data = FloatArray(n * 3)
        for (i in 0 until n) for (c in 0..2) data[i * 3 + c] = (rgba[i * 4 + c].toInt() and 0xFF) / 255f
        CubeLut(title, size, data)
    }

    private companion object {
        const val MAGIC = 0x4C555431 // "LUT1"
        const val MAX_FILE_BYTES = 20 * 1024 * 1024
    }
}
