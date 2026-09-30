package tw.avianjay.airplaydroid.cast

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Carries decoded pictures from a video decoder to an encoder's input surface
 * through OpenGL.
 *
 * A decoder cannot render straight into an encoder's surface in general: the two
 * sizes differ whenever the picture is scaled down, and a rotated source (a phone
 * video with a 90° rotation tag) must be turned upright, because AirPlay players
 * ignore the tag in a transport stream. Drawing each frame as an external
 * texture on a quad does both, and converts whatever colour layout the decoder
 * produces, which is the part that differs most between devices.
 *
 * Must be created, used and released on one thread: EGL contexts are
 * thread-bound.
 */
class GlFrameCopier(encoderSurface: Surface, private val rotationDegrees: Int) {

    private val display: EGLDisplay
    private val context: EGLContext
    private val surface: EGLSurface

    private val program: Int
    private val texture: Int
    private val positionHandle: Int
    private val texCoordHandle: Int
    private val mvpHandle: Int
    private val stHandle: Int

    private val mvp = FloatArray(16)
    private val st = FloatArray(16)

    /**
     * x, y, s, t for a full-viewport triangle strip. Per instance, not shared:
     * drawing moves its position, and a converter restarted by a seek overlaps
     * the one it replaces for a moment, on another thread.
     */
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        ))
        position(0)
    }

    private val callbackThread = HandlerThread("converter-frames").apply { start() }
    private val frameLock = ReentrantLock()
    private val frameArrived = frameLock.newCondition()
    private var frameAvailable = false

    private val surfaceTexture: SurfaceTexture

    /** What the decoder renders into. */
    val decoderSurface: Surface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            // Without it the encoder's surface may not accept what GL draws.
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "no recordable EGL config"
        }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        checkEgl("eglCreateContext")
        surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        checkEgl("eglCreateWindowSurface")
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        program = buildProgram()
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        stHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(texture)
        // On a thread of its own: the converter thread blocks in awaitFrame, so
        // a callback posted to it would never run.
        surfaceTexture.setOnFrameAvailableListener({
            frameLock.withLock {
                frameAvailable = true
                frameArrived.signalAll()
            }
        }, Handler(callbackThread.looper))
        decoderSurface = Surface(surfaceTexture)

        // The quad is turned by the source's rotation, so the picture arrives upright.
        Matrix.setRotateM(mvp, 0, -rotationDegrees.toFloat(), 0f, 0f, 1f)
    }

    /**
     * Waits for the frame the decoder was just told to render, then draws it into
     * the encoder's surface stamped with [presentationUs]. Returns false if the
     * frame never arrived, which the caller treats as a dropped frame.
     */
    fun copyFrame(presentationUs: Long, width: Int, height: Int): Boolean {
        val arrived = frameLock.withLock {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FRAME_TIMEOUT_MS)
            while (!frameAvailable) {
                val left = deadline - System.nanoTime()
                if (left <= 0) break
                frameArrived.awaitNanos(left)
            }
            frameAvailable.also { frameAvailable = false }
        }
        if (!arrived) return false
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(st)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)

        quad.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, STRIDE, quad)
        GLES20.glEnableVertexAttribArray(positionHandle)
        quad.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, STRIDE, quad)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(stHandle, 1, false, st, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGLExt.eglPresentationTimeANDROID(display, surface, presentationUs * 1000)
        return EGL14.eglSwapBuffers(display, surface)
    }

    fun release() {
        runCatching { decoderSurface.release() }
        runCatching { surfaceTexture.release() }
        runCatching { GLES20.glDeleteProgram(program) }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        callbackThread.quitSafely()
    }

    private fun buildProgram(): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: " + GLES20.glGetProgramInfoLog(program) }
        return program
    }

    private fun checkEgl(what: String) {
        val error = EGL14.eglGetError()
        check(error == EGL14.EGL_SUCCESS) { "$what: EGL error 0x" + Integer.toHexString(error) }
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        /** A decoder that has not delivered a rendered frame in this long has lost it. */
        const val FRAME_TIMEOUT_MS = 2_500L
        const val STRIDE = 4 * 4

        const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}
