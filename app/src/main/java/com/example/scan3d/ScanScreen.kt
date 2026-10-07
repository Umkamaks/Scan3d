package com.example.scan3d

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ScanScreen(onClose: () -> Unit, onSaved: (String) -> Unit) {
    val ctx = LocalContext.current
    var hasPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }
    LaunchedEffect(Unit) { if (!hasPerm) launcher.launch(Manifest.permission.CAMERA) }

    if (!hasPerm) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Для сканирования нужен доступ к камере")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Разрешить") }
            TextButton(onClick = onClose) { Text("Назад") }
        }
    } else {
        ScanContent(onClose, onSaved)
    }
}

@Composable
private fun ScanContent(onClose: () -> Unit, onSaved: (String) -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val renderer = remember { ArRenderer(ctx) }
    val glView = remember {
        GLSurfaceView(ctx).apply {
            setEGLContextClientVersion(2)
            setPreserveEGLContextOnPause(true)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    var session by remember { mutableStateOf<Session?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installRequested by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var points by remember { mutableIntStateOf(0) }
    var tracking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    renderer.onStats = { n, tr -> points = n; tracking = tr }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    try {
                        if (session == null) {
                            val st = ArCoreApk.getInstance().requestInstall(activity, !installRequested)
                            if (st == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                                installRequested = true
                                return@LifecycleEventObserver
                            }
                            val s = Session(ctx)
                            if (!s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                                error = "Это устройство не поддерживает Depth API (ARCore)."
                                s.close()
                                return@LifecycleEventObserver
                            }
                            val cfg = Config(s)
                            cfg.depthMode = Config.DepthMode.AUTOMATIC
                            cfg.focusMode = Config.FocusMode.AUTO
                            cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            s.configure(cfg)
                            session = s
                            renderer.session = s
                        }
                        session?.resume()
                        renderer.active = true
                        glView.onResume()
                    } catch (e: UnavailableException) {
                        error = "ARCore недоступен: ${e.javaClass.simpleName}"
                    } catch (e: Exception) {
                        error = e.message ?: "Не удалось запустить камеру"
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    renderer.active = false
                    glView.onPause()
                    session?.pause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            renderer.active = false
            renderer.scanning = false
            glView.onPause()
            renderer.session = null
            session?.close()
            session = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())

        // Весь интерфейс — внутри безопасной зоны: ни статус-бар, ни кнопки/жесты навигации, ни вырез не перекрывают элементы
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("Точек: $points", color = Color.White)
                    Text(
                        if (tracking) "Отслеживание: ОК" else "Двигайте телефон медленно…",
                        color = if (tracking) Color(0xFF81C784) else Color(0xFFFFB74D)
                    )
                }
            }

            if (!scanning && points == 0 && error == null) {
                Text(
                    "Наведите камеру на объект и медленно обходите его по кругу. Нажмите «Старт».",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                )
            }

            error?.let {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(it, color = Color.White)
                    TextButton(onClick = onClose) { Text("Назад") }
                }
            }

            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onClose, enabled = !saving) { Text("Назад") }
                Button(
                    onClick = { scanning = !scanning; renderer.scanning = scanning },
                    enabled = error == null && !saving
                ) { Text(if (scanning) "Пауза" else "Старт") }
                OutlinedButton(
                    onClick = { renderer.reset(); points = 0; scanning = false; renderer.scanning = false },
                    enabled = points > 0 && !saving
                ) { Text("Сброс") }
                Button(
                    enabled = points > 100 && !saving,
                    onClick = {
                        scanning = false; renderer.scanning = false
                        saving = true
                        scope.launch {
                            val cloud = renderer.snapshot()
                            val name = "Скан " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())
                            val info = withContext(Dispatchers.IO) { ScanStore.save(ctx, cloud, name) }
                            saving = false
                            Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show()
                            onSaved(info.id)
                        }
                    }
                ) { Text(if (saving) "…" else "Сохранить") }
            }
        }
    }
}
