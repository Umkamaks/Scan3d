package com.example.scan3d

import android.content.Context
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.google.ar.core.Camera
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Рисует поток камеры, собирает облако точек из Depth API + цвета камеры. */
class ArRenderer(private val ctx: Context) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
    @Volatile var active = false
    @Volatile var scanning = false
    @Volatile private var resetRequested = false
    var onStats: ((Int, Boolean) -> Unit)? = null

    private val lock = Any()
    private var cloud = PointCloud()
    private val main = Handler(Looper.getMainLooper())

    private var bgTex = 0; private var bgProg = 0; private var bgPos = 0; private var bgTc = 0
    private var ptProg = 0; private var ptMvp = 0; private var ptSize = 0; private var ptPos = 0; private var ptCol = 0
    private val vbo = PointVbo()
    private var vw = 1; private var vh = 1
    private var viewportChanged = true
    private var texSet = false
    private val quad = Gl.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex = Gl.floatBuffer(FloatArray(8))
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16)
    private var pend = FloatArray(6 * 30000); private var pendN = 0
    private var lastCollect = 0L; private var lastStats = 0L
    private val density = ctx.resources.displayMetrics.density

    fun reset() {
        synchronized(lock) { cloud = PointCloud() }
        resetRequested = true
    }

    fun snapshot(): PointCloud = synchronized(lock) {
        PointCloud().also {
            it.setData(cloud.pos.copyOf(cloud.count * 3), cloud.col.copyOf(cloud.count * 3), cloud.count)
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        bgTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        bgProg = Gl.program(BG_VS, BG_FS)
        bgPos = GLES20.glGetAttribLocation(bgProg, "a_Position")
        bgTc = GLES20.glGetAttribLocation(bgProg, "a_TexCoord")
        ptProg = Gl.program(Gl.POINT_VS, Gl.POINT_FS)
        ptMvp = GLES20.glGetUniformLocation(ptProg, "u_MVP")
        ptSize = GLES20.glGetUniformLocation(ptProg, "u_Size")
        ptPos = GLES20.glGetAttribLocation(ptProg, "a_Pos")
        ptCol = GLES20.glGetAttribLocation(ptProg, "a_Color")

        vbo.create()
        vbo.allocate(PointCloud.MAX)
        texSet = false
        // восстановить VBO, если контекст был пересоздан
        synchronized(lock) {
            val n = cloud.count
            var i = 0
            val chunk = 50_000
            while (i < n) {
                val end = minOf(n, i + chunk)
                val tmp = FloatArray((end - i) * 6)
                for (k in i until end) {
                    val o = (k - i) * 6
                    tmp[o] = cloud.pos[k * 3]; tmp[o + 1] = cloud.pos[k * 3 + 1]; tmp[o + 2] = cloud.pos[k * 3 + 2]
                    tmp[o + 3] = (cloud.col[k * 3].toInt() and 0xFF) / 255f
                    tmp[o + 4] = (cloud.col[k * 3 + 1].toInt() and 0xFF) / 255f
                    tmp[o + 5] = (cloud.col[k * 3 + 2].toInt() and 0xFF) / 255f
                }
                vbo.append(tmp, end - i)
                i = end
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        vw = width; vh = height
        viewportChanged = true
    }

    @Suppress("DEPRECATION")
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!active) return

        if (resetRequested) { vbo.count = 0; pendN = 0; resetRequested = false }
        if (!texSet) { s.setCameraTextureName(bgTex); texSet = true }
        if (viewportChanged) {
            val rot = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            s.setDisplayGeometry(rot, vw, vh)
            viewportChanged = false
        }
        val frame: Frame = try { s.update() } catch (e: Exception) { return }
        val camera = frame.camera

        if (frame.hasDisplayGeometryChanged()) {
            quad.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad,
                Coordinates2d.TEXTURE_NORMALIZED, quadTex
            )
        }
        drawBackground(frame)

        val tracking = camera.trackingState == TrackingState.TRACKING
        if (tracking) {
            pendN = 0
            if (scanning) collect(frame, camera)
            if (pendN > 0) vbo.append(pend, pendN)
            if (cloud.count >= PointCloud.MAX) scanning = false

            camera.getProjectionMatrix(proj, 0, 0.05f, 50f)
            camera.getViewMatrix(view, 0)
            Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glUseProgram(ptProg)
            GLES20.glUniformMatrix4fv(ptMvp, 1, false, vp, 0)
            GLES20.glUniform1f(ptSize, density * 2.5f)
            vbo.draw(ptPos, ptCol)
        }

        val now = System.nanoTime()
        if (now - lastStats > 200_000_000L) {
            lastStats = now
            val n = cloud.count
            main.post { onStats?.invoke(n, tracking) }
        }
    }

    private fun drawBackground(frame: Frame) {
        if (frame.timestamp == 0L) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, bgTex)
        GLES20.glUseProgram(bgProg)
        quad.position(0); quadTex.position(0)
        GLES20.glVertexAttribPointer(bgPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(bgTc, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glEnableVertexAttribArray(bgPos)
        GLES20.glEnableVertexAttribArray(bgTc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(bgPos)
        GLES20.glDisableVertexAttribArray(bgTc)
        GLES20.glDepthMask(true)
    }

    private fun collect(frame: Frame, camera: Camera) {
        val now = System.nanoTime()
        if (now - lastCollect < 120_000_000L) return
        lastCollect = now

        var depth: Image? = null
        var conf: Image? = null
        var cam: Image? = null
        try {
            try {
                depth = frame.acquireRawDepthImage16Bits()
                conf = frame.acquireRawDepthConfidenceImage()
            } catch (e: Exception) {
                depth?.close(); conf?.close(); depth = null; conf = null
                depth = frame.acquireDepthImage16Bits()
            }
            cam = frame.acquireCameraImage()
            val d = depth ?: return
            val c = cam ?: return

            val intr = camera.imageIntrinsics
            val fx = intr.focalLength[0]; val fy = intr.focalLength[1]
            val cx = intr.principalPoint[0]; val cy = intr.principalPoint[1]
            val iw = c.width; val ih = c.height

            val m = FloatArray(16)
            camera.pose.toMatrix(m, 0)

            val dw = d.width; val dh = d.height
            val dBuf = d.planes[0].buffer.order(ByteOrder.nativeOrder())
            val dStride = d.planes[0].rowStride
            val cBuf = conf?.planes?.get(0)?.buffer
            val cStride = conf?.planes?.get(0)?.rowStride ?: 0

            val yP = c.planes[0]; val uP = c.planes[1]; val vP = c.planes[2]
            val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer

            val step = maxOf(1, dw / 160)
            synchronized(lock) {
                var y = 0
                while (y < dh) {
                    var x = 0
                    while (x < dw) {
                        val dist = (dBuf.getShort(y * dStride + x * 2).toInt() and 0xFFFF) / 1000f
                        var ok = dist in 0.15f..4.0f
                        if (ok && cBuf != null) ok = (cBuf.get(y * cStride + x).toInt() and 0xFF) >= 100
                        if (ok) {
                            val px = (x + 0.5f) / dw * iw
                            val py = (y + 0.5f) / dh * ih
                            val X = (px - cx) / fx * dist
                            val Y = -(py - cy) / fy * dist
                            val Z = -dist
                            val wx = m[0] * X + m[4] * Y + m[8] * Z + m[12]
                            val wy = m[1] * X + m[5] * Y + m[9] * Z + m[13]
                            val wz = m[2] * X + m[6] * Y + m[10] * Z + m[14]

                            val ipx = px.toInt().coerceIn(0, iw - 1)
                            val ipy = py.toInt().coerceIn(0, ih - 1)
                            val yy = yB.get(ipy * yP.rowStride + ipx * yP.pixelStride).toInt() and 0xFF
                            val uvx = ipx / 2; val uvy = ipy / 2
                            val u = (uB.get(uvy * uP.rowStride + uvx * uP.pixelStride).toInt() and 0xFF) - 128
                            val v = (vB.get(uvy * vP.rowStride + uvx * vP.pixelStride).toInt() and 0xFF) - 128
                            val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
                            val g = (yy - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
                            val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)

                            if (cloud.add(wx, wy, wz, r, g, b)) {
                                if ((pendN + 1) * 6 > pend.size) pend = pend.copyOf(pend.size * 2)
                                val o = pendN * 6
                                pend[o] = wx; pend[o + 1] = wy; pend[o + 2] = wz
                                pend[o + 3] = r / 255f; pend[o + 4] = g / 255f; pend[o + 5] = b / 255f
                                pendN++
                            }
                        }
                        x += step
                    }
                    y += step
                }
            }
        } catch (e: Exception) {
            // кадр глубины ещё не готов — пропускаем
        } finally {
            depth?.close(); conf?.close(); cam?.close()
        }
    }

    companion object {
        private const val BG_VS = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }"""
        private const val BG_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }"""
    }
}
