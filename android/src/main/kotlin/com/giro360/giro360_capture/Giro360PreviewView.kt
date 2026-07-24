package com.giro360.giro360_capture

import android.app.Activity
import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

internal class Giro360PreviewFactory(
    private val coordinator: Giro360CaptureCoordinator,
    private val videoCoordinator: Giro360VideoFallbackCoordinator,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    override fun create(context: Context, viewId: Int, args: Any?): PlatformView =
        Giro360PreviewView(context, coordinator, videoCoordinator)
}

private class Giro360PreviewView(
    context: Context,
    private val coordinator: Giro360CaptureCoordinator,
    private val videoCoordinator: Giro360VideoFallbackCoordinator,
) : PlatformView {
    private val rootView = FrameLayout(context)
    private val surfaceView = GLSurfaceView(context)
    private val cameraXPreview = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        visibility = View.GONE
    }

    init {
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setRenderer(CameraBackgroundRenderer(context, surfaceView, coordinator))
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.onResume()
        rootView.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        rootView.addView(
            cameraXPreview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        coordinator.attachPreview(surfaceView)
        videoCoordinator.attachPreview(cameraXPreview)
    }

    override fun getView(): View = rootView

    override fun dispose() {
        coordinator.detachPreview(surfaceView)
        videoCoordinator.detachPreview(cameraXPreview)
        surfaceView.onPause()
    }
}

private class CameraBackgroundRenderer(
    private val context: Context,
    private val surfaceView: GLSurfaceView,
    private val coordinator: Giro360CaptureCoordinator,
) : GLSurfaceView.Renderer {
    private val vertexBuffer = floatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        ),
    )
    private val inputTextureBuffer = floatBuffer(FloatArray(8))
    private val outputTextureBuffer = floatBuffer(FloatArray(8))

    private var textureId = 0
    private var program = 0
    private var positionAttribute = 0
    private var textureAttribute = 0
    private var textureUniform = 0
    private var geometryInitialized = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        textureId = createExternalTexture()
        coordinator.setCameraTextureName(textureId)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        textureAttribute = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        coordinator.setDisplayGeometry(displayRotation(), width, height)
        geometryInitialized = false
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val frame = coordinator.updateFrame() ?: return
        if (frame.timestamp == 0L) return
        if (!geometryInitialized || frame.hasDisplayGeometryChanged()) {
            updateTextureCoordinates(frame)
            geometryInitialized = true
        }
        drawCamera()
        surfaceView.postInvalidateOnAnimation()
    }

    private fun updateTextureCoordinates(frame: Frame) {
        vertexBuffer.position(0)
        inputTextureBuffer.position(0)
        inputTextureBuffer.put(
            floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f,
            ),
        )
        inputTextureBuffer.position(0)
        outputTextureBuffer.position(0)
        frame.transformCoordinates2d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
            inputTextureBuffer,
            Coordinates2d.TEXTURE_NORMALIZED,
            outputTextureBuffer,
        )
        outputTextureBuffer.position(0)
    }

    private fun drawCamera() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            positionAttribute,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer,
        )
        outputTextureBuffer.position(0)
        GLES20.glVertexAttribPointer(
            textureAttribute,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            outputTextureBuffer,
        )
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glEnableVertexAttribArray(textureAttribute)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(textureAttribute)
        GLES20.glDepthMask(true)
    }

    private fun displayRotation(): Int {
        val activity = context as? Activity ?: return Surface.ROTATION_0
        return activity.windowManager.defaultDisplay.rotation
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return textures[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertexShader)
        GLES20.glAttachShader(result, fragmentShader)
        GLES20.glLinkProgram(result)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] == GLES20.GL_TRUE) {
            "Falha ao vincular o preview ARCore: ${GLES20.glGetProgramInfoLog(result)}"
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] == GLES20.GL_TRUE) {
            "Falha no shader do preview ARCore: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_Texture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """

        private fun floatBuffer(values: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
