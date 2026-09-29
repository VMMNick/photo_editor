package com.photoeditor.app.gl

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.photoeditor.app.core.AlphaMap
import com.photoeditor.app.core.CubeLut
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Детальний фрагмент для шейдера: картинка, її область (l, t, r, b в uv джерела) і покриття. */
class DetailLayer(val bitmap: Bitmap, val rect: FloatArray, val coverage: AlphaMap?)

/**
 * Спільна логіка малювання для прев'ю (GLSurfaceView) та експорту (офскрін EGL).
 * Усі методи викликати лише в потоці з активним GL-контекстом.
 */
class ImageRenderer {

    private var program = 0
    private var vbo = 0
    private var srcTex = 0
    private var whiteTex = 0
    private var blackTex = 0
    private val maskTex = IntArray(4)
    private var detailTex = 0
    private var coverageTex = 0
    private var curveTex = 0
    private var lutTex = 0
    private var dummyLutTex = 0
    private var lutKey: CubeLut? = null
    private var curveKey: ByteArray? = null
    private var detailKey: Bitmap? = null
    private var coverageKey: AlphaMap? = null
    private val maskKeys = arrayOfNulls<AlphaMap>(4)
    private val uniforms = HashMap<String, Int>()

    var srcWidth = 0
        private set
    var srcHeight = 0
        private set
    val hasSource get() = srcTex != 0

    fun init() {
        program = link(compile(GLES30.GL_VERTEX_SHADER, Shaders.VERTEX), compile(GLES30.GL_FRAGMENT_SHADER, Shaders.FRAGMENT))
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buf: FloatBuffer = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(quad).position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        whiteTex = newTexture()
        val white = ByteBuffer.allocateDirect(1).put(0xFF.toByte()).also { it.position(0) }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, white)
        // Растрова маска, карта якої ще рахується, — поки порожня (нуль), а не «вся картинка»
        blackTex = newTexture()
        val black = ByteBuffer.allocateDirect(1).put(0.toByte()).also { it.position(0) }
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, black)
        maskKeys.fill(null)
        maskTex.fill(0)
        // sampler3D має бути прив'язаний завжди — порожній 1×1×1 на випадок «без LUT»
        dummyLutTex = new3dTexture()
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, 1, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
            ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()))
    }

    fun setSource(bitmap: Bitmap) {
        if (srcTex == 0) srcTex = newTexture() else GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        // Mip-рівні: плавне зменшення в прев'ю та дешеве велике розмиття (чіткість, димка, кольоровий шум)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        srcWidth = bitmap.width
        srcHeight = bitmap.height
    }

    fun releaseSource() {
        if (srcTex != 0) GLES30.glDeleteTextures(1, intArrayOf(srcTex), 0)
        srcTex = 0
        srcWidth = 0
        srcHeight = 0
    }

    private fun updateMask(slot: Int, map: AlphaMap?) {
        if (map == null || maskKeys[slot] === map) return
        if (maskTex[slot] == 0) maskTex[slot] = newTexture() else GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex[slot])
        val buf = ByteBuffer.allocateDirect(map.data.size).order(ByteOrder.nativeOrder())
        buf.put(map.data).position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, map.width, map.height, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
        maskKeys[slot] = map
    }

    /**
     * @param flipY true — для рендеру у FBO з подальшим glReadPixels (рядки знизу вгору)
     */
    /**
     * @param detail фрагмент у повній роздільності для збільшеного прев'ю (лише для екрана, не для експорту)
     */
    fun draw(
        p: RenderParams, vx: Int, vy: Int, vw: Int, vh: Int, bg: FloatArray,
        flipY: Boolean = false, outRect: FloatArray = FULL_RECT, detail: DetailLayer? = null,
    ) {
        if (srcTex == 0) return
        for (i in 0 until 4) updateMask(i, p.maskMaps[i])

        GLES30.glViewport(vx, vy, vw, vh)
        GLES30.glUseProgram(program)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, srcTex)
        GLES30.glUniform1i(u("uTex"), 0)
        for (i in 0 until 4) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1 + i)
            val t = when {
                p.maskMaps[i] != null && maskTex[i] != 0 -> maskTex[i]
                i < p.maskCount && p.maskType[i] == 2 -> blackTex
                else -> whiteTex
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            GLES30.glUniform1i(u("uMask$i"), 1 + i)
        }

        bindDetail(detail)
        bindCurve(p.curveLut)
        bindLut(p.lut, p.lutAmount)
        GLES30.glUniform1f(u("uClipWarn"), if (p.clipWarning) 1f else 0f)
        GLES30.glUniform1f(u("uFlipY"), if (flipY) -1f else 1f)
        GLES30.glUniform4f(u("uOutRect"), outRect[0], outRect[1], outRect[2], outRect[3])
        GLES30.glUniformMatrix3fv(u("uXform"), 1, false, p.xform, 0)
        GLES30.glUniform2f(u("uImgScale"), p.imgScale[0], p.imgScale[1])
        GLES30.glUniform2f(u("uOutSize"), p.outW, p.outH)
        GLES30.glUniform3f(u("uBg"), bg[0], bg[1], bg[2])
        GLES30.glUniform1f(u("uBypass"), if (p.bypass) 1f else 0f)
        GLES30.glUniform1f(u("uSplit"), p.splitX)
        GLES30.glUniform1f(u("uLensK"), p.lensK)
        GLES30.glUniform1f(u("uLensN"), p.lensN)
        GLES30.glUniform1f(u("uLensVig"), p.a.lensVignette)
        GLES30.glUniform1f(u("uChromatic"), p.a.chromatic)

        val a = p.a
        GLES30.glUniform1f(u("uExposure"), a.exposure)
        GLES30.glUniform1f(u("uBrightness"), a.brightness)
        GLES30.glUniform1f(u("uContrast"), a.contrast)
        GLES30.glUniform1f(u("uHighlights"), a.highlights)
        GLES30.glUniform1f(u("uShadows"), a.shadows)
        GLES30.glUniform1f(u("uWhites"), a.whites)
        GLES30.glUniform1f(u("uBlacks"), a.blacks)
        GLES30.glUniform1f(u("uTemp"), a.temperature)
        GLES30.glUniform1f(u("uTint"), a.tint)
        GLES30.glUniform1f(u("uSaturation"), a.saturation)
        GLES30.glUniform1f(u("uVibrance"), a.vibrance)
        GLES30.glUniform1f(u("uVignette"), a.vignette)
        GLES30.glUniform1f(u("uGrain"), a.grain)
        GLES30.glUniform1f(u("uFade"), a.fade)
        GLES30.glUniform1f(u("uSharpen"), a.sharpen)
        GLES30.glUniform1f(u("uClarity"), a.clarity)
        GLES30.glUniform1f(u("uDehaze"), a.dehaze)
        GLES30.glUniform1f(u("uNoise"), a.noise)
        GLES30.glUniform1f(u("uColorNoise"), a.colorNoise)
        GLES30.glUniform1f(u("uSeed"), p.seed)

        val hsl = FloatArray(24)
        a.hsl.forEachIndexed { i, h -> hsl[i * 3] = h.hue; hsl[i * 3 + 1] = h.sat; hsl[i * 3 + 2] = h.lum }
        GLES30.glUniform3fv(u("uHsl"), 8, hsl, 0)

        GLES30.glUniform3fv(u("uGradeS"), 1, p.gradeS, 0)
        GLES30.glUniform3fv(u("uGradeM"), 1, p.gradeM, 0)
        GLES30.glUniform3fv(u("uGradeH"), 1, p.gradeH, 0)
        GLES30.glUniform3fv(u("uGradeLum"), 1, p.gradeLum, 0)
        GLES30.glUniform1f(u("uGradeBalance"), a.grading.balance)
        GLES30.glUniform1f(u("uGradeBlend"), a.grading.blending)

        GLES30.glUniform1i(u("uMaskCount"), p.maskCount)
        GLES30.glUniform1iv(u("uMaskType"), 4, p.maskType, 0)
        GLES30.glUniform4fv(u("uMaskA"), 4, p.maskA, 0)
        GLES30.glUniform4fv(u("uMaskB"), 4, p.maskB, 0)
        GLES30.glUniform1fv(u("uMaskInv"), 4, p.maskInv, 0)
        GLES30.glUniform4fv(u("uMaskAdjA"), 4, p.maskAdjA, 0)
        GLES30.glUniform4fv(u("uMaskAdjB"), 4, p.maskAdjB, 0)
        GLES30.glUniform1i(u("uShowMask"), p.showMask)
        GLES30.glUniform1iv(u("uMaskRangeMode"), 4, p.maskRangeMode, 0)
        GLES30.glUniform4fv(u("uMaskRange"), 4, p.maskRange, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun bindLut(lut: CubeLut?, amount: Float) {
        if (lut != null && lut !== lutKey) {
            if (lutTex == 0) lutTex = new3dTexture() else GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
            val bytes = lut.toRgba8()
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes).position(0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, lut.size, lut.size, lut.size, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            lutKey = lut
        }
        val on = lut != null && lutTex != 0 && amount > 0f
        GLES30.glActiveTexture(GLES30.GL_TEXTURE8)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, if (on) lutTex else dummyLutTex)
        GLES30.glUniform1i(u("uLut"), 8)
        GLES30.glUniform1f(u("uLutAmount"), if (on) amount else 0f)
        GLES30.glUniform1f(u("uLutSize"), (lut?.size ?: 2).toFloat())
    }

    private fun new3dTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun bindCurve(lut: ByteArray?) {
        if (lut != null && (curveKey == null || !curveKey.contentEquals(lut))) {
            if (curveTex == 0) curveTex = newTexture() else GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTex)
            val buf = ByteBuffer.allocateDirect(lut.size).order(ByteOrder.nativeOrder())
            buf.put(lut).position(0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, lut.size / 4, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            curveKey = lut.copyOf()
        }
        val on = lut != null && curveTex != 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE7)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (on) curveTex else whiteTex)
        GLES30.glUniform1i(u("uCurve"), 7)
        GLES30.glUniform1f(u("uCurveOn"), if (on) 1f else 0f)
    }

    private fun bindDetail(d: DetailLayer?) {
        val bmp = d?.bitmap?.takeIf { !it.isRecycled }
        if (bmp != null && bmp !== detailKey) {
            if (detailTex == 0) detailTex = newTexture() else GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, detailTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
            detailKey = bmp
        }
        val cov = d?.coverage
        if (cov != null && cov !== coverageKey) {
            if (coverageTex == 0) coverageTex = newTexture() else GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coverageTex)
            val buf = ByteBuffer.allocateDirect(cov.data.size).order(ByteOrder.nativeOrder())
            buf.put(cov.data).position(0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, cov.width, cov.height, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
            coverageKey = cov
        }
        val on = bmp != null && detailTex != 0
        GLES30.glActiveTexture(GLES30.GL_TEXTURE5)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (on) detailTex else whiteTex)
        GLES30.glUniform1i(u("uDetail"), 5)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE6)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (on && cov != null) coverageTex else blackTex)
        GLES30.glUniform1i(u("uCoverage"), 6)
        GLES30.glUniform1f(u("uDetailOn"), if (on) 1f else 0f)
        val r = d?.rect ?: FULL_RECT_LTRB
        GLES30.glUniform4f(u("uDetailRect"), r[0], r[1], r[2] - r[0], r[3] - r[1])
    }

    fun release() {
        val tex = intArrayOf(srcTex, whiteTex, blackTex, detailTex, coverageTex, curveTex, lutTex, dummyLutTex, *maskTex).filter { it != 0 }.toIntArray()
        if (tex.isNotEmpty()) GLES30.glDeleteTextures(tex.size, tex, 0)
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (program != 0) GLES30.glDeleteProgram(program)
        srcTex = 0; whiteTex = 0; blackTex = 0; vbo = 0; program = 0
        detailTex = 0; coverageTex = 0; detailKey = null; coverageKey = null
        curveTex = 0; curveKey = null
        lutTex = 0; dummyLutTex = 0; lutKey = null
        maskTex.fill(0); maskKeys.fill(null); uniforms.clear()
    }

    // ------------------------------------------------------------------

    private fun u(name: String): Int = uniforms.getOrPut(name) {
        // Для масивів GL повертає location першого елемента за ім'ям без [0]
        GLES30.glGetUniformLocation(program, name)
    }

    private fun newTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            Log.e(TAG, "Shader compile error: $log")
            throw IllegalStateException("Shader compile error: $log")
        }
        return s
    }

    private fun link(vs: Int, fs: Int): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            GLES30.glDeleteProgram(p)
            throw IllegalStateException("Program link error: $log")
        }
        return p
    }

    companion object {
        private const val TAG = "ImageRenderer"
        private val FULL_RECT = floatArrayOf(0f, 0f, 1f, 1f)
        private val FULL_RECT_LTRB = floatArrayOf(0f, 0f, 1f, 1f)

        fun maxTextureSize(): Int {
            val v = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0)
            return if (v[0] > 0) v[0] else 4096
        }
    }
}
