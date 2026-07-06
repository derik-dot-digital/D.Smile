package com.dsmile.emulator.emu

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class ShaderMode { PIXEL, SHARP, CRT }
enum class AspectMode { FOUR_THREE, STRETCH, INTEGER }

class GameRenderer : GLSurfaceView.Renderer {
    @Volatile var shaderMode = ShaderMode.SHARP
    @Volatile var aspectMode = AspectMode.FOUR_THREE

    private val frameBuf: ByteBuffer =
        ByteBuffer.allocateDirect(320 * 240 * 2).order(ByteOrder.nativeOrder())
    private var lastSeq = -1
    private var tex = 0
    private var viewW = 1
    private var viewH = 1
    private val programs = HashMap<ShaderMode, Int>()
    private var quad: ByteBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder())

    private val vertexSrc = """
        attribute vec2 aPos;
        attribute vec2 aTex;
        varying vec2 vTex;
        void main() {
          gl_Position = vec4(aPos, 0.0, 1.0);
          vTex = aTex;
        }
    """

    private val fragPixel = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        void main() { gl_FragColor = texture2D(uTex, vTex); }
    """

    // Sharp-bilinear: nearest at integer zoom, bilinear only at pixel edges.
    private val fragSharp = """
        precision highp float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform vec2 uTexSize;
        uniform vec2 uOutSize;
        void main() {
          vec2 texel = vTex * uTexSize;
          vec2 scale = max(floor(uOutSize / uTexSize), vec2(1.0));
          vec2 texelFloor = floor(texel);
          vec2 f = texel - texelFloor;
          vec2 region = vec2(0.5) - 0.5 / scale;
          f = (clamp(f, region, vec2(1.0) - region) - region) / (1.0 - 2.0 * region);
          gl_FragColor = texture2D(uTex, (texelFloor + f) / uTexSize);
        }
    """

    // Single-pass CRT: scanlines + aperture mask + soft curvature-free bloom.
    private val fragCrt = """
        precision highp float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform vec2 uTexSize;
        uniform vec2 uOutSize;
        void main() {
          vec2 texel = vTex * uTexSize;
          vec2 texelFloor = floor(texel) + 0.5;
          vec2 f = texel - texelFloor;
          vec2 uv = (texelFloor + clamp(f * 1.6, -0.5, 0.5)) / uTexSize;
          vec3 col = texture2D(uTex, uv).rgb;
          float scan = 0.82 + 0.18 * cos(6.28318 * texel.y);
          float maskIdx = mod(gl_FragCoord.x, 3.0);
          vec3 mask = vec3(1.0);
          if (maskIdx < 1.0)      mask = vec3(1.06, 0.94, 0.94);
          else if (maskIdx < 2.0) mask = vec3(0.94, 1.06, 0.94);
          else                    mask = vec3(0.94, 0.94, 1.06);
          col = pow(col, vec3(1.12)) * scan * mask * 1.18;
          gl_FragColor = vec4(col, 1.0);
        }
    """

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        programs[ShaderMode.PIXEL] = buildProgram(vertexSrc, fragPixel)
        programs[ShaderMode.SHARP] = buildProgram(vertexSrc, fragSharp)
        programs[ShaderMode.CRT] = buildProgram(vertexSrc, fragCrt)

        val texs = IntArray(1)
        GLES20.glGenTextures(1, texs, 0)
        tex = texs[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, 320, 240, 0,
            GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        lastSeq = -1
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val seq = NativeCore.nativeGetFrame(frameBuf)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        if (seq != lastSeq) {
            lastSeq = seq
            frameBuf.position(0)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, 320, 240,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, frameBuf
            )
        }

        val filter = if (shaderMode == ShaderMode.PIXEL) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)

        // Aspect-corrected quad
        var qw = 1f
        var qh = 1f
        when (aspectMode) {
            AspectMode.STRETCH -> {}
            AspectMode.FOUR_THREE -> {
                val target = 4f / 3f
                val view = viewW.toFloat() / viewH
                if (view > target) qw = target / view else qh = view / target
            }
            AspectMode.INTEGER -> {
                val scale = maxOf(1, minOf(viewW / 320, viewH / 240))
                qw = 320f * scale / viewW
                qh = 240f * scale / viewH
            }
        }
        quad.position(0)
        quad.asFloatBuffer().put(
            floatArrayOf(
                -qw, -qh, 0f, 1f,
                qw, -qh, 1f, 1f,
                -qw, qh, 0f, 0f,
                qw, qh, 1f, 0f
            )
        )

        val prog = programs[shaderMode] ?: return
        GLES20.glUseProgram(prog)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aTex = GLES20.glGetAttribLocation(prog, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(8)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
        GLES20.glGetUniformLocation(prog, "uTexSize").let {
            if (it >= 0) GLES20.glUniform2f(it, 320f, 240f)
        }
        GLES20.glGetUniformLocation(prog, "uOutSize").let {
            if (it >= 0) GLES20.glUniform2f(it, viewW * qw, viewH * qh)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }
}
