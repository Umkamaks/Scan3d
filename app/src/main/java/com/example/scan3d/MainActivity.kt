package com.example.scan3d

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    data object Home : Screen
    data object Scan : Screen
    data class View(val id: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // edge-to-edge: приложение рисуется под системными панелями, а отступы берём из insets
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF4FC3F7))) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(screen != Screen.Home) { screen = Screen.Home }
    when (val s = screen) {
        Screen.Home -> HomeScreen(onScan = { screen = Screen.Scan }, onOpen = { screen = Screen.View(it) })
        Screen.Scan -> ScanScreen(onClose = { screen = Screen.Home }, onSaved = { screen = Screen.View(it) })
        is Screen.View -> ViewerScreen(s.id, onBack = { screen = Screen.Home }, onDeleted = { screen = Screen.Home })
    }
}

@Composable
fun HomeScreen(onScan: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var scans by remember { mutableStateOf(ScanStore.list(ctx)) }
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onScan) { Text("＋ Новый скан") }
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = pad.calculateTopPadding() + 16.dp,
                bottom = pad.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item { Text("Мои сканы", style = MaterialTheme.typography.headlineMedium) }
            if (scans.isEmpty()) {
                item {
                    Text(
                        "Пока пусто. Нажмите «Новый скан», наведите камеру на объект и обойдите его по кругу.",
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
            }
            items(scans, key = { it.id }) { s ->
                Card(onClick = { onOpen(s.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Text("${s.points} точек · ${fmt.format(Date(s.time))}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { ScanStore.delete(ctx, s.id); scans = ScanStore.list(ctx) }) { Text("Удалить") }
                    }
                }
            }
        }
    }
}
