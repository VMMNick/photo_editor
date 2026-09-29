package com.photoeditor.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import com.photoeditor.app.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoeditor.app.core.AlphaMap
import com.photoeditor.app.core.CubeLut
import com.photoeditor.app.core.DetailMath
import com.photoeditor.app.core.GeometryMath
import com.photoeditor.app.core.Histogram
import com.photoeditor.app.core.MaskMaps
import com.photoeditor.app.core.MaskRaster
import com.photoeditor.app.core.PixelImage
import com.photoeditor.app.core.Retouch
import com.photoeditor.app.core.SessionMeta
import com.photoeditor.app.gl.GlWorker
import com.photoeditor.app.gl.RenderParams
import com.photoeditor.app.gl.Shaders
import com.photoeditor.app.image.ExportFormat
import com.photoeditor.app.image.ExportSize
import com.photoeditor.app.image.Exporter
import com.photoeditor.app.image.DetailTile
import com.photoeditor.app.image.ImageLoader
import com.photoeditor.app.image.LutStore
import com.photoeditor.app.image.SessionStore
import com.photoeditor.app.image.SubjectSegmenter
import com.photoeditor.app.image.toBitmap
import com.photoeditor.app.image.toPixelImage
import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.AspectRatio
import com.photoeditor.app.model.BrushStroke
import com.photoeditor.app.model.CloneOp
import com.photoeditor.app.model.CurveChannel
import com.photoeditor.app.model.EditState
import com.photoeditor.app.model.EraseOp
import com.photoeditor.app.model.Geometry
import com.photoeditor.app.model.HealOp
import com.photoeditor.app.model.Look
import com.photoeditor.app.model.LookClipboard
import com.photoeditor.app.model.Mask
import com.photoeditor.app.model.MaskKind
import com.photoeditor.app.model.NRect
import com.photoeditor.app.model.Preset
import com.photoeditor.app.model.PresetCategory
import com.photoeditor.app.model.PresetStore
import com.photoeditor.app.model.Presets
import com.photoeditor.app.model.RetouchOp
import com.photoeditor.app.model.StrokePoint
import com.photoeditor.app.model.ToneCurve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

enum class Tool(@StringRes val labelRes: Int) {
    CROP(R.string.tool_crop), LIGHT(R.string.tool_light), COLOR(R.string.tool_color), DETAIL(R.string.tool_detail),
    RETOUCH(R.string.tool_retouch), MASKS(R.string.tool_masks), FILTERS(R.string.tool_filters),
}

enum class RetouchMode(@StringRes val labelRes: Int) {
    HEAL(R.string.mode_heal), CLONE(R.string.mode_clone), ERASE(R.string.mode_erase),
}

data class ImageInfo(
    val name: String,
    val isRaw: Boolean,
    val fullWidth: Int,
    val fullHeight: Int,
    val usedEmbeddedPreview: Boolean,
)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val PREVIEW_MAX_SIDE = 2048
        const val PREVIEW_MAX_PIXELS = 3_200_000L
        private const val HISTORY_LIMIT = 80
        private const val HISTOGRAM_SIDE = 256
        private const val RECENT_THUMB_SIDE = 480
        const val KEY_TIPS_DONE = "tips_done"
        /** Скільки кроків історії зберігати в сесії на диску. */
        const val SAVED_HISTORY = 30

        /** Один потік для всіх операцій із папкою сесії (спільний для всіх ViewModel) — записи не перекриваються. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val SESSION_IO = Dispatchers.IO.limitedParallelism(1)

        /** Токен ViewModel, якому належить папка сесії. Застарілий екземпляр не перезапише чужу сесію. */
        @Volatile private var sessionOwner: Any? = null
    }

    private val presetStore = PresetStore(app)
    private val lookClipboard = LookClipboard(app)
    private val uiPrefs = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)

    /** Короткі поради при першому відкритті редактора. */
    var showTips by mutableStateOf(!uiPrefs.getBoolean(KEY_TIPS_DONE, false))
        private set

    fun dismissTips() {
        showTips = false
        uiPrefs.edit().putBoolean(KEY_TIPS_DONE, true).apply()
    }
    private val lutStore = LutStore(app)

    /** Імпортовані LUT-файли. */
    var luts by mutableStateOf(lutStore.list())
        private set
    /** Завантажений LUT (id → дані); рендер бере його, лише якщо id збігається з вибраним. */
    private var loadedLut by mutableStateOf<Pair<String, CubeLut>?>(null)
    private var lutJob: Job? = null
    private var lutJobId: String? = null

    /** Скопійований «вигляд» (переживає перезапуск). */
    var copiedLook by mutableStateOf(lookClipboard.load())
        private set

    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    // ------------------------------------------------------------------ Загальний стан
    var info by mutableStateOf<ImageInfo?>(null)
        private set
    var sourceUri by mutableStateOf<Uri?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** Текст тривалої операції (ретуш, експорт) або null. */
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    /** Файл, яким можна поділитися з поточного повідомлення (кнопка «Поділитися»); скидається разом з ним. */
    var messageShareUri by mutableStateOf<Uri?>(null)

    /** Останній експортований файл. */
    var lastExportUri by mutableStateOf<Uri?>(null)
        private set

    /**
     * «Покоління» відкритого фото. Фонові задачі (ретуш, карти масок) запам'ятовують його на старті
     * й відкидають результат, якщо за цей час відкрили інше фото.
     */
    private var generation = 0

    /** Поточне (після ретушу) прев'ю, яке піде у GPU-текстуру. */
    var displayBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var state by mutableStateOf(EditState())
        private set
    var tool by mutableStateOf(Tool.LIGHT)
    var compare by mutableStateOf(false)
    /** Шторка «до/після» і її положення (частка ширини кадру). */
    var splitCompare by mutableStateOf(false)
    var splitPos by mutableFloatStateOf(0.5f)

    // ------------------------------------------------------------------ Гістограма та криві
    /** Гістограма поточного результату (перераховується з невеликою затримкою після змін). */
    var histogram by mutableStateOf<Histogram?>(null)
        private set
    /** Підсвічування пересвічених / провалених ділянок на фото. */
    var clipWarning by mutableStateOf(false)
    var curveChannel by mutableStateOf(CurveChannel.RGB)
    private var histJob: Job? = null
    private var histSmall: Pair<Bitmap, Bitmap>? = null

    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var gestureBase: EditState? = null

    val srcW get() = displayBitmap?.width ?: 1
    val srcH get() = displayBitmap?.height ?: 1

    // ------------------------------------------------------------------ Маски
    var selectedMask by mutableIntStateOf(-1)
    /** Постійне червоне підсвічування маски (перемикач у панелі). */
    var showMaskOverlay by mutableStateOf(false)
    /** Тимчасове підсвічування, поки користувач тягне маркери чи малює маску. */
    var maskGestureActive by mutableStateOf(false)
    /**
     * Растрові карти масок за id. Рахуються асинхронно, тому зберігаються за id, а не за індексом:
     * після видалення/перестановки маски карта не «перескочить» на сусідню.
     */
    var maskMapsById by mutableStateOf<Map<Long, AlphaMap?>>(emptyMap())
        private set
    /** Карти в порядку поточних масок (null — ще рахується або не потрібна). */
    val maskMaps: List<AlphaMap?> get() = state.masks.map { maskMapsById[it.id] }
    var maskBrushErase by mutableStateOf(false)
    var maskBrushSizeDp by mutableFloatStateOf(40f)
    var maskBrushHardness by mutableFloatStateOf(0.3f)

    // ------------------------------------------------------------------ Ретуш
    var retouchMode by mutableStateOf(RetouchMode.HEAL)
    var retouchBrushSizeDp by mutableFloatStateOf(28f)
    /** Штамп: точка-джерело (uv) і чи треба її зараз вказати. */
    var cloneSource by mutableStateOf<StrokePoint?>(null)
    var pickingCloneSource by mutableStateOf(true)
    private var cloneOffset: Pair<Float, Float>? = null
    /** AI-ластик: виділена область, яку буде видалено. */
    var eraseSelection by mutableStateOf<AlphaMap?>(null)
        private set
    var eraseBrushRemove by mutableStateOf(false)
    private var segCache: Pair<Bitmap, SubjectSegmenter.Result>? = null
    private var segmenterInstance: SubjectSegmenter? = null
    private val segmenter: SubjectSegmenter
        get() = segmenterInstance ?: SubjectSegmenter().also { segmenterInstance = it }

    // ------------------------------------------------------------------ Пресети
    var userPresets by mutableStateOf(presetStore.load())
        private set
    var thumbnails by mutableStateOf<Map<String, Bitmap>>(emptyMap())
        private set
    val allPresets get() = Presets.builtIn + userPresets

    val effectiveAdjust: Adjustments
        get() = state.effectiveAdjust { id -> Presets.find(id, userPresets)?.delta }

    // ================================================================== Відкриття
    fun open(uri: Uri) {
        if (loading) return
        loading = true
        cancelBackgroundWork()
        saveJob?.cancel()
        sessionOwner = sessionToken
        viewModelScope.launch {
            try {
                val app: Application = getApplication()
                val name = withContext(Dispatchers.IO) { ImageLoader.displayName(app, uri) }
                // Робоча копія в папці сесії: переживає смерть процесу і не залежить від дозволів на Uri
                val copy = withContext(sessionIo) { runCatching { sessionStore.importSource(app, uri, name) }.getOrNull() }
                val src = copy?.let { Uri.fromFile(it) } ?: uri
                val loaded = withContext(Dispatchers.IO) {
                    ImageLoader.load(app, src, PREVIEW_MAX_SIDE, PREVIEW_MAX_PIXELS, embeddedPreviewOut = copy?.let { sessionStore.detailFile() })
                }
                val meta = copy?.let {
                    SessionMeta(name, loaded.isRaw, loaded.fullWidth, loaded.fullHeight, loaded.usedEmbeddedPreview, it.name)
                }
                installImage(loaded, src, name, meta, EditState())
                if (loaded.isRaw && loaded.usedEmbeddedPreview) message = str(R.string.msg_raw_embedded)
                scheduleSave(0)
            } catch (e: Throwable) {
                message = str(R.string.msg_open_failed, e.message.orEmpty())
            } finally {
                loading = false
            }
        }
    }

    /**
     * Відновлення незавершеного редагування після того, як Android закрив процес у фоні
     * (або користувач змахнув застосунок, не закривши фото).
     * @return true, якщо відновлення запущено
     */
    fun restoreSessionIfAny(): Boolean {
        if (info != null || loading || !sessionStore.hasSession()) return false
        loading = true
        sessionOwner = sessionToken
        viewModelScope.launch {
            try {
                val restored = withContext(sessionIo) { sessionStore.load() }
                if (restored == null) {
                    withContext(sessionIo) { sessionStore.clear() }
                    return@launch
                }
                val src = Uri.fromFile(restored.source)
                val loaded = withContext(Dispatchers.IO) {
                    ImageLoader.load(getApplication(), src, PREVIEW_MAX_SIDE, PREVIEW_MAX_PIXELS)
                }
                installImage(loaded, src, restored.meta.displayName, restored.meta, restored.state)
                // Історія: «Скасувати» працює і для змін, зроблених до того, як Android закрив застосунок
                undoStack.clear(); undoStack.addAll(restored.undo)
                redoStack.clear(); redoStack.addAll(restored.redo)
                refreshHistoryFlags()
                message = str(R.string.msg_session_restored)
            } catch (e: Throwable) {
                withContext(sessionIo) { sessionStore.clear() }
            } finally {
                loading = false
            }
        }
        return true
    }

    /** Спільна частина відкриття і відновлення: скидає все, що стосувалося попереднього фото. */
    private suspend fun installImage(loaded: ImageLoader.Loaded, src: Uri, name: String, meta: SessionMeta?, initial: EditState) {
        val base = withContext(Dispatchers.Default) { loaded.bitmap.toPixelImage() }
        retouchMutex.withLock {
            snapshots.clear()
            snapshots += emptyList<RetouchOp>() to base
        }
        generation++
        synchronized(mapCache) { mapCache.clear() }
        sessionMeta = meta
        // Тайли ріжемо з робочої копії (або з вбудованого JPEG-прев'ю для RAW)
        val copy = src.path?.let { File(it) }?.takeIf { src.scheme == "file" && it.isFile }
        val detailFile = if (loaded.usedEmbeddedPreview) sessionStore.detailFile().takeIf { it.isFile } else copy
        resetDetail(detailFile?.let { it to loaded.orientation })
        sourceUri = src
        lastExportUri = null
        info = ImageInfo(
            meta?.displayName ?: name,
            meta?.isRaw ?: loaded.isRaw,
            meta?.fullWidth ?: loaded.fullWidth,
            meta?.fullHeight ?: loaded.fullHeight,
            meta?.usedEmbeddedPreview ?: loaded.usedEmbeddedPreview,
        )
        displayBitmap = loaded.bitmap
        histogram = null
        state = EditState()
        undoStack.clear(); redoStack.clear(); gestureBase = null
        refreshHistoryFlags()
        selectedMask = -1
        maskMapsById = emptyMap()
        cloneSource = null; cloneOffset = null; pickingCloneSource = true
        eraseSelection = null
        segCache = null
        tool = Tool.LIGHT
        // Відновлений стан застосовуємо як звичайну зміну: перераховує ретуш і карти масок
        if (initial != EditState()) updateState(initial)
        // Гістограма раніше за мініатюри: обидві задачі йдуть в один GL-потік, а гістограму видно одразу
        refreshHistogram(0)
        refreshThumbnails()
    }

    private fun cancelBackgroundWork() {
        retouchJob?.cancel()
        mapsJob?.cancel()
        generation++
    }

    fun close() {
        cancelBackgroundWork()
        saveJob?.cancel()
        commit()
        val meta = sessionMeta
        val snapshot = state
        val undo = undoStack.toList().takeLast(SAVED_HISTORY)
        val redo = redoStack.toList().takeLast(SAVED_HISTORY)
        val b = displayBitmap
        val eff = effectiveAdjust
        val maps = maskMaps
        val lut = activeLut()
        sessionMeta = null
        resetDetail(null)
        // Фото з правками переходить у «Нещодавні» (можна повернутися пізніше), без правок — видаляється
        if (sessionOwner === sessionToken) {
            sessionOwner = null
            viewModelScope.launch {
                // Перенесення — першим завданням у черзі сесії, раніше за можливе відкриття іншого фото
                val id = withContext(sessionIo) {
                    if (meta != null && snapshot != EditState()) {
                        runCatching { sessionStore.save(meta, snapshot, undo, redo) }
                        sessionStore.archive()
                    } else {
                        sessionStore.clear(); null
                    }
                }
                if (id != null && b != null) {
                    runCatching {
                        val k = RECENT_THUMB_SIDE.toFloat() / max(b.width, b.height)
                        val small = withContext(Dispatchers.Default) {
                            Bitmap.createScaledBitmap(b, max(1, (b.width * k).toInt()), max(1, (b.height * k).toInt()), true)
                        }
                        val thumb = GlWorker.renderSmall(small) { w, h -> RenderParams.from(snapshot, w, h, eff, maps, lut = lut) }
                        withContext(Dispatchers.IO) {
                            sessionStore.recentThumb(id).outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        }
                    }
                }
                refreshRecents()
            }
        }
        busy = null
        info = null
        sourceUri = null
        displayBitmap = null
        histogram = null
        histSmall = null
        thumbnails = emptyMap()
        segCache = null
    }

    // ================================================================== Нещодавні
    /** Відкладені редагування для головного екрана. */
    var recents by mutableStateOf<List<com.photoeditor.app.image.RecentItem>>(emptyList())
        private set

    fun refreshRecents() {
        viewModelScope.launch { recents = withContext(sessionIo) { sessionStore.listRecent() } }
    }

    /** Відкриває відкладене редагування разом з історією. */
    fun openRecent(id: String) {
        if (loading || info != null) return
        viewModelScope.launch {
            val ok = withContext(sessionIo) { sessionStore.restoreRecent(id) }
            refreshRecents()
            if (ok) restoreSessionIfAny()
        }
    }

    fun deleteRecent(id: String) {
        viewModelScope.launch {
            withContext(sessionIo) { sessionStore.deleteRecent(id) }
            refreshRecents()
        }
    }

    // ================================================================== Сесія на диску
    private val sessionStore = SessionStore(app)
    private val sessionIo get() = SESSION_IO
    /** Ідентичність цього екземпляра для [sessionOwner] (без статичного посилання на сам ViewModel). */
    private val sessionToken = Any()
    private var sessionMeta: SessionMeta? = null
    private var saveJob: Job? = null

    /** Відкладене збереження: серія змін (рух повзунка) пишеться на диск один раз. */
    private fun scheduleSave(delayMs: Long = 600) {
        val meta = sessionMeta ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val snapshot = state
            // Стеки читаємо в головному потоці; на диск — лише останні кроки
            val undo = undoStack.toList().takeLast(SAVED_HISTORY)
            val redo = redoStack.toList().takeLast(SAVED_HISTORY)
            withContext(sessionIo) {
                // Папка сесії одна на процес: пише лише той ViewModel, що відкрив поточне фото
                if (sessionOwner === sessionToken) runCatching { sessionStore.save(meta, snapshot, undo, redo) }
            }
        }
    }

    // ================================================================== Детальні тайли
    private val detail = DetailTileController(viewModelScope)

    /** Фрагмент оригіналу в повній роздільності для збільшеного прев'ю (або null). */
    val detailTile: DetailTile? get() = detail.tile

    /** Чи варто взагалі показувати тайл — оригінал помітно більший за прев'ю. */
    val detailAvailable: Boolean
        get() = detail.hasSource && (info?.fullWidth ?: 0) > srcW * 1.1f

    fun requestDetail(want: FloatArray, targetMaxSide: Int) {
        val ops = state.retouch
        val gen = generation
        detail.request(want, targetMaxSide, ops, srcW, srcH) { gen == generation && ops === state.retouch }
    }

    private fun resetDetail(spec: Pair<File, Int>?) = detail.reset(spec)

    /** Негайне збереження — викликається, коли застосунок іде у фон (onStop). */
    fun saveSessionNow() {
        commit()
        scheduleSave(0)
    }

    private fun refreshThumbnails() {
        val b = displayBitmap ?: return
        viewModelScope.launch {
            thumbnails = runCatching { GlWorker.presetThumbnails(b, allPresets) }.getOrDefault(emptyMap())
        }
    }

    // ================================================================== Історія
    /** Проміжна зміна (жест, рух повзунка) — без запису в історію. */
    fun preview(s: EditState) {
        if (gestureBase == null) gestureBase = state
        updateState(s)
    }

    /** Завершення жесту: одна дія в історії на весь рух повзунка. */
    fun commit() {
        val base = gestureBase ?: return
        gestureBase = null
        if (base != state) {
            undoStack.addLast(base)
            while (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
            redoStack.clear()
            scheduleSave() // історія теж частина сесії
        }
        refreshHistoryFlags()
    }

    fun apply(s: EditState) {
        preview(s)
        commit()
    }

    fun undo() {
        commit()
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(state)
        updateState(prev)
        refreshHistoryFlags()
    }

    fun redo() {
        commit()
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(state)
        updateState(next)
        refreshHistoryFlags()
    }

    fun resetAll() = apply(EditState())

    private fun refreshHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun updateState(s: EditState) {
        val old = state
        state = s
        if (s != old) scheduleSave()
        if (s.retouch != old.retouch) {
            syncRetouch(s.retouch)
            detail.invalidate() // тайл містить ретуш — перерахується для нового списку
        }
        if (s.masks !== old.masks) updateMaskMaps(s.masks)
        if (selectedMask >= s.masks.size) selectedMask = s.masks.lastIndex
        if (s != old) refreshHistogram()
        ensureLut()
    }

    /** Фонове завантаження вибраного LUT (файл компактний, але це все одно диск). */
    private fun ensureLut() {
        val id = effectiveAdjust.lut ?: return
        if (loadedLut?.first == id || (lutJobId == id && lutJob?.isActive == true)) return
        lutJobId = id
        lutJob = viewModelScope.launch {
            val lut = withContext(Dispatchers.IO) { lutStore.load(id) }
            if (lut != null) {
                loadedLut = id to lut
                refreshHistogram()
            }
        }
    }

    private fun activeLut(): CubeLut? = loadedLut?.takeIf { it.first == effectiveAdjust.lut }?.second

    fun importLut(uri: Uri) {
        viewModelScope.launch {
            val app: Application = getApplication()
            try {
                val name = withContext(Dispatchers.IO) { ImageLoader.displayName(app, uri) }
                val info = withContext(Dispatchers.IO) { lutStore.import(app, uri, name) }
                luts = lutStore.list()
                apply(state.copy(adjust = state.adjust.copy(lut = info.id, lutAmount = 1f)))
                message = str(R.string.msg_lut_imported, info.title)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = str(R.string.msg_lut_failed, e.message.orEmpty())
            }
        }
    }

    fun deleteLut(id: String) {
        if (state.adjust.lut == id) apply(state.copy(adjust = state.adjust.copy(lut = null)))
        lutStore.delete(id)
        luts = lutStore.list()
        if (loadedLut?.first == id) loadedLut = null
    }

    /** Відкладений перерахунок гістограми (під час руху повзунка — не частіше ніж раз на ~120 мс). */
    fun refreshHistogram(delayMs: Long = 120) {
        val b = displayBitmap ?: return
        if (histJob?.isActive == true && delayMs > 0) return
        histJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            val cached = histSmall?.takeIf { it.first === b }?.second
            val small = cached ?: withContext(Dispatchers.Default) {
                val k = HISTOGRAM_SIDE.toFloat() / max(b.width, b.height)
                Bitmap.createScaledBitmap(b, max(1, (b.width * k).toInt()), max(1, (b.height * k).toInt()), true)
            }.also { histSmall = b to it }
            // Параметри беремо в момент рендеру — вже з останніми змінами
            val s = state
            val eff = effectiveAdjust
            val maps = maskMaps
            val lut = activeLut()
            histogram = runCatching { GlWorker.histogram(small) { w, h -> RenderParams.from(s, w, h, eff, maps, lut = lut) } }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) android.util.Log.w("EditorViewModel", "histogram", it) }
                .getOrNull() ?: histogram
            // Поки рахували, стан змінився — ще один прохід з актуальними даними
            if (state !== s || displayBitmap !== b) {
                histJob = null
                refreshHistogram()
            }
        }
    }

    fun previewCurve(ch: CurveChannel, c: ToneCurve) =
        previewAdjust(state.adjust.copy(curves = state.adjust.curves.with(ch, c)))

    fun resetCurve(ch: CurveChannel) =
        applyAdjust(state.adjust.copy(curves = state.adjust.curves.with(ch, ToneCurve())))

    // ================================================================== Параметри рендеру
    fun renderParams(): RenderParams? {
        val b = displayBitmap ?: return null
        var s = state
        var maps = maskMaps
        var show = if (tool == Tool.MASKS && (showMaskOverlay || maskGestureActive)) selectedMask else -1

        // Виділення AI-ластика показуємо як тимчасову «маску» з нульовими корекціями
        val sel = eraseSelection
        if (tool == Tool.RETOUCH && retouchMode == RetouchMode.ERASE && sel != null) {
            val keep = s.masks.take(Shaders.MAX_MASKS - 1)
            s = s.copy(masks = keep + Mask(id = -1, kind = MaskKind.SUBJECT, subject = sel))
            maps = maps.take(keep.size) + sel
            show = keep.size
        }
        return RenderParams.from(
            state = s,
            srcW = b.width,
            srcH = b.height,
            effective = effectiveAdjust,
            maskMaps = maps,
            cropOverride = if (tool == Tool.CROP) NRect.FULL else null,
            showMask = show,
            bypass = compare,
            clipWarning = clipWarning && !compare,
            // Під час кадрування шторка заважала б бачити рамку
            splitX = if (splitCompare && !compare && tool != Tool.CROP) splitPos.coerceIn(0f, 1f) else -1f,
            lut = activeLut(),
        )
    }

    fun geometryMath(forCropTool: Boolean = tool == Tool.CROP): GeometryMath =
        GeometryMath(srcW, srcH, state.geometry, if (forCropTool) NRect.FULL else null)

    // ================================================================== Світло / колір / фільтри
    fun previewAdjust(a: Adjustments) = preview(state.copy(adjust = a))
    fun applyAdjust(a: Adjustments) = apply(state.copy(adjust = a))

    fun selectPreset(p: Preset?) = apply(state.copy(presetId = p?.id, presetAmount = if (p == null) 1f else state.presetAmount))
    fun previewPresetAmount(v: Float) = preview(state.copy(presetAmount = v))

    fun saveUserPreset(name: String) {
        val p = Preset(
            id = "user_" + System.currentTimeMillis(),
            name = name.ifBlank { str(R.string.default_preset_name) },
            category = PresetCategory.MINE,
            delta = effectiveAdjust,
            builtIn = false,
        )
        userPresets = userPresets + p
        presetStore.save(userPresets)
        message = str(R.string.msg_preset_saved, p.name)
        refreshThumbnails()
    }

    fun deleteUserPreset(p: Preset) {
        userPresets = userPresets.filter { it.id != p.id }
        presetStore.save(userPresets)
        if (state.presetId == p.id) selectPreset(null)
    }

    // ================================================================== Геометрія
    fun previewGeometry(g: Geometry) = preview(state.copy(geometry = g))
    fun applyGeometry(g: Geometry) = apply(state.copy(geometry = g))

    fun rotate90(clockwise: Boolean) {
        val g = state.geometry
        val turns = (g.quarterTurns + if (clockwise) 1 else 3) % 4
        val ng = g.copy(quarterTurns = turns)
        applyGeometry(ng.copy(crop = cropForAspect(ng, g.aspect)))
    }

    fun setAspect(a: AspectRatio) {
        val g = state.geometry.copy(aspect = a)
        applyGeometry(g.copy(crop = if (a == AspectRatio.FREE) state.geometry.crop else cropForAspect(g, a)))
    }

    /** Пропорція кадру в пікселях (ширина/висота) для обраного співвідношення. */
    fun aspectValue(g: Geometry, a: AspectRatio): Float? {
        val gm = GeometryMath(srcW, srcH, g, NRect.FULL)
        return when (val r = a.ratio) {
            null -> null
            -1f -> (gm.frameW / gm.frameH).toFloat()
            else -> r
        }
    }

    private fun cropForAspect(g: Geometry, a: AspectRatio): NRect {
        val r = aspectValue(g, a) ?: return NRect.FULL
        val gm = GeometryMath(srcW, srcH, g, NRect.FULL)
        return GeometryMath.centeredCrop(gm.frameW, gm.frameH, r.toDouble())
    }

    fun resetGeometry() = applyGeometry(Geometry())

    // ================================================================== Маски
    private data class MapKey(val kind: MaskKind, val strokes: List<BrushStroke>, val subject: AlphaMap?)
    private val mapCache = HashMap<MapKey, AlphaMap?>()
    private var mapsJob: Job? = null

    private fun updateMaskMaps(masks: List<Mask>) {
        val w = srcW
        val h = srcH
        val gen = generation
        mapsJob?.cancel()
        mapsJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                masks.associate { m ->
                    if (m.kind != MaskKind.BRUSH && m.kind != MaskKind.SUBJECT) return@associate m.id to null
                    val key = MapKey(m.kind, m.strokes, m.subject)
                    val cached = synchronized(mapCache) { mapCache[key] }
                    m.id to (cached ?: run {
                        // Під час малювання змінюється лише останній мазок — беремо готову карту без нього
                        val withoutLast = if (m.strokes.isNotEmpty()) {
                            synchronized(mapCache) { mapCache[MapKey(m.kind, m.strokes.dropLast(1), m.subject)] }
                        } else null
                        MaskMaps.forMask(m, w, h, withoutLast).also { map ->
                            synchronized(mapCache) {
                                if (mapCache.size > 32) mapCache.clear()
                                mapCache[key] = map
                            }
                        }
                    })
                }
            }
            if (gen == generation) maskMapsById = result
        }
    }

    fun addMask(kind: MaskKind, invert: Boolean = false) {
        if (state.masks.size >= Shaders.MAX_MASKS) {
            message = str(R.string.msg_max_masks, Shaders.MAX_MASKS)
            return
        }
        val id = System.nanoTime()
        when (kind) {
            MaskKind.SUBJECT -> {
                val bmp = displayBitmap ?: return
                runSegmentation(bmp) { res ->
                    val fg = res.foreground ?: res.subjects.fold<AlphaMap, AlphaMap?>(null) { acc, m -> acc?.union(m) ?: m }
                    if (fg == null || fg.isEmpty()) {
                        message = str(R.string.msg_no_subject)
                    } else {
                        apply(state.copy(masks = state.masks + Mask(id = id, kind = kind, subject = fg, invert = invert)))
                        selectedMask = state.masks.lastIndex
                    }
                }
                return
            }
            MaskKind.LINEAR -> apply(state.copy(masks = state.masks + Mask(id = id, kind = kind, x0 = 0.5f, y0 = 0.05f, x1 = 0.5f, y1 = 0.45f)))
            MaskKind.RADIAL -> apply(state.copy(masks = state.masks + Mask(id = id, kind = kind)))
            MaskKind.BRUSH -> apply(state.copy(masks = state.masks + Mask(id = id, kind = kind)))
        }
        selectedMask = state.masks.lastIndex
    }

    fun updateMask(index: Int, m: Mask, commit: Boolean) {
        if (index !in state.masks.indices) return
        val list = state.masks.toMutableList().also { it[index] = m }
        if (commit) apply(state.copy(masks = list)) else preview(state.copy(masks = list))
    }

    fun deleteMask(index: Int) {
        if (index !in state.masks.indices) return
        apply(state.copy(masks = state.masks.filterIndexed { i, _ -> i != index }))
        selectedMask = if (state.masks.isEmpty()) -1 else minOf(index, state.masks.lastIndex)
    }

    fun duplicateMaskInverted(index: Int) {
        val m = state.masks.getOrNull(index) ?: return
        if (state.masks.size >= Shaders.MAX_MASKS) return
        apply(state.copy(masks = state.masks + m.copy(id = System.nanoTime(), invert = !m.invert)))
        selectedMask = state.masks.lastIndex
    }

    // ================================================================== Ретуш
    private val snapshots = ArrayList<Pair<List<RetouchOp>, PixelImage>>()
    private val retouchMutex = Mutex()
    private var retouchJob: Job? = null

    private fun syncRetouch(ops: List<RetouchOp>) {
        retouchJob?.cancel()
        val gen = generation
        retouchJob = viewModelScope.launch {
            val heavy = ops.any { it is EraseOp }
            val img = retouchMutex.withLock {
                withContext(Dispatchers.Default) {
                    val best = snapshots
                        .filter { (k, _) -> k.size <= ops.size && ops.subList(0, k.size) == k }
                        .maxByOrNull { it.first.size } ?: return@withContext null
                    var cur = best.second
                    if (best.first.size < ops.size && heavy) withContext(Dispatchers.Main) { busy = str(R.string.busy_processing) }
                    for (i in best.first.size until ops.size) {
                        cur = Retouch.apply(cur, ops[i])
                        snapshots += ops.subList(0, i + 1).toList() to cur
                    }
                    // Кеш: оригінал + кілька останніх станів (для швидкого undo)
                    // 6 × ~13 МБ на прев'ю 3.2 Мп — компроміс між швидким undo і пам'яттю
                    while (snapshots.size > 6) snapshots.removeAt(1)
                    cur.toBitmap()
                }
            }
            busy = null
            if (img != null && gen == generation) {
                displayBitmap = img
                segCache = null
                refreshHistogram()
            }
        }
    }

    /** Радіус у «квадратних» одиницях → додаємо точковий коректор. */
    fun heal(u: Float, v: Float, radius: Float) {
        if (u !in 0f..1f || v !in 0f..1f) return
        apply(state.copy(retouch = state.retouch + HealOp(u, v, radius)))
    }

    fun setCloneSource(u: Float, v: Float) {
        cloneSource = StrokePoint(u, v)
        cloneOffset = null
        pickingCloneSource = false
    }

    /** Штамп: зсув фіксується на першому мазку після вибору джерела (вирівняний режим). */
    fun cloneStroke(points: List<StrokePoint>, radius: Float, hardness: Float = 0.4f) {
        val src = cloneSource ?: run { message = str(R.string.msg_clone_source_first); return }
        if (points.isEmpty()) return
        val off = cloneOffset ?: (src.u - points[0].u to src.v - points[0].v).also { cloneOffset = it }
        apply(state.copy(retouch = state.retouch + CloneOp(BrushStroke(points, radius, hardness), off.first, off.second)))
    }

    /** Пензель виділення AI-ластика. */
    fun eraseBrush(points: List<StrokePoint>, radius: Float) {
        if (points.isEmpty()) return
        val stroke = listOf(BrushStroke(points, radius, hardness = 0.8f, erase = eraseBrushRemove))
        val current = eraseSelection
        if (current == null) {
            val w = (srcW * 0.5f).toInt().coerceAtLeast(1)
            val h = (srcH * 0.5f).toInt().coerceAtLeast(1)
            eraseSelection = MaskRaster.rasterize(stroke, w, h, srcW, srcH)
        } else {
            // Малюємо прямо в масив і лише «обгортаємо» його новим об'єктом — щоб Compose і GL
            // побачили зміну, але без копіювання 0.8 МБ на кожен рух пальця
            MaskRaster.paintInto(current, stroke, srcW, srcH)
            eraseSelection = AlphaMap(current.width, current.height, current.data)
        }
    }

    /** Тап по об'єкту: AI знаходить людину/предмет під пальцем і виділяє його повністю. */
    fun eraseSelectObjectAt(u: Float, v: Float) {
        val bmp = displayBitmap ?: return
        runSegmentation(bmp) { res ->
            val hit = res.subjects.maxByOrNull { it.sampleUv(u, v) }?.takeIf { it.sampleUv(u, v) > 0.35f }
                ?: res.foreground?.takeIf { it.sampleUv(u, v) > 0.5f }
            if (hit == null) {
                message = str(R.string.msg_no_object_here)
            } else {
                eraseSelection = eraseSelection?.union(hit) ?: hit
            }
        }
    }

    fun clearEraseSelection() {
        eraseSelection = null
    }

    fun commitErase() {
        val sel = eraseSelection ?: return
        if (sel.isEmpty()) return
        eraseSelection = null
        // Власна копія даних: масив виділення більше ніхто не змінить
        apply(state.copy(retouch = state.retouch + EraseOp(AlphaMap(sel.width, sel.height, sel.data.copyOf()))))
    }

    fun undoLastRetouch() {
        if (state.retouch.isEmpty()) return
        apply(state.copy(retouch = state.retouch.dropLast(1)))
    }

    private fun runSegmentation(bmp: Bitmap, onResult: (SubjectSegmenter.Result) -> Unit) {
        val cached = segCache
        if (cached != null && cached.first === bmp) {
            onResult(cached.second)
            return
        }
        viewModelScope.launch {
            busy = str(R.string.busy_ai_search)
            try {
                val res = segmenter.segment(bmp)
                segCache = bmp to res
                onResult(res)
            } catch (e: Throwable) {
                message = str(R.string.msg_ai_unavailable, e.message ?: str(R.string.msg_ai_unavailable_default))
            } finally {
                busy = null
            }
        }
    }

    // ================================================================== Експорт
    fun export(size: ExportSize, format: ExportFormat, quality: Int) {
        val uri = sourceUri ?: return
        if (busy != null) return
        commit()
        val snapshot = state
        val eff = effectiveAdjust
        viewModelScope.launch {
            var active = true
            busy = str(R.string.busy_export)
            try {
                val lut = eff.lut?.let { id -> withContext(Dispatchers.IO) { lutStore.load(id) } }
                val saved = Exporter.export(getApplication(), uri, info?.name ?: "photo", snapshot, eff, size, format, quality, lut) { text ->
                    // Прогрес приходить з GL-потоку; після завершення запізнілі оновлення ігноруємо
                    viewModelScope.launch(Dispatchers.Main) { if (active) busy = text }
                }
                lastExportUri = saved
                messageShareUri = saved.takeIf { it.scheme == "content" }
                message = str(R.string.msg_exported)
            } catch (e: OutOfMemoryError) {
                message = str(R.string.msg_oom)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                message = str(R.string.msg_export_failed, e.message.orEmpty())
            } finally {
                active = false
                busy = null
            }
        }
    }

    // ================================================================== Копіювання та пакетна обробка
    fun copyLook() {
        commit()
        val look = Look.of(state)
        copiedLook = look
        lookClipboard.save(look)
        message = str(R.string.msg_look_copied)
    }

    fun pasteLook() {
        val look = copiedLook ?: return
        apply(look.applyTo(state))
        message = str(R.string.msg_look_pasted)
    }

    /**
     * Застосовує скопійований вигляд до інших фото й експортує їх у галерею по черзі.
     * Кожне фото декодується в повній роздільності окремо, тож пам'яті потрібно як для одного експорту.
     */
    fun batchExport(uris: List<Uri>, size: ExportSize, format: ExportFormat, quality: Int) {
        val look = copiedLook ?: return
        if (busy != null || uris.isEmpty()) return
        val batchState = look.toState()
        val eff = batchState.effectiveAdjust { id -> Presets.find(id, userPresets)?.delta }
        viewModelScope.launch {
            var active = true
            var ok = 0
            var lastSaved: Uri? = null
            val app: Application = getApplication()
            try {
                val lut = eff.lut?.let { id -> withContext(Dispatchers.IO) { lutStore.load(id) } }
                uris.forEachIndexed { i, uri ->
                    busy = str(R.string.batch_progress, i + 1, uris.size)
                    try {
                        val name = withContext(Dispatchers.IO) { ImageLoader.displayName(app, uri) }
                        lastSaved = Exporter.export(app, uri, name, batchState, eff, size, format, quality, lut) { text ->
                            viewModelScope.launch(Dispatchers.Main) {
                                if (active) busy = str(R.string.batch_progress, i + 1, uris.size) + "\n" + text
                            }
                        }
                        ok++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        android.util.Log.w("EditorViewModel", "batch item failed: $uri", e)
                    }
                }
                messageShareUri = lastSaved?.takeIf { ok == 1 && it.scheme == "content" }
                message = if (ok == uris.size) str(R.string.msg_batch_done, ok)
                else str(R.string.msg_batch_partial, ok, uris.size, uris.size - ok)
            } finally {
                active = false
                busy = null
            }
        }
    }

    /** Переводить радіус пензля з пікселів екрана в «квадратні» одиниці джерела. */
    fun screenRadiusToSquare(radiusPx: Float, displayedOutWidthPx: Float, forCropTool: Boolean = false): Float {
        val gm = geometryMath(forCropTool)
        // 1 піксель результату = outW / displayedWidth пікселів кадру; масштаб S розтягує кадр
        val srcPx = radiusPx * (gm.outW.toFloat() / max(1f, displayedOutWidthPx)) / gm.autoScale.toFloat()
        return srcPx / max(srcW, srcH)
    }

    // Наприкінці класу: усі поля (зокрема sessionStore) вже ініціалізовані
    init {
        refreshRecents()
    }

    override fun onCleared() {
        if (sessionOwner === sessionToken) sessionOwner = null
        detail.close()
        runCatching { segmenterInstance?.close() }
        super.onCleared()
    }

}
