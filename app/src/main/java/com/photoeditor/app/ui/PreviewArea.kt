package com.photoeditor.app.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.abs
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.photoeditor.app.R
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.photoeditor.app.core.GeometryMath
import com.photoeditor.app.gl.PreviewRenderer
import com.photoeditor.app.model.MaskKind
import com.photoeditor.app.model.NRect
import com.photoeditor.app.model.StrokePoint
import com.photoeditor.app.ui.theme.Accent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Перетворення між пікселями екрана, координатами результату та uv джерела. */
class ViewMapper(val rect: Rect, val gm: GeometryMath) {
    fun toOut(p: Offset) = floatArrayOf((p.x - rect.left) / rect.width, (p.y - rect.top) / rect.height)
    fun toSource(p: Offset): StrokePoint {
        val o = toOut(p)
        val s = gm.outToSourceUv(o[0], o[1])
        return StrokePoint(s[0], s[1])
    }
    fun toScreen(u: Float, v: Float): Offset {
        val o = gm.sourceToOutUv(u, v)
        return Offset(rect.left + o[0] * rect.width, rect.top + o[1] * rect.height)
    }
}

/**
 * Видима частина фото в uv джерела (l, t, r, b) із запасом 15% — щоб невеликі зсуви не вимагали нового тайла.
 */
private fun visibleSourceRect(mp: ViewMapper, viewW: Float, viewH: Float): FloatArray? {
    val l = maxOf(0f, mp.rect.left)
    val t = maxOf(0f, mp.rect.top)
    val r = minOf(viewW, mp.rect.right)
    val b = minOf(viewH, mp.rect.bottom)
    if (r <= l || b <= t) return null
    var minU = 1f; var minV = 1f; var maxU = 0f; var maxV = 0f
    // Кути і середини сторін: при повороті/перспективі видима область у джерелі — довільний чотирикутник
    for (fx in listOf(0f, 0.5f, 1f)) for (fy in listOf(0f, 0.5f, 1f)) {
        val p = mp.toSource(Offset(l + (r - l) * fx, t + (b - t) * fy))
        minU = minOf(minU, p.u); maxU = maxOf(maxU, p.u)
        minV = minOf(minV, p.v); maxV = maxOf(maxV, p.v)
    }
    val padU = (maxU - minU) * 0.15f
    val padV = (maxV - minV) * 0.15f
    val out = floatArrayOf(
        (minU - padU).coerceIn(0f, 1f), (minV - padV).coerceIn(0f, 1f),
        (maxU + padU).coerceIn(0f, 1f), (maxV + padV).coerceIn(0f, 1f),
    )
    return if (out[2] - out[0] > 1e-3f && out[3] - out[1] > 1e-3f) out else null
}

class GlViewHolder {
    var view: GLSurfaceView? = null
}

/** Жест інструмента: один палець. Два пальці завжди масштабують/рухають фото. */
interface ToolGesture {
    fun start(p: Offset)
    fun move(p: Offset)
    fun end(p: Offset, tap: Boolean)
    fun cancel()
}

@Composable
fun PreviewArea(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val renderer = remember { PreviewRenderer() }
    // Посилання на GLSurfaceView — звичайний тримач, не Compose-стан:
    // його заповнює factory під час застосування змін, і писати туди стан не слід
    val glHolder = remember { GlViewHolder() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val cursor = remember { mutableStateOf<Offset?>(null) }

    val tool = vm.tool
    val params = vm.renderParams() ?: return
    val bitmap = vm.displayBitmap ?: return

    // У режимі кадрування залишаємо поля для маркерів і вимикаємо масштаб
    val inset = with(density) { (if (tool == Tool.CROP) 28.dp else 8.dp).toPx() }
    if (tool == Tool.CROP && (zoom != 1f || pan != Offset.Zero)) { zoom = 1f; pan = Offset.Zero }
    val fit = GeometryMath.fitRect(size.width - 2 * inset, size.height - 2 * inset, params.outAspect)
    val base = Rect(fit[0] + inset, fit[1] + inset, fit[0] + inset + fit[2], fit[1] + inset + fit[3])
    val rect = Rect(
        base.left * zoom + pan.x, base.top * zoom + pan.y,
        base.right * zoom + pan.x, base.bottom * zoom + pan.y,
    )
    val mapper = ViewMapper(rect, vm.geometryMath())

    // ---- Детальний тайл: коли фото збільшене сильніше, ніж дозволяє прев'ю, підвантажуємо
    // видиму ділянку оригіналу в повній роздільності
    val screenPxPerPreviewPx = rect.width / mapper.gm.outW.toFloat() * mapper.gm.autoScale.toFloat()
    val detailWanted: FloatArray? =
        if (tool != Tool.CROP && vm.detailAvailable && screenPxPerPreviewPx > 1.15f && size.width > 0) {
            visibleSourceRect(mapper, size.width.toFloat(), size.height.toFloat())
        } else null
    val detailKey = detailWanted?.let { r -> r.map { (it * 64).toInt() } } // квантування: менше зайвих запитів
    val targetSide = com.photoeditor.app.image.DetailSource.targetSide(maxOf(size.width, size.height))
    LaunchedEffect(detailKey, vm.state.retouch) {
        val want = detailWanted ?: return@LaunchedEffect
        kotlinx.coroutines.delay(250) // чекаємо, поки пальці зупиняться
        vm.requestDetail(want, targetSide)
    }
    val detailLayer = if (detailWanted != null) {
        vm.detailTile?.let { com.photoeditor.app.gl.DetailLayer(it.bitmap, it.rect, it.coverage) }
    } else null

    SideEffect {
        renderer.setBitmap(bitmap)
        renderer.setFrame(params, floatArrayOf(rect.left, rect.top, rect.width, rect.height), detailLayer)
        glHolder.view?.requestRender()
    }

    // Пауза GL-потоку разом з життєвим циклом Activity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> glHolder.view?.onPause()
                Lifecycle.Event.ON_RESUME -> glHolder.view?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val gesture: ToolGesture? = rememberToolGesture(vm, mapper, cursor)
    val latestGesture by rememberUpdatedState(gesture)
    val touchSlop = with(density) { 8.dp.toPx() }
    val splitGrab = with(density) { 28.dp.toPx() }
    val latestRect by rememberUpdatedState(rect)

    val previewDesc = stringResource(R.string.cd_preview)
    Box(
        modifier
            .semantics { contentDescription = previewDesc }
            .onSizeChanged {
                // Поворот екрана / зміна розкладки: старий зсув у пікселях уже не має сенсу
                if (it != size) { zoom = 1f; pan = Offset.Zero }
                size = it
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Шторка «до/після»: дотик біля межі перетягує її, а не діє інструментом
                    val r = latestRect
                    if (vm.splitCompare && vm.tool != Tool.CROP && r.width > 0f &&
                        abs(down.position.x - (r.left + vm.splitPos * r.width)) < splitGrab
                    ) {
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            vm.splitPos = ((ch.position.x - r.left) / r.width).coerceIn(0f, 1f)
                            ch.consume()
                        }
                        return@awaitEachGesture
                    }
                    val g = latestGesture
                    var multi = false
                    var moved = false
                    val startPos = down.position
                    var last = down.position
                    g?.start(down.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (!multi) g?.end(last, tap = !moved)
                            break
                        }
                        if (pressed.size >= 2) {
                            if (!multi) { multi = true; g?.cancel() }
                            if (vm.tool != Tool.CROP) {
                                val z = ev.calculateZoom()
                                val c = ev.calculateCentroid(useCurrent = true)
                                val pn = ev.calculatePan()
                                val nz = (zoom * z).coerceIn(1f, 10f)
                                val k = nz / zoom
                                pan = Offset(c.x - (c.x - pan.x) * k + pn.x, c.y - (c.y - pan.y) * k + pn.y)
                                zoom = nz
                                if (zoom <= 1.001f) { zoom = 1f; pan = Offset.Zero }
                            }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (!multi) {
                            val ch = pressed.first()
                            if ((ch.position - startPos).getDistance() > touchSlop) moved = true
                            if (ch.position != last) {
                                last = ch.position
                                if (moved) g?.move(ch.position)
                                ch.consume()
                            }
                        }
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    preserveEGLContextOnPause = true
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    glHolder.view = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { glHolder.view = null },
        )

        Canvas(Modifier.fillMaxSize()) {
            when (tool) {
                Tool.CROP -> drawCropOverlay(vm, rect)
                Tool.MASKS -> drawMaskOverlay(vm, mapper)
                Tool.RETOUCH -> drawRetouchOverlay(vm, mapper)
                else -> Unit
            }
            if (vm.splitCompare && tool != Tool.CROP) drawSplitOverlay(rect, vm.splitPos)
            cursor.value?.let { c ->
                val r = brushRadiusPx(vm, density.density)
                drawCircle(Color.White, r, c, style = Stroke(2.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = 0.5f), r + 2.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            }
        }

        if (vm.splitCompare && tool != Tool.CROP) SplitLabels(rect, size.width.toFloat())

        if (zoom > 1f) {
            SmallFloatingActionButton(
                onClick = { zoom = 1f; pan = Offset.Zero },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            ) { Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.cd_reset_zoom)) }
        }
    }
}

private fun brushRadiusPx(vm: EditorViewModel, density: Float): Float = when (vm.tool) {
    Tool.RETOUCH -> vm.retouchBrushSizeDp * density
    Tool.MASKS -> vm.maskBrushSizeDp * density
    else -> 0f
}

// ====================================================================== Жести інструментів

@Composable
private fun rememberToolGesture(
    vm: EditorViewModel,
    mapper: ViewMapper,
    cursor: androidx.compose.runtime.MutableState<Offset?>,
): ToolGesture? {
    val density = LocalDensity.current.density
    val latestMapper by rememberUpdatedState(mapper)
    val stroke = remember { mutableStateListOf<StrokePoint>() }

    return remember(vm.tool, vm.retouchMode, vm.selectedMask, vm.pickingCloneSource) {
        when (vm.tool) {
            Tool.CROP -> CropGesture(vm, { latestMapper }, density)
            Tool.MASKS -> MaskGesture(vm, { latestMapper }, density, cursor)
            Tool.RETOUCH -> RetouchGesture(vm, { latestMapper }, density, cursor, stroke)
            else -> null
        }
    }
}

private class CropGesture(val vm: EditorViewModel, val mapper: () -> ViewMapper, val density: Float) : ToolGesture {
    private var handle: CropHandle? = null
    private var startCrop = NRect.FULL
    private var startP = Offset.Zero

    override fun start(p: Offset) {
        val r = mapper().rect
        val c = vm.state.geometry.crop
        handle = CropMath.hit(
            p.x, p.y,
            r.left + c.l * r.width, r.top + c.t * r.height, r.left + c.r * r.width, r.top + c.b * r.height,
            24f * density,
        )
        startCrop = c
        startP = p
    }

    override fun move(p: Offset) {
        val h = handle ?: return
        val r = mapper().rect
        val g = vm.state.geometry
        val ratio = vm.aspectValue(g, g.aspect)
        val gm = mapper().gm
        val rn = ratio?.let { (it * gm.frameH / gm.frameW).toFloat() }
        val crop = CropMath.drag(startCrop, h, (p.x - startP.x) / r.width, (p.y - startP.y) / r.height, rn)
        vm.previewGeometry(g.copy(crop = crop))
    }

    override fun end(p: Offset, tap: Boolean) {
        handle = null
        vm.commit()
    }

    override fun cancel() {
        if (handle != null) vm.previewGeometry(vm.state.geometry.copy(crop = startCrop))
        handle = null
        vm.commit()
    }
}

private class MaskGesture(
    val vm: EditorViewModel,
    val mapper: () -> ViewMapper,
    val density: Float,
    val cursor: androidx.compose.runtime.MutableState<Offset?>,
) : ToolGesture {
    private enum class Part { P0, P1, LINE, CENTER, RX, RY, BRUSH }
    private var part: Part? = null
    private var startMask = vm.state.masks.getOrNull(vm.selectedMask)
    private var startP = Offset.Zero
    private val points = ArrayList<StrokePoint>()

    override fun start(p: Offset) {
        val idx = vm.selectedMask
        val m = vm.state.masks.getOrNull(idx) ?: return
        startMask = m
        startP = p
        val mp = mapper()
        val touch = 36f * density
        part = when (m.kind) {
            MaskKind.LINEAR -> {
                val a = mp.toScreen(m.x0, m.y0)
                val b = mp.toScreen(m.x1, m.y1)
                when {
                    (p - a).getDistance() < touch -> Part.P0
                    (p - b).getDistance() < touch -> Part.P1
                    else -> Part.LINE
                }
            }
            MaskKind.RADIAL -> {
                val (sx, sy) = squareScale()
                val c = mp.toScreen(m.cx, m.cy)
                val ex = mp.toScreen(m.cx + m.rx / sx, m.cy)
                val ey = mp.toScreen(m.cx, m.cy + m.ry / sy)
                when {
                    (p - ex).getDistance() < touch -> Part.RX
                    (p - ey).getDistance() < touch -> Part.RY
                    else -> Part.CENTER
                }
            }
            MaskKind.BRUSH, MaskKind.SUBJECT -> {
                points.clear()
                points += mp.toSource(p)
                cursor.value = p
                Part.BRUSH
            }
        }
        vm.maskGestureActive = true
    }

    private fun squareScale(): Pair<Float, Float> {
        val mx = max(vm.srcW, vm.srcH).toFloat()
        return vm.srcW / mx to vm.srcH / mx
    }

    override fun move(p: Offset) {
        val m = startMask ?: return
        val idx = vm.selectedMask
        val mp = mapper()
        val s = mp.toSource(p)
        val s0 = mp.toSource(startP)
        val du = s.u - s0.u
        val dv = s.v - s0.v
        val (sx, sy) = squareScale()
        val nm = when (part) {
            Part.P0 -> m.copy(x0 = s.u, y0 = s.v)
            Part.P1 -> m.copy(x1 = s.u, y1 = s.v)
            Part.LINE -> m.copy(x0 = m.x0 + du, y0 = m.y0 + dv, x1 = m.x1 + du, y1 = m.y1 + dv)
            Part.CENTER -> m.copy(cx = m.cx + du, cy = m.cy + dv)
            Part.RX -> m.copy(rx = hypot((s.u - m.cx) * sx, (s.v - m.cy) * sy).coerceAtLeast(0.01f))
            Part.RY -> m.copy(ry = hypot((s.u - m.cx) * sx, (s.v - m.cy) * sy).coerceAtLeast(0.01f))
            Part.BRUSH -> {
                cursor.value = p
                points += s
                m.copy(strokes = m.strokes + brushStroke(mp))
            }
            null -> return
        }
        vm.updateMask(idx, nm, commit = false)
    }

    private fun brushStroke(mp: ViewMapper) = com.photoeditor.app.model.BrushStroke(
        points = points.toList(),
        radius = vm.screenRadiusToSquare(vm.maskBrushSizeDp * density, mp.rect.width),
        hardness = vm.maskBrushHardness,
        erase = vm.maskBrushErase,
    )

    override fun end(p: Offset, tap: Boolean) {
        val m = startMask
        if (part == Part.BRUSH && m != null) {
            // Тап пензлем — одна «крапка»
            vm.updateMask(vm.selectedMask, m.copy(strokes = m.strokes + brushStroke(mapper())), commit = false)
        }
        cursor.value = null
        part = null
        vm.maskGestureActive = false
        vm.commit()
    }

    override fun cancel() {
        startMask?.let { vm.updateMask(vm.selectedMask, it, commit = false) }
        cursor.value = null
        part = null
        vm.maskGestureActive = false
        vm.commit()
    }
}

private class RetouchGesture(
    val vm: EditorViewModel,
    val mapper: () -> ViewMapper,
    val density: Float,
    val cursor: androidx.compose.runtime.MutableState<Offset?>,
    val stroke: MutableList<StrokePoint>,
) : ToolGesture {
    private var lastSrc: StrokePoint? = null

    private fun radius() = vm.screenRadiusToSquare(vm.retouchBrushSizeDp * density, mapper().rect.width)

    override fun start(p: Offset) {
        stroke.clear()
        val s = mapper().toSource(p)
        cursor.value = p
        stroke += s
        lastSrc = s
    }

    override fun move(p: Offset) {
        val s = mapper().toSource(p)
        cursor.value = p
        when (vm.retouchMode) {
            RetouchMode.CLONE -> if (!vm.pickingCloneSource) stroke += s
            RetouchMode.ERASE -> {
                val prev = lastSrc ?: s
                vm.eraseBrush(listOf(prev, s), radius())
                stroke += s
            }
            RetouchMode.HEAL -> Unit
        }
        lastSrc = s
    }

    override fun end(p: Offset, tap: Boolean) {
        val s = mapper().toSource(p)
        cursor.value = null
        when (vm.retouchMode) {
            RetouchMode.HEAL -> vm.heal(s.u, s.v, radius())
            RetouchMode.CLONE -> {
                if (vm.pickingCloneSource) vm.setCloneSource(s.u, s.v)
                else vm.cloneStroke(stroke.toList(), radius())
            }
            RetouchMode.ERASE -> if (tap) vm.eraseSelectObjectAt(s.u, s.v)
        }
        stroke.clear()
    }

    override fun cancel() {
        cursor.value = null
        stroke.clear()
    }
}

// ====================================================================== Малювання оверлеїв

private fun DrawScope.drawCropOverlay(vm: EditorViewModel, r: Rect) {
    val c = vm.state.geometry.crop
    val l = r.left + c.l * r.width
    val t = r.top + c.t * r.height
    val rr = r.left + c.r * r.width
    val b = r.top + c.b * r.height
    val shade = Color.Black.copy(alpha = 0.55f)
    // Затемнення поза рамкою
    drawRect(shade, Offset(0f, 0f), Size(size.width, t))
    drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
    drawRect(shade, Offset(0f, t), Size(l, b - t))
    drawRect(shade, Offset(rr, t), Size(size.width - rr, b - t))
    // Рамка та сітка третин
    drawRect(Color.White, Offset(l, t), Size(rr - l, b - t), style = Stroke(1.5.dp.toPx()))
    val grid = Color.White.copy(alpha = 0.45f)
    for (i in 1..2) {
        val x = l + (rr - l) * i / 3f
        val y = t + (b - t) * i / 3f
        drawLine(grid, Offset(x, t), Offset(x, b), 1.dp.toPx())
        drawLine(grid, Offset(l, y), Offset(rr, y), 1.dp.toPx())
    }
    // Кутові маркери
    val len = 22.dp.toPx()
    val w = 4.dp.toPx()
    for ((x, y, sx, sy) in listOf(Quad(l, t, 1f, 1f), Quad(rr, t, -1f, 1f), Quad(l, b, 1f, -1f), Quad(rr, b, -1f, -1f))) {
        drawLine(Color.White, Offset(x, y), Offset(x + len * sx, y), w)
        drawLine(Color.White, Offset(x, y), Offset(x, y + len * sy), w)
    }
}

private data class Quad(val a: Float, val b: Float, val c: Float, val d: Float)

private fun DrawScope.drawMaskOverlay(vm: EditorViewModel, mp: ViewMapper) {
    val m = vm.state.masks.getOrNull(vm.selectedMask) ?: return
    val mx = max(vm.srcW, vm.srcH).toFloat()
    val sx = vm.srcW / mx
    val sy = vm.srcH / mx
    val stroke = Stroke(2.dp.toPx())
    val dashed = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
    when (m.kind) {
        MaskKind.LINEAR -> {
            val a = mp.toScreen(m.x0, m.y0)
            val b = mp.toScreen(m.x1, m.y1)
            // Лінії, перпендикулярні напрямку градієнта: початок (100%), середина, кінець (0%)
            val d = b - a
            val len = d.getDistance().coerceAtLeast(1f)
            val n = Offset(-d.y / len, d.x / len) * (size.width + size.height)
            drawLine(Color.White, a - n, a + n, 2.dp.toPx())
            val mid = (a + b) / 2f
            drawLine(Color.White.copy(alpha = 0.6f), mid - n, mid + n, 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            drawLine(Color.White.copy(alpha = 0.8f), b - n, b + n, 1.5.dp.toPx())
            drawCircle(Accent, 9.dp.toPx(), a)
            drawCircle(Color.White, 9.dp.toPx(), b, style = stroke)
        }
        MaskKind.RADIAL -> {
            val path = Path()
            val n = 64
            for (i in 0..n) {
                val ang = i * 2 * PI / n
                val u = m.cx + (cos(ang) * m.rx).toFloat() / sx
                val v = m.cy + (sin(ang) * m.ry).toFloat() / sy
                val p = mp.toScreen(u, v)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, Color.White, style = stroke)
            // Внутрішній контур — де починається розмиття
            val inner = Path()
            val k = (1f - m.feather).coerceIn(0.02f, 1f)
            for (i in 0..n) {
                val ang = i * 2 * PI / n
                val p = mp.toScreen(m.cx + (cos(ang) * m.rx * k).toFloat() / sx, m.cy + (sin(ang) * m.ry * k).toFloat() / sy)
                if (i == 0) inner.moveTo(p.x, p.y) else inner.lineTo(p.x, p.y)
            }
            drawPath(inner, Color.White.copy(alpha = 0.6f), style = dashed)
            drawCircle(Accent, 9.dp.toPx(), mp.toScreen(m.cx, m.cy))
            drawCircle(Color.White, 8.dp.toPx(), mp.toScreen(m.cx + m.rx / sx, m.cy))
            drawCircle(Color.White, 8.dp.toPx(), mp.toScreen(m.cx, m.cy + m.ry / sy))
        }
        else -> Unit
    }
}

private fun DrawScope.drawRetouchOverlay(vm: EditorViewModel, mp: ViewMapper) {
    if (vm.retouchMode == RetouchMode.CLONE) {
        val src = vm.cloneSource ?: return
        val p = mp.toScreen(src.u, src.v)
        val r = 10.dp.toPx()
        drawCircle(Color.White, r, p, style = Stroke(2.dp.toPx()))
        drawLine(Color.White, Offset(p.x - r * 1.6f, p.y), Offset(p.x + r * 1.6f, p.y), 1.5.dp.toPx())
        drawLine(Color.White, Offset(p.x, p.y - r * 1.6f), Offset(p.x, p.y + r * 1.6f), 1.5.dp.toPx())
    }
}


/** Межа шторки «до/після»: лінія на всю висоту кадру і круглий маркер зі стрілками. */
private fun DrawScope.drawSplitOverlay(rect: Rect, pos: Float) {
    val top = maxOf(rect.top, 0f)
    val bottom = minOf(rect.bottom, size.height)
    val x = (rect.left + pos * rect.width).coerceIn(0f, size.width)
    drawLine(Color.Black.copy(alpha = 0.35f), Offset(x + 1.dp.toPx(), top), Offset(x + 1.dp.toPx(), bottom), 2.dp.toPx())
    drawLine(Color.White, Offset(x, top), Offset(x, bottom), 2.dp.toPx())
    val c = Offset(x, (top + bottom) / 2f)
    val r = 16.dp.toPx()
    drawCircle(Color.Black.copy(alpha = 0.45f), r + 2.dp.toPx(), c)
    drawCircle(Color.White, r, c)
    val a = 5.dp.toPx()
    val gap = 4.dp.toPx()
    val dark = Color(0xFF0E0F11)
    // ◀ ▶
    drawPath(Path().apply { moveTo(c.x - gap, c.y - a); lineTo(c.x - gap - a, c.y); lineTo(c.x - gap, c.y + a); close() }, dark)
    drawPath(Path().apply { moveTo(c.x + gap, c.y - a); lineTo(c.x + gap + a, c.y); lineTo(c.x + gap, c.y + a); close() }, dark)
}

/** Підписи «До» / «Після» у верхніх кутах кадру. */
@Composable
private fun SplitLabels(rect: Rect, viewWidth: Float) {
    val density = LocalDensity.current
    val topPx = maxOf(rect.top, 0f)
    Box(Modifier.fillMaxSize()) {
        val chip: @Composable (Int, Modifier) -> Unit = { res, m ->
            Text(
                stringResource(res),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = m
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        with(density) {
            chip(
                R.string.compare_before,
                Modifier.offset(x = (maxOf(rect.left, 0f)).toDp() + 8.dp, y = topPx.toDp() + 8.dp).testTag("split_before"),
            )
            Box(Modifier.width(minOf(rect.right, viewWidth).toDp() - 8.dp).offset(y = topPx.toDp() + 8.dp)) {
                chip(R.string.compare_after, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}
