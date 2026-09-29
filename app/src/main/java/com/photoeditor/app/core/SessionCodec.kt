package com.photoeditor.app.core

import com.photoeditor.app.model.Adjustments
import com.photoeditor.app.model.AspectRatio
import com.photoeditor.app.model.BrushStroke
import com.photoeditor.app.model.CloneOp
import com.photoeditor.app.model.ColorGrading
import com.photoeditor.app.model.CurveChannel
import com.photoeditor.app.model.CurvePoint
import com.photoeditor.app.model.Curves
import com.photoeditor.app.model.ToneCurve
import com.photoeditor.app.model.EditState
import com.photoeditor.app.model.EraseOp
import com.photoeditor.app.model.Geometry
import com.photoeditor.app.model.GradeWheel
import com.photoeditor.app.model.HealOp
import com.photoeditor.app.model.HslAdjust
import com.photoeditor.app.model.LocalAdjust
import com.photoeditor.app.model.Mask
import com.photoeditor.app.model.MaskKind
import com.photoeditor.app.model.MaskRange
import com.photoeditor.app.model.RangeMode
import com.photoeditor.app.model.NRect
import com.photoeditor.app.model.RetouchOp
import com.photoeditor.app.model.StrokePoint
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.IdentityHashMap
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Що крім EditState треба, щоб відновити сесію: інформація про відкрите фото. */
data class SessionMeta(
    val displayName: String,
    val isRaw: Boolean,
    val fullWidth: Int,
    val fullHeight: Int,
    val usedEmbeddedPreview: Boolean,
    /** Ім'я робочої копії оригіналу в папці сесії. */
    val sourceFile: String,
)

/**
 * Серіалізація сесії: JSON для параметрів + окремі двійкові файли для растрових карт
 * (маски пензля/AI, області AI-ластика). Карти ідентифікуються за індексом у списку `maps`,
 * однакові об'єкти зберігаються один раз.
 */
object SessionCodec {

    const val VERSION = 1

    class Encoded(val json: String, val maps: List<AlphaMap>)

    // ============================================================ encode

    /**
     * @param undo / redo стеки історії (останні кроки) — щоб «Скасувати» працювало і після відновлення
     * @param mapName викликається для кожної нової карти (у порядку індексів) і повертає ім'я її файлу;
     *   імена записуються в JSON («mapFiles»), тож стан і список карт змінюються одним атомарним записом
     */
    fun encode(
        meta: SessionMeta,
        state: EditState,
        undo: List<EditState> = emptyList(),
        redo: List<EditState> = emptyList(),
        mapName: ((AlphaMap) -> String)? = null,
    ): Encoded {
        val maps = ArrayList<AlphaMap>()
        val names = ArrayList<String>()
        val index = IdentityHashMap<AlphaMap, Int>()
        fun mapRef(m: AlphaMap?): Any? = m?.let {
            index.getOrPut(it) {
                maps += it
                mapName?.let { f -> names += f(it) }
                maps.size - 1
            }
        }

        val root = linkedMapOf<String, Any?>(
            "version" to VERSION,
            "meta" to linkedMapOf(
                "displayName" to meta.displayName,
                "isRaw" to meta.isRaw,
                "fullWidth" to meta.fullWidth,
                "fullHeight" to meta.fullHeight,
                "usedEmbeddedPreview" to meta.usedEmbeddedPreview,
                "sourceFile" to meta.sourceFile,
            ),
        )
        root.putAll(stateTree(state, ::mapRef))
        if (undo.isNotEmpty()) root["undo"] = undo.map { stateTree(it, ::mapRef) }
        if (redo.isNotEmpty()) root["redo"] = redo.map { stateTree(it, ::mapRef) }
        if (mapName != null) root["mapFiles"] = names
        return Encoded(MiniJson.write(root), maps)
    }

    private fun stateTree(state: EditState, mapRef: (AlphaMap?) -> Any?): LinkedHashMap<String, Any?> = linkedMapOf(
        "adjust" to adjust(state.adjust),
        "geometry" to geometry(state.geometry),
        "masks" to state.masks.map { m ->
            linkedMapOf(
                // id — рядком: Long з nanoTime не вміщається точно в Double
                "id" to m.id.toString(), "kind" to m.kind.name,
                "x0" to m.x0, "y0" to m.y0, "x1" to m.x1, "y1" to m.y1,
                "cx" to m.cx, "cy" to m.cy, "rx" to m.rx, "ry" to m.ry, "angle" to m.angle, "feather" to m.feather,
                "strokes" to m.strokes.map(::stroke),
                "subject" to mapRef(m.subject),
                "invert" to m.invert,
                "adjust" to local(m.adjust),
                "range" to linkedMapOf(
                    "mode" to m.range.mode.name, "lo" to m.range.lo, "hi" to m.range.hi,
                    "hue" to m.range.hue, "hueWidth" to m.range.hueWidth, "smooth" to m.range.smooth,
                ),
            )
        },
        "retouch" to state.retouch.map { op ->
            when (op) {
                is HealOp -> linkedMapOf("type" to "heal", "u" to op.u, "v" to op.v, "radius" to op.radius)
                is CloneOp -> linkedMapOf("type" to "clone", "stroke" to stroke(op.stroke), "du" to op.du, "dv" to op.dv)
                is EraseOp -> linkedMapOf("type" to "erase", "mask" to mapRef(op.mask))
            }
        },
        "presetId" to state.presetId,
        "presetAmount" to state.presetAmount,
    )

    private fun adjust(a: Adjustments) = linkedMapOf(
        "exposure" to a.exposure, "brightness" to a.brightness, "contrast" to a.contrast,
        "highlights" to a.highlights, "shadows" to a.shadows, "whites" to a.whites, "blacks" to a.blacks,
        "temperature" to a.temperature, "tint" to a.tint, "saturation" to a.saturation, "vibrance" to a.vibrance,
        "hsl" to a.hsl.map { listOf(it.hue, it.sat, it.lum) },
        "grading" to linkedMapOf(
            "s" to wheel(a.grading.shadows), "m" to wheel(a.grading.midtones), "h" to wheel(a.grading.highlights),
            "blending" to a.grading.blending, "balance" to a.grading.balance,
        ),
        "vignette" to a.vignette, "grain" to a.grain, "fade" to a.fade,
        "sharpen" to a.sharpen, "clarity" to a.clarity, "dehaze" to a.dehaze, "noise" to a.noise, "colorNoise" to a.colorNoise,
        "lensVignette" to a.lensVignette, "chromatic" to a.chromatic,
        "lut" to a.lut, "lutAmount" to a.lutAmount,
        "curves" to CurveChannel.entries.associate { ch -> ch.name.lowercase() to a.curves[ch].points.map { listOf(it.x, it.y) } },
    )

    private fun wheel(w: GradeWheel) = listOf(w.hue, w.sat, w.lum)

    private fun geometry(g: Geometry) = linkedMapOf(
        "quarterTurns" to g.quarterTurns, "flipH" to g.flipH, "flipV" to g.flipV,
        "straighten" to g.straighten, "perspectiveV" to g.perspectiveV, "perspectiveH" to g.perspectiveH,
        "distortion" to g.distortion,
        "crop" to listOf(g.crop.l, g.crop.t, g.crop.r, g.crop.b),
        "aspect" to g.aspect.name,
    )

    private fun local(a: LocalAdjust) = listOf(a.exposure, a.contrast, a.highlights, a.shadows, a.saturation, a.temperature, a.tint)

    private fun stroke(s: BrushStroke) = linkedMapOf(
        // Точки плоским масивом [u0, v0, u1, v1, …] — компактніше
        "points" to s.points.flatMap { listOf(it.u, it.v) },
        "radius" to s.radius, "hardness" to s.hardness, "erase" to s.erase,
    )

    // ============================================================ decode

    class Decoded(
        val meta: SessionMeta,
        val state: EditState,
        val undo: List<EditState> = emptyList(),
        val redo: List<EditState> = emptyList(),
    )

    /** @throws IllegalArgumentException якщо файл пошкоджений або з несумісної версії. */
    fun decode(json: String, maps: List<AlphaMap>): Decoded {
        val root = MiniJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("Not an object")
        val version = (root["version"] as? Double)?.toInt()
        require(version == VERSION) { "Unsupported session version $version" }
        fun map(ref: Any?): AlphaMap? = (ref as? Double)?.toInt()?.let { maps.getOrNull(it) ?: throw IllegalArgumentException("Missing map $it") }

        val m = root.obj("meta")
        val meta = SessionMeta(
            displayName = m.str("displayName"),
            isRaw = m.bool("isRaw"),
            fullWidth = m.int("fullWidth"),
            fullHeight = m.int("fullHeight"),
            usedEmbeddedPreview = m.bool("usedEmbeddedPreview"),
            sourceFile = m.str("sourceFile"),
        )
        val state = stateFrom(root, ::map)
        // Історія — «бонус»: якщо вона пошкоджена, відновлюємо хоча б поточний стан
        val undo = runCatching { (root["undo"] as? List<*>)?.map { stateFrom(it as Map<*, *>, ::map) } }.getOrNull().orEmpty()
        val redo = runCatching { (root["redo"] as? List<*>)?.map { stateFrom(it as Map<*, *>, ::map) } }.getOrNull().orEmpty()
        return Decoded(meta, state, undo, redo)
    }

    /** Імена файлів карт, записані разом зі станом (null — формат 1.1, де вони були в maps.txt). */
    fun mapFiles(json: String): List<String>? =
        ((MiniJson.parse(json) as? Map<*, *>)?.get("mapFiles") as? List<*>)?.map { it as String }

    private fun stateFrom(root: Map<*, *>, map: (Any?) -> AlphaMap?): EditState {
        return EditState(
            adjust = adjustFrom(root.obj("adjust")),
            geometry = geometryFrom(root.obj("geometry")),
            masks = root.list("masks").map { raw ->
                val o = raw as Map<*, *>
                Mask(
                    id = o.str("id").toLong(),
                    kind = MaskKind.valueOf(o.str("kind")),
                    x0 = o.f("x0"), y0 = o.f("y0"), x1 = o.f("x1"), y1 = o.f("y1"),
                    cx = o.f("cx"), cy = o.f("cy"), rx = o.f("rx"), ry = o.f("ry"),
                    angle = o.f("angle"), feather = o.f("feather"),
                    strokes = o.list("strokes").map { strokeFrom(it as Map<*, *>) },
                    subject = map(o["subject"]),
                    invert = o.bool("invert"),
                    adjust = localFrom(o.list("adjust")),
                    range = rangeFrom(o["range"] as? Map<*, *>),
                )
            },
            retouch = root.list("retouch").map { raw ->
                val o = raw as Map<*, *>
                when (val t = o.str("type")) {
                    "heal" -> HealOp(o.f("u"), o.f("v"), o.f("radius"))
                    "clone" -> CloneOp(strokeFrom(o.obj("stroke")), o.f("du"), o.f("dv"))
                    "erase" -> EraseOp(map(o["mask"]) ?: throw IllegalArgumentException("Erase without mask"))
                    else -> throw IllegalArgumentException("Unknown retouch op $t")
                } as RetouchOp
            },
            presetId = root["presetId"] as? String,
            presetAmount = (root["presetAmount"] as? Double)?.toFloat() ?: 1f,
        )
    }

    private fun adjustFrom(o: Map<*, *>): Adjustments {
        val g = o.obj("grading")
        return Adjustments(
            exposure = o.f("exposure"), brightness = o.f("brightness"), contrast = o.f("contrast"),
            highlights = o.f("highlights"), shadows = o.f("shadows"), whites = o.f("whites"), blacks = o.f("blacks"),
            temperature = o.f("temperature"), tint = o.f("tint"), saturation = o.f("saturation"), vibrance = o.f("vibrance"),
            hsl = o.list("hsl").map { val l = floats(it); HslAdjust(l[0], l[1], l[2]) },
            grading = ColorGrading(
                shadows = wheelFrom(g["s"]), midtones = wheelFrom(g["m"]), highlights = wheelFrom(g["h"]),
                blending = g.f("blending"), balance = g.f("balance"),
            ),
            vignette = o.f("vignette"), grain = o.f("grain"), fade = o.f("fade"),
            curves = curvesFrom(o["curves"] as? Map<*, *>),
            // Деталізація з'явилася в 1.3 — у старіших сесіях ключів немає
            sharpen = o.fOr("sharpen"), clarity = o.fOr("clarity"), dehaze = o.fOr("dehaze"),
            noise = o.fOr("noise"), colorNoise = o.fOr("colorNoise"),
            lensVignette = o.fOr("lensVignette"), chromatic = o.fOr("chromatic"),
            lut = o["lut"] as? String, lutAmount = o.fOr("lutAmount", 1f),
        )
    }

    /** Криві з'явилися в 1.2 — у старіших сесіях ключа немає, тоді прямі. */
    private fun curvesFrom(o: Map<*, *>?): Curves {
        o ?: return Curves()
        var c = Curves()
        for (ch in CurveChannel.entries) {
            val pts = (o[ch.name.lowercase()] as? List<*>)?.map { val f = floats(it); CurvePoint(f[0], f[1]) } ?: continue
            require(pts.size >= 2 && pts.zipWithNext().all { (a, b) -> a.x < b.x }) { "Bad curve ${ch.name}" }
            c = c.with(ch, ToneCurve(pts))
        }
        return c
    }

    /** Діапазони масок — з 1.3; у старіших сесіях їх немає. */
    private fun rangeFrom(o: Map<*, *>?): MaskRange {
        o ?: return MaskRange()
        return MaskRange(
            mode = runCatching { RangeMode.valueOf(o.str("mode")) }.getOrDefault(RangeMode.NONE),
            lo = o.fOr("lo"), hi = o.fOr("hi", 1f), hue = o.fOr("hue", 30f),
            hueWidth = o.fOr("hueWidth", 30f), smooth = o.fOr("smooth", 0.3f),
        )
    }

    private fun wheelFrom(v: Any?): GradeWheel = floats(v).let { GradeWheel(it[0], it[1], it[2]) }

    private fun geometryFrom(o: Map<*, *>): Geometry {
        val c = floats(o["crop"])
        return Geometry(
            quarterTurns = o.int("quarterTurns"), flipH = o.bool("flipH"), flipV = o.bool("flipV"),
            straighten = o.f("straighten"), perspectiveV = o.f("perspectiveV"), perspectiveH = o.f("perspectiveH"),
            crop = NRect(c[0], c[1], c[2], c[3]),
            aspect = runCatching { AspectRatio.valueOf(o.str("aspect")) }.getOrDefault(AspectRatio.FREE),
            distortion = o.fOr("distortion"),
        )
    }

    private fun localFrom(l: List<*>): LocalAdjust {
        val f = floats(l)
        return LocalAdjust(f[0], f[1], f[2], f[3], f[4], f[5], f[6])
    }

    private fun strokeFrom(o: Map<*, *>): BrushStroke {
        val p = floats(o["points"])
        return BrushStroke(
            points = List(p.size / 2) { StrokePoint(p[it * 2], p[it * 2 + 1]) },
            radius = o.f("radius"), hardness = o.f("hardness"), erase = o.bool("erase"),
        )
    }

    private fun floats(v: Any?): FloatArray = (v as? List<*>)?.map { (it as Double).toFloat() }?.toFloatArray()
        ?: throw IllegalArgumentException("Expected number array")

    private fun Map<*, *>.obj(k: String) = this[k] as? Map<*, *> ?: throw IllegalArgumentException("Missing object $k")
    private fun Map<*, *>.list(k: String) = this[k] as? List<*> ?: throw IllegalArgumentException("Missing array $k")
    private fun Map<*, *>.str(k: String) = this[k] as? String ?: throw IllegalArgumentException("Missing string $k")
    private fun Map<*, *>.bool(k: String) = this[k] as? Boolean ?: throw IllegalArgumentException("Missing bool $k")
    private fun Map<*, *>.f(k: String) = (this[k] as? Double)?.toFloat() ?: throw IllegalArgumentException("Missing number $k")
    private fun Map<*, *>.fOr(k: String, def: Float = 0f) = (this[k] as? Double)?.toFloat() ?: def
    private fun Map<*, *>.int(k: String) = (this[k] as? Double)?.toInt() ?: throw IllegalArgumentException("Missing number $k")

    // ============================================================ растрові карти

    private const val MAP_MAGIC = 0x414D4150 // "AMAP"

    /** Карта маски → байти: заголовок + deflate (маски здебільшого порожні, стискаються в десятки разів). */
    fun encodeMap(m: AlphaMap): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(MAP_MAGIC)
            out.writeInt(m.width)
            out.writeInt(m.height)
            out.flush()
            DeflaterOutputStream(bos).use { it.write(m.data) }
        }
        return bos.toByteArray()
    }

    fun decodeMap(bytes: ByteArray): AlphaMap {
        val input = DataInputStream(bytes.inputStream())
        require(input.readInt() == MAP_MAGIC) { "Not a mask file" }
        val w = input.readInt()
        val h = input.readInt()
        require(w in 1..16384 && h in 1..16384) { "Bad mask size $w×$h" }
        val data = ByteArray(w * h)
        InflaterInputStream(input).use { inf ->
            var off = 0
            while (off < data.size) {
                val n = inf.read(data, off, data.size - off)
                require(n > 0) { "Truncated mask" }
                off += n
            }
        }
        return AlphaMap(w, h, data)
    }
}
