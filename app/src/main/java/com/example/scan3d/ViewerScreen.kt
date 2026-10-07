package com.example.scan3d

import android.content.Context
import android.content.Intent
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun ViewerScreen(id: String, onBack: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val info = remember(id) { ScanStore.info(ctx, id) }
    val cloud by produceState<PointCloud?>(null, id) {
        value = withContext(Dispatchers.IO) { ScanStore.load(ctx, id) }
    }
    val glView = remember { ViewerGLView(ctx) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) glView.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) glView.onPause()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(cloud) { glView.setCloud(cloud) }

    fun export(format: String) {
        scope.launch {
            busy = true
            val file = withContext(Dispatchers.IO) { ScanStore.export(ctx, id, format) }
            busy = false
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Экспорт модели"))
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF121218))) {
        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(info?.name ?: "Скан", color = Color.White)
                    Text(
                        if (cloud == null) "Загрузка…" else "${info?.points ?: 0} точек · 1 палец — вращение, щипок — зум",
                        color = Color(0xFFBBBBBB)
                    )
                }
            }

            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                OutlinedButton(onClick = onBack) { Text("Назад") }
                Button(onClick = { export("ply") }, enabled = cloud != null && !busy) { Text(if (busy) "…" else "PLY") }
                Button(onClick = { export("obj") }, enabled = cloud != null && !busy) { Text("OBJ") }
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Удалить") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить скан?") },
            confirmButton = {
                TextButton(onClick = { ScanStore.delete(ctx, id); confirmDelete = false; onDeleted() }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}

class ViewerGLView(ctx: Context) : GLSurfaceView(ctx) {
    private val r = ViewerRenderer(ctx.resources.displayMetrics.density)
    private var lx = 0f
    private var ly = 0f
    private val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            r.dist = (r.dist / d.scaleFactor).coerceIn(r.radius * 0.3f, r.radius * 12f)
            requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setPreserveEGLContextOnPause(true)
        setRenderer(r)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setCloud(c: PointCloud?) {
        r.setCloud(c)
        requestRender()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && e.actionIndex == 0) 1 else 0
                lx = e.getX(i); ly = e.getY(i)
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scale.isInProgress) {
                r.yaw -= (e.x - lx) * 0.4f
                r.pitch = (r.pitch + (e.y - ly) * 0.4f).coerceIn(-89f, 89f)
                lx = e.x; ly = e.y
                requestRender()
            }
        }
        return true
    }
}

class ViewerRenderer(private val density: Float) : GLSurfaceView.Renderer {
    @Volatile var yaw = 30f
    @Volatile var pitch = 20f
    @Volatile var dist = 2f
    @Volatile var radius = 1f

    private var cloud: PointCloud? = null
    private var dirty = false
    private val vbo = PointVbo()
    private var prog = 0; private var uMvp = 0; private var uSize = 0; private var aPos = 0; private var aCol = 0
    private var aspect = 1f
    private val proj = FloatArray(16); private val view = FloatArray(16); private val vp = FloatArray(16)

    fun setCloud(c: PointCloud?) = synchronized(this) { cloud = c; dirty = true }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.07f, 0.07f, 0.09f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        prog = Gl.program(Gl.POINT_VS, Gl.POINT_FS)
        uMvp = GLES20.glGetUniformLocation(prog, "u_MVP")
        uSize = GLES20.glGetUniformLocation(prog, "u_Size")
        aPos = GLES20.glGetAttribLocation(prog, "a_Pos")
        aCol = GLES20.glGetAttribLocation(prog, "a_Color")
        vbo.create()
        synchronized(this) { dirty = true }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        aspect = w.toFloat() / max(1, h)
    }

    private fun upload() {
        val c = synchronized(this) { dirty = false; cloud } ?: return
        val n = c.count
        if (n == 0) return
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        for (i in 0 until n) { sx += c.pos[i * 3]; sy += c.pos[i * 3 + 1]; sz += c.pos[i * 3 + 2] }
        val cx = (sx / n).toFloat(); val cy = (sy / n).toFloat(); val cz = (sz / n).toFloat()
        val data = FloatArray(n * 6)
        var sq = 0.0
        for (i in 0 until n) {
            val x = c.pos[i * 3] - cx; val y = c.pos[i * 3 + 1] - cy; val z = c.pos[i * 3 + 2] - cz
            sq += (x * x + y * y + z * z).toDouble()
            val o = i * 6
            data[o] = x; data[o + 1] = y; data[o + 2] = z
            data[o + 3] = (c.col[i * 3].toInt() and 0xFF) / 255f
            data[o + 4] = (c.col[i * 3 + 1].toInt() and 0xFF) / 255f
            data[o + 5] = (c.col[i * 3 + 2].toInt() and 0xFF) / 255f
        }
        radius = max(0.05f, sqrt(sq / n).toFloat() * 2.2f)
        dist = radius * 2.5f
        vbo.allocate(n)
        vbo.append(data, n)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (dirty) upload()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val yr = Math.toRadians(yaw.toDouble()); val pr = Math.toRadians(pitch.toDouble())
        val ex = (dist * cos(pr) * sin(yr)).toFloat()
        val ey = (dist * sin(pr)).toFloat()
        val ez = (dist * cos(pr) * cos(yr)).toFloat()
        Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, 50f, aspect, radius * 0.02f, radius * 40f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, vp, 0)
        GLES20.glUniform1f(uSize, density * 2.2f)
        vbo.draw(aPos, aCol)
    }
}
