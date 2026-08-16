package com.example.panichelper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Запрашиваем разрешения (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            PanicHelperApp()
        }
    }
}

/* =========================
   МОДЕЛИ ДАННЫХ
========================= */

data class AttackEntry(
    val id: Long,
    val date: Long,
    val durationMin: Int,
    val intensity: Int,            // 0..10
    val triggers: List<String>,
    val helped: Boolean,
    val note: String
)

/* =========================
   ХРАНИЛИЩЕ (SharedPreferences)
========================= */

class Storage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "panic_helper", Context.MODE_PRIVATE
    )

    private val attacksKey = "attacks"

    fun loadAttacks(): List<AttackEntry> {
        val json = prefs.getString(attacksKey, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    AttackEntry(
                        id = o.optLong("id"),
                        date = o.optLong("date"),
                        durationMin = o.optInt("duration", 0),
                        intensity = o.optInt("intensity", 0),
                        triggers = jsonArrayToList(o.optJSONArray("triggers")),
                        helped = o.optBoolean("helped", false),
                        note = o.optString("note")
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun saveAttacks(list: List<AttackEntry>) {
        val arr = JSONArray()
        list.forEach { a ->
            val o = JSONObject()
            o.put("id", a.id)
            o.put("date", a.date)
            o.put("duration", a.durationMin)
            o.put("intensity", a.intensity)
            o.put("triggers", listToJson(a.triggers))
            o.put("helped", a.helped)
            o.put("note", a.note)
            arr.put(o)
        }
        prefs.edit().putString(attacksKey, arr.toString()).apply()
    }

    fun addAttack(entry: AttackEntry) {
        val list = loadAttacks().toMutableList()
        list.add(0, entry)
        saveAttacks(list)
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    private fun listToJson(list: List<String>): JSONArray {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr
    }
}

/* =========================
   ВИБРАЦИЯ (главный канал помощи)
========================= */

class Haptics(private val context: Context) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (e: Exception) { null }

    /** Короткий "тик" — для перехода по шагам */
    fun tick() {
        vibratePattern(longArrayOf(0, 40), intArrayOf(0, 80))
    }

    /** Вибрация вдоха: нарастающая, 4 секунды */
    fun inhale() {
        // 4 шага по 1 секунде, усиливающаяся интенсивность
        vibratePattern(
            longArrayOf(0, 1000, 1000, 1000, 1000),
            intArrayOf(0, 60, 120, 180, 230)
        )
    }

    /** Вибрация выдоха: стабильная, 6 секунд (успокаивающая) */
    fun exhale() {
        vibratePattern(
            longArrayOf(0, 6000),
            intArrayOf(0, 180)
        )
    }

    /** Пауза между циклами */
    fun pause() {
        vibratePattern(longArrayOf(0, 1000), intArrayOf(0, 0))
    }

    private fun vibratePattern(timings: LongArray, amplitudes: IntArray) {
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        } catch (e: Exception) { /* ignore */ }
    }

    fun stop() {
        try { vibrator?.cancel() } catch (e: Exception) { /* ignore */ }
    }
}

/* =========================
   ТРИГГЕРЫ / ПОМОЩЬ
========================= */

val commonTriggers = listOf(
    "Плохой сон",
    "Новости",
    "Конфликт",
    "Мысли о сердце",
    "Духота",
    "Одиночество",
    "Не знаю"
)

/* =========================
   КОМПОЗИЦИЯ
========================= */

enum class Screen { Home, Panic, History, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanicHelperApp() {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1976D2),
            error = Color(0xFFD32F2F)
        )
    ) {
        val context = LocalContext.current
        val storage = remember { Storage(context) }
        val haptics = remember { Haptics(context) }

        val attacks = remember {
            mutableStateListOf<AttackEntry>().apply {
                addAll(storage.loadAttacks())
            }
        }

        var screen by rememberSaveable { mutableStateOf(Screen.Home) }

        // Глобальная SOS-обёртка
        Box(modifier = Modifier.fillMaxSize()) {

            // Содержимое экрана
            when (screen) {
                Screen.Home -> HomeScreen(
                    lastAttack = attacks.firstOrNull(),
                    totalAttacks = attacks.size,
                    onPanic = { screen = Screen.Panic },
                    onHistory = { screen = Screen.History },
                    onSettings = { screen = Screen.Settings }
                )
                Screen.Panic -> PanicFlowScreen(
                    haptics = haptics,
                    onFinish = { entry ->
                        storage.addAttack(entry)
                        attacks.add(0, entry)
                        screen = Screen.Home
                    },
                    onCancel = {
                        haptics.stop()
                        screen = Screen.Home
                    }
                )
                Screen.History -> HistoryScreen(
                    attacks = attacks,
                    onBack = { screen = Screen.Home }
                )
                Screen.Settings -> SettingsScreen(
                    onBack = { screen = Screen.Home }
                )
            }

            // SOS-кнопка в правом верхнем углу (всегда видна)
            if (screen != Screen.Panic) {
                SosButton(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                )
            }
        }
    }
}

/* =========================
   ГЛАВНЫЙ ЭКРАН
========================= */

@Composable
fun HomeScreen(
    lastAttack: AttackEntry?,
    totalAttacks: Int,
    onPanic: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Заголовок
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "АнтиПаника",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (lastAttack == null) "Пока всё спокойно"
                       else "Последний приступ: ${formatDate(lastAttack.date)}",
                fontSize = 16.sp,
                color = Color.Gray
            )
        }

        // ГЛАВНАЯ КНОПКА
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(40.dp))
                .background(Color(0xFFD32F2F))
                .clickable {
                    // Тактильный отклик при нажатии
                    try {
                        Haptics(context).tick()
                    } catch (e: Exception) { /* ignore */ }
                    onPanic()
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(80.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "МНЕ ПЛОХО",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Нажми — я помогу",
                    fontSize = 20.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }

        // Нижние кнопки
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SecondaryButton(
                text = "Мои записи",
                icon = Icons.Filled.List,
                onClick = onHistory,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = "Настройки",
                icon = Icons.Filled.Settings,
                onClick = onSettings,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(80.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFFE3F2FD))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Color(0xFF1976D2))
            Text(
                text = text,
                fontSize = 16.sp,
                color = Color(0xFF1976D2),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun SosButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color(0xFFD32F2F))
            .clickable { dialPhone(context, "112") },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Call,
                contentDescription = "SOS",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "SOS",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

/* =========================
   ПОТОК ПОМОЩИ ПРИ ПАНИКЕ
========================= */

private enum class PanicStage { Intro, Breathing, Grounding, Assess, Trigger, Done }

@Composable
fun PanicFlowScreen(
    haptics: Haptics,
    onFinish: (AttackEntry) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val startTime = remember { System.currentTimeMillis() }

    var stage by rememberSaveable { mutableStateOf(PanicStage.Intro) }
    var intensity by rememberSaveable { mutableStateOf(5) }
    var selectedTriggers by rememberSaveable { mutableStateOf(emptyList<String>()) }

    // Сохраняем запись в конце
    fun finish() {
        val durationMin = ((System.currentTimeMillis() - startTime) / 60000L).toInt().coerceAtLeast(1)
        val entry = AttackEntry(
            id = System.currentTimeMillis(),
            date = startTime,
            durationMin = durationMin,
            intensity = intensity,
            triggers = selectedTriggers,
            helped = true,
            note = ""
        )
        haptics.stop()
        onFinish(entry)
    }

    // SOS в правом верхнем углу
    Box(modifier = Modifier.fillMaxSize()) {

        when (stage) {
            PanicStage.Intro -> PanicIntro(
                onStart = { stage = PanicStage.Breathing }
            )
            PanicStage.Breathing -> BreathingStage(
                haptics = haptics,
                onDone = {
                    haptics.tick()
                    stage = PanicStage.Grounding
                }
            )
            PanicStage.Grounding -> GroundingStage(
                haptics = haptics,
                onDone = {
                    haptics.tick()
                    stage = PanicStage.Assess
                }
            )
            PanicStage.Assess -> AssessStage(
                haptics = haptics,
                selected = intensity,
                onSelect = {
                    intensity = it
                    haptics.tick()
                    stage = PanicStage.Trigger
                }
            )
            PanicStage.Trigger -> TriggerStage(
                haptics = haptics,
                selected = selectedTriggers,
                onToggle = { trig ->
                    selectedTriggers = if (selectedTriggers.contains(trig))
                        selectedTriggers - trig else selectedTriggers + trig
                    haptics.tick()
                },
                onDone = {
                    haptics.tick()
                    stage = PanicStage.Done
                }
            )
            PanicStage.Done -> DoneStage(
                onFinish = { finish() }
            )
        }

        // Кнопка отмены внизу (только не на финальном экране)
        if (stage != PanicStage.Done) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Text(
                    text = "Я в порядке, отмена",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        }

        // SOS в углу
        SosButton(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        )
    }
}

/* --- INTRO --- */
@Composable
fun PanicIntro(onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Ты в безопасности.",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1976D2)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Сейчас мы вместе пройдём 4 простых шага.\n" +
                   "Следуй за кругом и вибрацией.",
            fontSize = 20.sp,
            color = Color.DarkGray,
            lineHeight = 28.sp
        )
        Spacer(Modifier.height(48.dp))
        BigButton(
            text = "Начать",
            color = Color(0xFF1976D2),
            onClick = onStart
        )
    }
}

/* --- BREATHING --- */
@Composable
fun BreathingStage(haptics: Haptics, onDone: () -> Unit) {
    var phase by remember { mutableStateOf("Вдох") }
    var cyclesLeft by remember { mutableStateOf(6) }
    var running by remember { mutableStateOf(true) }

    // Анимация круга
    val scale by animateFloatAsState(
        targetValue = if (phase == "Вдох") 1f else 0.6f,
        animationSpec = tween(
            durationMillis = if (phase == "Вдох") 4000 else 6000,
            easing = LinearEasing
        ),
        label = "breath"
    )

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (cyclesLeft > 0 && running) {
            // Вдох
            phase = "Вдох"
            haptics.inhale()
            delay(4000)
            if (!running) break
            // Выдох
            phase = "Выдох"
            haptics.exhale()
            delay(6000)
            if (!running) break
            // Пауза
            haptics.pause()
            delay(1000)
            cyclesLeft--
        }
        running = false
    }

    DisposableEffect(Unit) {
        onDispose { haptics.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Дыши со мной",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1976D2)
        )

        Spacer(Modifier.height(32.dp))

        // Анимированный круг
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size((220 * scale).dp)
                    .clip(CircleShape)
                    .background(Color(0xFF64B5F6))
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = phase,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Осталось циклов: $cyclesLeft",
            fontSize = 18.sp,
            color = Color.Gray
        )

        Spacer(Modifier.height(32.dp))

        if (!running) {
            BigButton(
                text = "Готово, дальше",
                color = Color(0xFF388E3C),
                onClick = onDone
            )
        } else {
            Text(
                text = "Телефон вибрирует в ритме дыхания.\nПросто следуй за кругом.",
                fontSize = 16.sp,
                color = Color.Gray,
                lineHeight = 22.sp
            )
        }
    }
}

/* --- GROUNDING --- */
@Composable
fun GroundingStage(haptics: Haptics, onDone: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(0) }

    val steps = listOf(
        "Посмотри вокруг.\nНазови вслух 5 предметов.",
        "Потрогай 4 предмета рядом.\nПочувствуй их.",
        "Прислушайся.\nНазови 3 звука.",
        "Почувствуй 2 запаха.\nИли вспомни их.",
        "Сделай глоток воды.\nИли назови 1 вкус."
    )

    val numbers = listOf("5", "4", "3", "2", "1")

    DisposableEffect(Unit) {
        onDispose { haptics.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Заземление",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1976D2)
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = numbers[step],
            fontSize = 120.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF1976D2)
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = steps[step],
            fontSize = 24.sp,
            color = Color.DarkGray,
            lineHeight = 32.sp
        )

        Spacer(Modifier.height(48.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (step > 0) {
                Box(modifier = Modifier.weight(1f)) {
                    BigButton(
                        text = "Назад",
                        color = Color(0xFFB0BEC5),
                        onClick = { step-- }
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                if (step < steps.lastIndex) {
                    BigButton(
                        text = "Готово",
                        color = Color(0xFF388E3C),
                        onClick = { step++ }
                    )
                } else {
                    BigButton(
                        text = "Дальше",
                        color = Color(0xFF388E3C),
                        onClick = onDone
                    )
                }
            }
        }
    }
}

/* --- ASSESS --- */
@Composable
fun AssessStage(haptics: Haptics, selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        Triple(3, "Слабо", Color(0xFF4CAF50)),
        Triple(6, "Средне", Color(0xFFFFA726)),
        Triple(9, "Сильно", Color(0xFFE53935))
    )

    DisposableEffect(Unit) {
        onDispose { haptics.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Насколько сильно было?",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1976D2)
        )

        Spacer(Modifier.height(40.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            options.forEach { (level, label, color) ->
                val isSelected = selected == level
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (isSelected) color else color.copy(alpha = 0.3f))
                        .clickable { onSelect(level) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Выбери один вариант",
            fontSize = 16.sp,
            color = Color.Gray
        )
    }
}

/* --- TRIGGER --- */
@Composable
fun TriggerStage(
    haptics: Haptics,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onDone: () -> Unit
) {
    DisposableEffect(Unit) {
        onDispose { haptics.stop() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Что могло вызвать?",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1976D2)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Можно выбрать несколько. Можно пропустить.",
            fontSize = 16.sp,
            color = Color.Gray
        )
        Spacer(Modifier.height(24.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            commonTriggers.forEach { trigger ->
                val isSelected = selected.contains(trigger)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) Color(0xFF1976D2) else Color(0xFFE3F2FD))
                        .clickable { onToggle(trigger) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = trigger,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) Color.White else Color(0xFF1976D2)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        BigButton(
            text = "Готово, дальше",
            color = Color(0xFF388E3C),
            onClick = onDone
        )
    }
}

/* --- DONE --- */
@Composable
fun DoneStage(onFinish: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF388E3C),
            modifier = Modifier.size(140.dp)
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = "Ты молодец.",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF388E3C)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Приступ записан.\nЭти данные можно показать врачу.",
            fontSize = 20.sp,
            color = Color.DarkGray,
            lineHeight = 28.sp
        )
        Spacer(Modifier.height(48.dp))
        BigButton(
            text = "На главную",
            color = Color(0xFF388E3C),
            onClick = onFinish
        )
    }
}

/* --- Общие большие кнопки --- */
@Composable
fun BigButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

/* =========================
   ИСТОРИЯ
========================= */

@Composable
fun HistoryScreen(attacks: List<AttackEntry>, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(32.dp))
            }
            Text(
                text = "Мои приступы",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(16.dp))

        if (attacks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Записей пока нет.\nЭто хорошо.",
                    fontSize = 22.sp,
                    color = Color.Gray
                )
            }
        } else {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Краткая статистика
                val total = attacks.size
                val avgInt = attacks.map { it.intensity }.average()
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Всего приступов: $total", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Средняя сила: ${"%.1f".format(avgInt)}/10", fontSize = 18.sp)
                    }
                }

                attacks.take(30).forEach { AttackCard(it) }
            }
        }
    }
}

@Composable
fun AttackCard(item: AttackEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = formatDate(item.date),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(Modifier.height(8.dp))
            Text("Длительность: ${item.durationMin} мин", fontSize = 16.sp)
            Text("Сила: ${item.intensity}/10", fontSize = 16.sp)
            if (item.triggers.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Триггеры: ${item.triggers.joinToString()}", fontSize = 14.sp, color = Color.Gray)
            }
        }
    }
}

/* =========================
   НАСТРОЙКИ
========================= */

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(32.dp))
            }
            Text(
                text = "Настройки",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(16.dp))

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
            Column(Modifier.padding(16.dp)) {
                Text("Экстренный звонок", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.height(12.dp))
                BigButton(
                    text = "Позвонить 112",
                    color = Color(0xFFD32F2F),
                    onClick = { dialPhone(context, "112") }
                )
                Spacer(Modifier.height(8.dp))
                BigButton(
                    text = "Позвонить 103",
                    color = Color(0xFFD32F2F),
                    onClick = { dialPhone(context, "103") }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Card {
            Column(Modifier.padding(16.dp)) {
                Text("О приложении", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Приложение не заменяет врача.\n" +
                           "Все данные хранятся только на телефоне.\n" +
                           "Интернет не используется.",
                    fontSize = 16.sp,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

/* =========================
   УТИЛИТЫ
========================= */

fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
}

fun dialPhone(context: Context, number: String) {
    try {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Не удалось позвонить", Toast.LENGTH_SHORT).show()
    }
}