package com.photoeditor.app.image

import android.content.Context
import android.net.Uri
import com.photoeditor.app.core.AlphaMap
import com.photoeditor.app.core.MiniJson
import com.photoeditor.app.core.SessionCodec
import com.photoeditor.app.core.SessionMeta
import com.photoeditor.app.model.EditState
import java.io.File
import java.util.IdentityHashMap

/**
 * Сесія редагування на диску (filesDir/session):
 *  - source.<ext>  — робоча копія оригіналу. Не залежить від тимчасових дозволів на Uri
 *                    з галереї, тож фото можна перевідкрити після того, як Android вбив процес;
 *  - detail.jpg    — (для RAW з вбудованим прев'ю) JPEG для детальних тайлів;
 *  - state.json    — параметри редагування;
 *  - map_N.bin     — растрові маски (стиснуті), кожна пишеться один раз.
 *
 * Потокобезпечність: усі методи викликати з одного фонового потоку (ViewModel серіалізує).
 */
/** Відкладене редагування на головному екрані. */
data class RecentItem(val id: String, val name: String, val thumb: File?, val time: Long)

class SessionStore(context: Context) {

    private val dir = File(context.filesDir, "session")
    private val recentDir = File(context.filesDir, "recent")
    private val stateFile get() = File(dir, "state.json")

    /** Які карти вже записані на диск (за ідентичністю об'єкта) — щоб не переписувати їх щоразу. */
    private val writtenMaps = IdentityHashMap<AlphaMap, String>()
    private var mapCounter = 0

    class Restored(
        val meta: SessionMeta,
        val state: EditState,
        val source: File,
        val undo: List<EditState> = emptyList(),
        val redo: List<EditState> = emptyList(),
    )

    /** Копіює оригінал у папку сесії й повертає файл. Попередня сесія видаляється. */
    fun importSource(context: Context, uri: Uri, displayName: String): File {
        clear()
        dir.mkdirs()
        val ext = displayName.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
        val target = File(dir, if (ext.isEmpty()) "source" else "source.$ext")
        val tmp = File(dir, "source.tmp")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
        } ?: error("openInputStream returned null")
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }

    fun detailFile(): File = File(dir, "detail.jpg")

    /**
     * @param undo / redo — останні кроки історії; спільні з поточним станом карти пишуться один раз
     */
    fun save(meta: SessionMeta, state: EditState, undo: List<EditState> = emptyList(), redo: List<EditState> = emptyList()) {
        if (!dir.isDirectory) return // сесію вже закрили
        val enc = SessionCodec.encode(meta, state, undo, redo) { m ->
            writtenMaps.getOrPut(m) {
                val name = "map_${mapCounter++}.bin"
                atomicWrite(File(dir, name), SessionCodec.encodeMap(m))
                name
            }
        }
        // Спершу карти (вище), потім стан з їхніми іменами — одним атомарним записом
        atomicWrite(stateFile, enc.json.toByteArray(Charsets.UTF_8))
        File(dir, "maps.txt").delete() // формат 1.1
        // Прибираємо карти, на які вже ніщо не посилається (скасовані мазки, видалені маски, стара історія)
        val used = enc.maps.mapNotNull { writtenMaps[it] }.toSet()
        dir.listFiles { f -> f.name.startsWith("map_") && f.name !in used }?.forEach { it.delete() }
        writtenMaps.entries.removeIf { it.value !in used }
    }

    fun load(): Restored? = runCatching {
        if (!stateFile.isFile) return null
        val json = stateFile.readText(Charsets.UTF_8)
        val names = SessionCodec.mapFiles(json)
            ?: File(dir, "maps.txt").takeIf { it.isFile }?.readText()?.lines()?.filter { it.isNotBlank() }.orEmpty()
        val maps = names.map { SessionCodec.decodeMap(File(dir, it).readBytes()) }
        val decoded = SessionCodec.decode(json, maps)
        val source = File(dir, decoded.meta.sourceFile)
        if (!source.isFile) return null
        // Щоб наступне збереження не переписувало ті самі карти
        writtenMaps.clear()
        maps.forEachIndexed { i, m -> writtenMaps[m] = names[i] }
        mapCounter = names.mapNotNull { it.removePrefix("map_").removeSuffix(".bin").toIntOrNull() }.maxOrNull()?.plus(1) ?: 0
        Restored(decoded.meta, decoded.state, source, decoded.undo, decoded.redo)
    }.getOrNull()

    fun hasSession(): Boolean = stateFile.isFile

    // ------------------------------------------------------------------ Нещодавні
    /**
     * Переносить поточну сесію в «Нещодавні» (замість видалення) і повертає її id.
     * Найстаріші записи понад [MAX_RECENT] видаляються разом з копіями оригіналів.
     */
    fun archive(): String? {
        if (!stateFile.isFile) { clear(); return null }
        recentDir.mkdirs()
        val id = "r${System.currentTimeMillis()}"
        val target = File(recentDir, id)
        if (!dir.renameTo(target)) {
            dir.copyRecursively(target, overwrite = true)
            dir.deleteRecursively()
        }
        writtenMaps.clear()
        mapCounter = 0
        listRecent().drop(MAX_RECENT).forEach { File(recentDir, it.id).deleteRecursively() }
        return id
    }

    fun recentThumb(id: String): File = File(File(recentDir, id), "thumb.jpg")

    /** Від найновішого до найстарішого. */
    fun listRecent(): List<RecentItem> {
        val dirs = recentDir.listFiles { f -> f.isDirectory && f.name.startsWith("r") } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val json = File(d, "state.json").takeIf { it.isFile }?.readText() ?: return@mapNotNull null
            val name = runCatching {
                ((MiniJson.parse(json) as Map<*, *>)["meta"] as Map<*, *>)["displayName"] as String
            }.getOrNull() ?: return@mapNotNull null
            RecentItem(d.name, name, File(d, "thumb.jpg").takeIf { it.isFile }, d.name.removePrefix("r").toLongOrNull() ?: 0L)
        }.sortedByDescending { it.time }
    }

    /** Повертає відкладене редагування в поточну сесію (поточна видаляється). */
    fun restoreRecent(id: String): Boolean {
        val src = File(recentDir, id)
        if (!File(src, "state.json").isFile) return false
        clear()
        if (!src.renameTo(dir)) {
            src.copyRecursively(dir, overwrite = true)
            src.deleteRecursively()
        }
        return true
    }

    fun deleteRecent(id: String) {
        File(recentDir, id).deleteRecursively()
    }

    fun clear() {
        dir.deleteRecursively()
        writtenMaps.clear()
        mapCounter = 0
    }

    companion object {
        /** Скільки відкладених редагувань тримати (кожне — з копією оригіналу). */
        const val MAX_RECENT = 8
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
