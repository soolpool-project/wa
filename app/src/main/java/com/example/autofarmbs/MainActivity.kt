package com.example.autofarmbs

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Yellow = Color(0xFFFFC400)
private val Purple = Color(0xFF2A1466)
private val CardPurple = Color(0xFF3F2290)

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Yellow, onPrimary = Color.Black, surface = Purple)) { App() }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ConfigStore(ctx) }
    val cfg by store.flow.collectAsState(Config())
    val running by Bus.running.collectAsState()
    val stats by Bus.stats.collectAsState()
    val logs by Bus.logs.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    fun save(c: Config) { scope.launch { store.save(c) } }

    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null)
            ContextCompat.startForegroundService(ctx, Intent(ctx, FarmService::class.java)
                .putExtra("code", r.resultCode).putExtra("data", r.data))
    }
    fun start() {
        when {
            GameInputService.inst == null -> {
                Toast.makeText(ctx, "Включи сервис доступности «AutoFarm BS»", Toast.LENGTH_LONG).show()
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            !Settings.canDrawOverlays(ctx) -> ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            else -> projection.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
    }

    Surface(Modifier.fillMaxSize(), color = Purple) {
        Column(Modifier.padding(16.dp).statusBarsPadding()) {
            Text("AutoFarm BS", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Yellow)
            Row {
                listOf("Главная", "Модули", "Лог", "Статистика").forEachIndexed { i, t ->
                    TextButton(onClick = { tab = i }) {
                        Text(t, color = if (tab == i) Yellow else Color.White, fontWeight = if (tab == i) FontWeight.Bold else null)
                    }
                }
            }
            when (tab) {
                0 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(Color(0xFF7A1F1F))) {
                        Text(DISCLAIMER, Modifier.padding(12.dp), fontSize = 13.sp, color = Color.White)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Статус: " + if (running) "работает" else "остановлен", color = Color.White)
                    val sec = if (running && stats.startedAt > 0) (now - stats.startedAt) / 1000 else 0
                    Text("Матчей: ${stats.matches}   Побед: ${stats.wins}", color = Color.White, fontSize = 18.sp)
                    Text("Кубки: ${stats.trophies ?: "—"}  (${if (stats.delta >= 0) "+" else ""}${stats.delta})", color = Yellow, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Время: %d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60), color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { if (running) Bus.running.value = false else start() },
                        Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(if (running) Color(0xFFE53935) else Yellow)
                    ) { Text(if (running) "СТОП" else "СТАРТ", fontSize = 26.sp, fontWeight = FontWeight.Black,
                        color = if (running) Color.White else Color.Black) }
                    Spacer(Modifier.height(8.dp))
                    Text("Бот играет тем бойцом и в том режиме, который уже выбран в лобби Brawl Stars.",
                        color = Color(0xFFCCCCFF), fontSize = 13.sp)
                }
                1 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Режим", color = Yellow, fontWeight = FontWeight.Bold)
                    FarmMode.values().forEach { m ->
                        Row { RadioButton(cfg.mode == m, { save(cfg.copy(mode = m)) }); Text(m.title, Modifier.padding(top = 12.dp), color = Color.White) }
                    }
                    Toggle("AimBot (авто-прицел и стрельба)", cfg.aim) { save(cfg.copy(aim = it)) }
                    Toggle("AutoDodge (стрейф)", cfg.dodge) { save(cfg.copy(dodge = it)) }
                    Slide("Сила уворота", cfg.dodgeLevel) { save(cfg.copy(dodgeLevel = it)) }
                    Slide("Агрессивность (сближение)", cfg.aggro) { save(cfg.copy(aggro = it)) }
                    Toggle("Супер", cfg.useSuper) { save(cfg.copy(useSuper = it)) }
                    Toggle("Гаджет", cfg.useGadget) { save(cfg.copy(useGadget = it)) }
                    Text("Остановиться на кубках: ${if (cfg.stopAtTrophies == 0) "∞" else cfg.stopAtTrophies}", color = Color.White)
                    Slider(cfg.stopAtTrophies / 2000f, { save(cfg.copy(stopAtTrophies = (it * 20).toInt() * 100)) })
                }
                2 -> LazyColumn { items(logs.asReversed()) { Text(it, color = Color.White, fontSize = 12.sp) } }
                3 -> Column {
                    Text("Кубки за сессию", color = Yellow, fontWeight = FontWeight.Bold)
                    Chart(stats.history)
                }
            }
        }
    }
}

@Composable fun Toggle(title: String, v: Boolean, on: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, Modifier.padding(top = 12.dp), color = Color.White); Switch(v, on)
    }

@Composable fun Slide(title: String, v: Float, on: (Float) -> Unit) {
    Text(title, color = Color.White, fontSize = 13.sp); Slider(v, on)
}

@Composable fun Chart(h: List<Int>) {
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        if (h.size < 2) return@Canvas
        val mn = h.min(); val rng = (h.max() - mn).coerceAtLeast(1)
        val p = Path()
        h.forEachIndexed { i, v ->
            val x = size.width * i / (h.size - 1); val y = size.height * (1 - (v - mn) / rng.toFloat())
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, Yellow, style = Stroke(6f))
    }
}
