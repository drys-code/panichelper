package com.example.panichelper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent { PanicHelperApp() }
    }
}

/* ========================= МОДЕЛИ ========================= */

data class Contact(
    val id: Long,
    val name: String,
    val phone: String,
    val email: String,
    val notifyOnPanic: Boolean = true,
    val notifyOnFinish: Boolean = true
)

data class AttackEntry(
    val id: Long,
    val date: Long,
    val durationMin: Int,
    val intensity: Int,
    val triggers: List<String>,
    val helped: Boolean,
    val note: String,
    val notifiedContacts: List<String> = emptyList(),
    val finalNotificationSent: Boolean = false
)

/* ========================= ХРАНИЛИЩЕ ========================= */

class Storage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "panic_helper", Context.MODE_PRIVATE
    )

    fun loadContacts(): List<Contact> {
        val json = prefs.getString("contacts", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    Contact(
                        id = o.optLong("id"),
                        name = o.optString("name"),
                        phone = o.optString("phone"),
                        email = o.optString("email"),
                        notifyOnPanic = o.optBoolean("notifyPanic", true),
                        notifyOnFinish = o.optBoolean("notifyFinish", true)
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun saveContacts(list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { c ->
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("phone", c.phone)
            o.put("email", c.email)
            o.put("notifyPanic", c.notifyOnPanic)
            o.put("notifyFinish", c.notifyOnFinish)
            arr.put(o)
        }
        prefs.edit().putString("contacts", arr.toString()).apply()
    }

    fun loadAttacks(): List<AttackEntry> {
        val json = prefs.getString("attacks", "[]") ?: "[]"
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
                        note = o.optString("note"),
                        notifiedContacts = jsonArrayToList(o.optJSONArray("notified")),
                        finalNotificationSent = o.optBoolean("finalNotif", false)
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
            o.put("notified", listToJson(a.notifiedContacts))
            o.put("finalNotif", a.finalNotificationSent)
            arr.put(o)
        }
        prefs.edit().putString("attacks", arr.toString()).apply()
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

/* ========================= ВИБРАЦИЯ ========================= */

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

    fun tick() = vibratePattern(longArrayOf(0, 40), intArrayOf(0, 80))
    fun inhale() = vibratePattern(longArrayOf(0, 1000, 1000, 1000, 1000), intArrayOf(0, 60, 120, 180, 230))
    fun exhale() = vibratePattern(longArrayOf(0, 6000), intArrayOf(0, 180))
    fun pause() = vibratePattern(longArrayOf(0, 1000), intArrayOf(0, 0))
    fun stop() { try { vibrator?.cancel() } catch (e: Exception) { } }

    private fun vibratePattern(timings: LongArray, amplitudes: IntArray) {
        try {
            val v = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, -1)
            }
        } catch (e: Exception) { }
    }
}

/* ========================= КОНСТАНТЫ ========================= */

val commonTriggers = listOf(
    "Плохой сон", "Новости", "Конфликт",
    "Мысли о сердце", "Духота", "Одиночество", "Не знаю"
)

/* ========================= ОТПРАВКА УВЕДОМЛЕНИЙ ========================= */

object Notifier {
    fun sendSmsIntent(context: Context, phone: String, text: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$phone")
                putExtra("sms_body", text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось открыть SMS", Toast.LENGTH_SHORT).show()
        }
    }

    fun sendSmsDirect(context: Context, phone: String, text: String) {
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED) {
                val sms = SmsManager.getDefault()
                sms.sendTextMessage(phone, null, text, null, null)
            } else {
                sendSmsIntent(context, phone, text)
            }
        } catch (e: Exception) {
            sendSmsIntent(context, phone, text)
        }
    }

    fun sendEmailIntent(context: Context, email: String, subject: String, body: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$email")
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось открыть почту", Toast.LENGTH_SHORT).show()
        }
    }

    fun notifyAll(
        context: Context,
        contacts: List<Contact>,
        panicStart: Boolean
    ) {
        val relevant = contacts.filter {
            if (panicStart) it.notifyOnPanic else it.notifyOnFinish
        }
        if (relevant.isEmpty()) return

        val message = if (panicStart)
            "Мне сейчас плохо. Тревога. Пожалуйста, позвони."
        else
            "Со мной всё хорошо. Приступ закончился."

        relevant.forEach { contact ->
            if (contact.phone.isNotBlank()) {
                sendSmsDirect(context, contact.phone, message)
            }
            if (contact.email.isNotBlank()) {
                val subject = if (panicStart) "Тревога" else "Всё хорошо"
                sendEmailIntent(context, contact.email, subject, message)
            }
        }
    }

    fun exportLog(context: Context, attacks: List<AttackEntry>): File? {
        return try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: context.filesDir
            val fileName = "panic_log_${System.currentTimeMillis()}.txt"
            val file = File(dir, fileName)
            val sb = StringBuilder()
            sb.appendLine("ДНЕВНИК ПРИСТУПОВ")
            sb.appendLine("Экспорт: ${formatDate(System.currentTimeMillis())}")
            sb.appendLine("=".repeat(50))
            sb.appendLine()

            if (attacks.isEmpty()) {
                sb.appendLine("Записей нет.")
            } else {
                sb.appendLine("Всего приступов: ${attacks.size}")
                sb.appendLine("Средняя интенсивность: ${"%.1f".format(attacks.map { it.intensity }.average())}/10")
                sb.appendLine()

                attacks.forEachIndexed { index, a ->
                    sb.appendLine("--- Запись ${index + 1} ---")
                    sb.appendLine("Дата: ${formatDate(a.date)}")
                    sb.appendLine("Длительность: ${a.durationMin} мин")
                    sb.appendLine("Интенсивность: ${a.intensity}/10")
                    sb.appendLine("Триггеры: ${a.triggers.joinToString().ifBlank { "—" }}")
                    sb.appendLine("Помогло: ${if (a.helped) "да" else "нет"}")
                    sb.appendLine("Уведомлены: ${a.notifiedContacts.joinToString().ifBlank { "никто" }}")
                    sb.appendLine("Финальное уведомление: ${if (a.finalNotificationSent) "да" else "нет"}")
                    if (a.note.isNotBlank()) sb.appendLine("Заметка: ${a.note}")
                    sb.appendLine()
                }
            }

            file.writeText(sb.toString(), Charsets.UTF_8)
            file
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка экспорта: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }

    fun shareFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Дневник приступов")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Отправить лог").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "Не удалось отправить файл", Toast.LENGTH_SHORT).show()
        }
    }
}

/* ========================= НАВИГАЦИЯ ========================= */

enum class Screen { Home, Panic, History, Analytics, Settings, Contacts }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanicHelperApp() {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF1976D2),
        error = Color(0xFFD32F2F)
    )) {
        val context = LocalContext.current
        val storage = remember { Storage(context) }
        val haptics = remember { Haptics(context) }

        val attacks = remember { mutableStateListOf<AttackEntry>().apply { addAll(storage.loadAttacks()) } }
        val contacts = remember { mutableStateListOf<Contact>().apply { addAll(storage.loadContacts()) } }

        var screen by rememberSaveable { mutableStateOf(Screen.Home) }

        Box(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    lastAttack = attacks.firstOrNull(),
                    onPanic = { screen = Screen.Panic },
                    onHistory = { screen = Screen.History },
                    onAnalytics = { screen = Screen.Analytics },
                    onSettings = { screen = Screen.Settings }
                )
                Screen.Panic -> PanicFlowScreen(
                    haptics = haptics,
                    contacts = contacts,
                    onFinish = { entry ->
                        storage.addAttack(entry)
                        attacks.add(0, entry)
                        screen = Screen.Home
                    },
                    onCancel = { haptics.stop(); screen = Screen.Home }
                )
                Screen.History -> HistoryScreen(attacks) { screen = Screen.Home }
                Screen.Analytics -> AnalyticsScreen(attacks, contacts) { screen = Screen.Home }
                Screen.Settings -> SettingsScreen(
                    contacts = contacts,
                    onBack = { screen = Screen.Home },
                    onContacts = { screen = Screen.Contacts }
                )
                Screen.Contacts -> ContactsScreen(
                    contacts = contacts,
                    onBack = { screen = Screen.Settings },
                    onAdd = { contact ->
                        storage.saveContacts(contacts.toList() + contact)
                        contacts.add(contact)
                    },
                    onDelete = { contact ->
                        val updated = contacts.filter { it.id != contact.id }
                        storage.saveContacts(updated)
                        contacts.clear()
                        contacts.addAll(updated)
                    },
                    onUpdate = { contact ->
                        val updated = contacts.map { if (it.id == contact.id) contact else it }
                        storage.saveContacts(updated)
                        contacts.clear()
                        contacts.addAll(updated)
                    }
                )
            }

            if (screen != Screen.Panic) {
                SosButton(
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                )
            }
        }
    }
}

/* ========================= ГЛАВНЫЙ ЭКРАН ========================= */

@Composable
fun HomeScreen(
    lastAttack: AttackEntry?,
    onPanic: () -> Unit,
    onHistory: () -> Unit,
    onAnalytics: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("АнтиПаника", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (lastAttack == null) "Пока всё спокойно"
                       else "Последний приступ: ${formatDate(lastAttack.date)}",
                fontSize = 16.sp, color = Color.Gray
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(40.dp))
                .background(Color(0xFFD32F2F))
                .clickable {
                    try { Haptics(context).tick() } catch (e: Exception) { }
                    onPanic()
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Warning, null, tint = Color.White, modifier = Modifier.size(80.dp))
                Spacer(Modifier.height(16.dp))
                Text("МНЕ ПЛОХО", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("Нажми — я помогу", fontSize = 20.sp, color = Color.White.copy(alpha = 0.9f))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Записи", Icons.Filled.List, onHistory, Modifier.weight(1f))
                SecondaryButton("Анализ", Icons.Filled.TrendingUp, onAnalytics, Modifier.weight(1f))
            }
            SecondaryButton("Настройки", Icons.Filled.Settings, onSettings, Modifier.fillMaxWidth())
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF1976D2))
            Spacer(Modifier.width(8.dp))
            Text(text, fontSize = 18.sp, color = Color(0xFF1976D2), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SosButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier.size(72.dp).clip(CircleShape)
            .background(Color(0xFFD32F2F))
            .clickable { dialPhone(context, "112") },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Call, "SOS", tint = Color.White, modifier = Modifier.size(28.dp))
            Text("SOS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

/* ========================= ПОТОК ПАНИКИ ========================= */

private enum class PanicStage { Intro, NotifyChoice, Breathing, Grounding, Assess, Trigger, FinalNotify, Done }

@Composable
fun PanicFlowScreen(
    haptics: Haptics,
    contacts: List<Contact>,
    onFinish: (AttackEntry) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val startTime = remember { System.currentTimeMillis() }

    var stage by rememberSaveable { mutableStateOf(PanicStage.Intro) }
    var intensity by rememberSaveable { mutableStateOf(5) }
    var selectedTriggers by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var notifiedNames by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var finalNotified by rememberSaveable { mutableStateOf(false) }

    fun finish() {
        val durationMin = ((System.currentTimeMillis() - startTime) / 60000L).toInt().coerceAtLeast(1)
        val entry = AttackEntry(
            id = System.currentTimeMillis(),
            date = startTime,
            durationMin = durationMin,
            intensity = intensity,
            triggers = selectedTriggers,
            helped = true,
            note = "",
            notifiedContacts = notifiedNames,
            finalNotificationSent = finalNotified
        )
        haptics.stop()
        onFinish(entry)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (stage) {
            PanicStage.Intro -> PanicIntro { stage = PanicStage.NotifyChoice }
            PanicStage.NotifyChoice -> NotifyChoiceScreen(
                contacts = contacts,
                onNotify = { selected ->
                    if (selected.isNotEmpty()) {
                        Notifier.notifyAll(context, selected, panicStart = true)
                        notifiedNames = selected.map { it.name }
                    }
                    stage = PanicStage.Breathing
                },
                onSkip = { stage = PanicStage.Breathing }
            )
            PanicStage.Breathing -> BreathingStage(haptics) {
                haptics.tick(); stage = PanicStage.Grounding
            }
            PanicStage.Grounding -> GroundingStage(haptics) {
                haptics.tick(); stage = PanicStage.Assess
            }
            PanicStage.Assess -> AssessStage(haptics, intensity) {
                intensity = it; haptics.tick(); stage = PanicStage.Trigger
            }
            PanicStage.Trigger -> TriggerStage(haptics, selectedTriggers,
                onToggle = { t ->
                    selectedTriggers = if (selectedTriggers.contains(t)) selectedTriggers - t else selectedTriggers + t
                    haptics.tick()
                },
                onDone = {
                    haptics.tick()
                    stage = if (contacts.any { it.notifyOnFinish }) PanicStage.FinalNotify else PanicStage.Done
                }
            )
            PanicStage.FinalNotify -> FinalNotifyScreen(
                contacts = contacts.filter { it.notifyOnFinish },
                onNotify = {
                    Notifier.notifyAll(context, it, panicStart = false)
                    finalNotified = true
                    stage = PanicStage.Done
                },
                onSkip = { stage = PanicStage.Done }
            )
            PanicStage.Done -> DoneStage(onFinish = { finish() })
        }

        if (stage != PanicStage.Done) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            ) {
                Text("Я в порядке, отмена", color = Color.Gray, fontSize = 14.sp)
            }
        }

        SosButton(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
    }
}

@Composable
fun PanicIntro(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Ты в безопасности.", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
        Spacer(Modifier.height(16.dp))
        Text(
            "Сейчас пройдём несколько простых шагов.\nСледуй за кругом и вибрацией.",
            fontSize = 20.sp, color = Color.DarkGray, lineHeight = 28.sp
        )
        Spacer(Modifier.height(48.dp))
        BigButton("Начать", Color(0xFF1976D2), onStart)
    }
}

@Composable
fun NotifyChoiceScreen(
    contacts: List<Contact>,
    onNotify: (List<Contact>) -> Unit,
    onSkip: () -> Unit
) {
    val notifyable = contacts.filter { it.notifyOnPanic }
    var selected by rememberSaveable { mutableStateOf(emptyList<Contact>()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text("Сообщить близким?", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
        Spacer(Modifier.height(8.dp))
        Text(
            "Им придёт SMS или письмо.\nМожно пропустить.",
            fontSize = 18.sp, color = Color.Gray, lineHeight = 24.sp
        )
        Spacer(Modifier.height(24.dp))

        if (notifyable.isEmpty()) {
            Text(
                "Контакты не добавлены.\nДобавить можно в Настройках.",
                fontSize = 18.sp, color = Color.Gray
            )
            Spacer(Modifier.height(24.dp))
            BigButton("Продолжить без уведомления", Color(0xFF388E3C), onSkip)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                notifyable.forEach { contact ->
                    val isSelected = selected.any { it.id == contact.id }
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) Color(0xFF1976D2) else Color(0xFFE3F2FD))
                            .clickable {
                                selected = if (isSelected) selected.filter { it.id != contact.id }
                                           else selected + contact
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSelected) {
                                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Column {
                                Text(contact.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) Color.White else Color(0xFF1976D2))
                                Text(contact.phone, fontSize = 14.sp,
                                    color = if (isSelected) Color.White.copy(0.8f) else Color.Gray)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (selected.isNotEmpty()) {
                BigButton("Сообщить выбранным", Color(0xFFD32F2F)) { onNotify(selected) }
                Spacer(Modifier.height(8.dp))
            }
            BigButton("Продолжить без уведомления", Color(0xFF388E3C), onSkip)
        }
    }
}

@Composable
fun BreathingStage(haptics: Haptics, onDone: () -> Unit) {
    var phase by remember { mutableStateOf("Вдох") }
    var cyclesLeft by remember { mutableStateOf(6) }
    var running by remember { mutableStateOf(true) }

    val scale by animateFloatAsState(
        targetValue = if (phase == "Вдох") 1f else 0.6f,
        animationSpec = tween(
            durationMillis = if (phase == "Вдох") 4000 else 6000,
            easing = LinearEasing
        ), label = "breath"
    )

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (cyclesLeft > 0 && running) {
            phase = "Вдох"; haptics.inhale(); delay(4000)
            if (!running) break
            phase = "Выдох"; haptics.exhale(); delay(6000)
            if (!running) break
            haptics.pause(); delay(1000)
            cyclesLeft--
        }
        running = false
    }

    DisposableEffect(Unit) { onDispose { haptics.stop() } }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Дыши со мной", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
        Spacer(Modifier.height(32.dp))
        Box(modifier = Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size((220 * scale).dp).clip(CircleShape).background(Color(0xFF64B5F6)))
        }
        Spacer(Modifier.height(16.dp))
        Text(phase, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Осталось циклов: $cyclesLeft", fontSize = 18.sp, color = Color.Gray)
        Spacer(Modifier.height(32.dp))
        if (!running) {
            BigButton("Готово, дальше", Color(0xFF388E3C), onDone)
        } else {
            Text(
                "Телефон вибрирует в ритме дыхания.\nПросто следуй за кругом.",
                fontSize = 16.sp, color = Color.Gray, lineHeight = 22.sp
            )
        }
    }
}

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

    DisposableEffect(Unit) { onDispose { haptics.stop() } }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Заземление", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
        Spacer(Modifier.height(32.dp))
        Text(numbers[step], fontSize = 120.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1976D2))
        Spacer(Modifier.height(16.dp))
        Text(steps[step], fontSize = 24.sp, color = Color.DarkGray, lineHeight = 32.sp)
        Spacer(Modifier.height(48.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) {
                Box(Modifier.weight(1f)) { BigButton("Назад", Color(0xFFB0BEC5)) { step-- } }
            }
            Box(Modifier.weight(1f)) {
                if (step < steps.lastIndex) BigButton("Готово", Color(0xFF388E3C)) { step++ }
                else BigButton("Дальше", Color(0xFF388E3C), onDone)
            }
        }
    }
}

@Composable
fun AssessStage(haptics: Haptics, selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf(
        Triple(3, "Слабо", Color(0xFF4CAF50)),
        Triple(6, "Средне", Color(0xFFFFA726)),
        Triple(9, "Сильно", Color(0xFFE53935))
    )
    DisposableEffect(Unit) { onDispose { haptics.stop() } }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Насколько сильно было?", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
        Spacer(Modifier.height(40.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            options.forEach { (level, label, color) ->
                val isSelected = selected == level
                Box(
                    modifier = Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(24.dp))
                        .background(if (isSelected) color else color.copy(alpha = 0.3f))
                        .clickable { onSelect(level) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Выбери один вариант", fontSize = 16.sp, color = Color.Gray)
    }
}

@Composable
fun TriggerStage(
    haptics: Haptics,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onDone: () -> Unit
) {
    DisposableEffect(Unit) { onDispose { haptics.stop() } }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Что могло вызвать?", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2))
        Spacer(Modifier.height(8.dp))
        Text("Можно выбрать несколько. Можно пропустить.", fontSize = 16.sp, color = Color.Gray)
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            commonTriggers.forEach { trigger ->
                val isSelected = selected.contains(trigger)
                Box(
                    modifier = Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) Color(0xFF1976D2) else Color(0xFFE3F2FD))
                        .clickable { onToggle(trigger) },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        if (isSelected) {
                            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(trigger, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) Color.White else Color(0xFF1976D2))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        BigButton("Готово, дальше", Color(0xFF388E3C), onDone)
    }
}

@Composable
fun FinalNotifyScreen(
    contacts: List<Contact>,
    onNotify: (List<Contact>) -> Unit,
    onSkip: () -> Unit
) {
    var selected by rememberSaveable { mutableStateOf(contacts) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF388E3C), modifier = Modifier.size(100.dp))
        Spacer(Modifier.height(16.dp))
        Text("Сообщить близким,\nчто всё хорошо?", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF388E3C))
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            contacts.forEach { contact ->
                val isSelected = selected.any { it.id == contact.id }
                Box(
                    modifier = Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) Color(0xFF388E3C) else Color(0xFFE8F5E9))
                        .clickable {
                            selected = if (isSelected) selected.filter { it.id != contact.id } else selected + contact
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(contact.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) Color.White else Color(0xFF388E3C))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (selected.isNotEmpty()) {
            BigButton("Отправить: «всё хорошо»", Color(0xFF388E3C)) { onNotify(selected) }
            Spacer(Modifier.height(8.dp))
        }
        BigButton("Пропустить", Color(0xFFB0BEC5), onSkip)
    }
}

@Composable
fun DoneStage(onFinish: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF388E3C), modifier = Modifier.size(140.dp))
        Spacer(Modifier.height(32.dp))
        Text("Ты молодец.", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color(0xFF388E3C))
        Spacer(Modifier.height(16.dp))
        Text(
            "Приступ записан.\nЭти данные можно показать врачу.",
            fontSize = 20.sp, color = Color.DarkGray, lineHeight = 28.sp
        )
        Spacer(Modifier.height(48.dp))
        BigButton("На главную", Color(0xFF388E3C), onFinish)
    }
}

@Composable
fun BigButton(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(20.dp))
            .background(color).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/* ========================= ИСТОРИЯ ========================= */

@Composable
fun HistoryScreen(attacks: List<AttackEntry>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, null, modifier = Modifier.size(32.dp))
            }
            Text("Мои приступы", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        if (attacks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Записей пока нет.\nЭто хорошо.", fontSize = 22.sp, color = Color.Gray)
            }
        } else {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val total = attacks.size
                val avgInt = attacks.map { it.intensity }.average()
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Всего приступов: $total", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Средняя сила: ${"%.1f".format(avgInt)}/10", fontSize = 18.sp)
                    }
                }
                attacks.take(50).forEach { AttackCard(it) }
            }
        }
    }
}

@Composable
fun AttackCard(item: AttackEntry) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))) {
        Column(Modifier.padding(16.dp)) {
            Text(formatDate(item.date), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text("Длительность: ${item.durationMin} мин", fontSize = 16.sp)
            Text("Сила: ${item.intensity}/10", fontSize = 16.sp)
            if (item.triggers.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Триггеры: ${item.triggers.joinToString()}", fontSize = 14.sp, color = Color.Gray)
            }
            if (item.notifiedContacts.isNotEmpty()) {
                Text("Уведомлены: ${item.notifiedContacts.joinToString()}", fontSize = 14.sp, color = Color.Gray)
            }
            if (item.finalNotificationSent) {
                Text("Финальное уведомление отправлено", fontSize = 14.sp, color = Color(0xFF388E3C))
            }
        }
    }
}

/* ========================= АНАЛИТИКА ========================= */

@Composable
fun AnalyticsScreen(
    attacks: List<AttackEntry>,
    contacts: List<Contact>,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, null, modifier = Modifier.size(32.dp))
            }
            Text("Анализ", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))

        Column(modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {

            if (attacks.isEmpty()) {
                Card {
                    Text("Пока нет данных для анализа.\nКогда будут записи — здесь появятся графики.",
                        modifier = Modifier.padding(16.dp), fontSize = 18.sp)
                }
            } else {
                // Сводка
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Общая статистика", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Всего приступов: ${attacks.size}", fontSize = 18.sp)
                        val avgInt = attacks.map { it.intensity }.average()
                        val avgDur = attacks.map { it.durationMin }.average()
                        Text("Средняя интенсивность: ${"%.1f".format(avgInt)}/10", fontSize = 18.sp)
                        Text("Средняя длительность: ${"%.0f".format(avgDur)} мин", fontSize = 18.sp)
                        val notifiedCount = attacks.count { it.notifiedContacts.isNotEmpty() }
                        Text("Уведомляли близких: $notifiedCount раз", fontSize = 18.sp)
                    }
                }

                // График интенсивности
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Интенсивность приступов", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(12.dp))
                        SimpleLineChart(attacks.takeLast(30).reversed().map { it.intensity.toFloat() },
                            Color(0xFFD32F2F), maxVal = 10f)
                    }
                }

                // График длительности
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Длительность (мин)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(12.dp))
                        SimpleLineChart(attacks.takeLast(30).reversed().map { it.durationMin.toFloat() },
                            Color(0xFF1976D2))
                    }
                }

                // Топ триггеров
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Частые триггеры", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        val trigMap = attacks.flatMap { it.triggers }.groupingBy { it }.eachCount()
                        val top = trigMap.entries.sortedByDescending { it.value }.take(5)
                        if (top.isEmpty()) {
                            Text("Триггеры не указаны", color = Color.Gray)
                        } else {
                            top.forEachIndexed { i, e ->
                                Text("${i + 1}. ${e.key} — ${e.value} раз", fontSize = 17.sp)
                            }
                        }
                    }
                }

                // По дням недели
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("По дням недели", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
                        val byDay = IntArray(7)
                        val sdf = SimpleDateFormat("u", Locale.getDefault())
                        attacks.forEach { a ->
                            val d = sdf.format(Date(a.date)).toIntOrNull() ?: 0
                            if (d in 1..7) byDay[d - 1]++
                        }
                        SimpleBarChart(byDay.map { it.toFloat() }, days, Color(0xFF1976D2))
                    }
                }
            }

            // Экспорт
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Column(Modifier.padding(16.dp)) {
                    Text("Экспорт для врача", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Сохранит весь дневник в текстовый файл. Можно отправить на почту или в мессенджер.",
                        fontSize = 16.sp)
                    Spacer(Modifier.height(12.dp))
                    BigButton("Экспортировать лог", Color(0xFFFF6F00)) {
                        val file = Notifier.exportLog(context, attacks)
                        if (file != null) {
                            Notifier.shareFile(context, file)
                        }
                    }
                }
            }
        }
    }
}

/* ========================= ГРАФИКИ ========================= */

@Composable
fun SimpleLineChart(values: List<Float>, color: Color, maxVal: Float? = null) {
    if (values.size < 2) {
        Text("Недостаточно данных", color = Color.Gray)
        return
    }
    val maxY = maxVal ?: values.maxOrNull() ?: 1f
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxWidth().height(160.dp)
    ) {
        val padding = 24f
        val stepX = (size.width - padding * 2) / (values.size - 1)
        var prev: androidx.compose.ui.geometry.Offset? = null
        values.forEachIndexed { index, value ->
            val x = padding + index * stepX
            val y = size.height - padding - (value / maxY) * (size.height - padding * 2)
            val p = androidx.compose.ui.geometry.Offset(x, y)
            prev?.let {
                drawLine(color, it, p, strokeWidth = 4f)
            }
            drawCircle(color, 6f, p)
            prev = p
        }
    }
}

@Composable
fun SimpleBarChart(values: List<Float>, labels: List<String>, color: Color) {
    if (values.isEmpty()) return
    val max = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
        val padding = 24f
        val slot = (size.width - padding * 2) / values.size
        val barW = slot * 0.7f
        val gap = slot * 0.3f
        values.forEachIndexed { i, v ->
            val h = (v / max) * (size.height - padding * 2)
            val left = padding + i * slot + gap / 2
            val top = size.height - padding - h
            drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barW, h))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        labels.forEach { Text(it, fontSize = 13.sp, color = Color.Gray) }
    }
}

/* ========================= НАСТРОЙКИ ========================= */

@Composable
fun SettingsScreen(
    contacts: List<Contact>,
    onBack: () -> Unit,
    onContacts: () -> Unit
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, null, modifier = Modifier.size(32.dp))
            }
            Text("Настройки", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Column(modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {

            // Контакты
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Близкие люди", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Контакты для уведомлений и быстрого звонка.",
                        fontSize = 16.sp, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    if (contacts.isEmpty()) {
                        Text("Контакты не добавлены", color = Color.Gray)
                    } else {
                        contacts.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Person, null, tint = Color(0xFF1976D2))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, fontWeight = FontWeight.SemiBold)
                                    Text(c.phone, fontSize = 14.sp, color = Color.Gray)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    BigButton("Управление контактами", Color(0xFF1976D2), onContacts)
                }
            }

            // Быстрые звонки
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Экстренные звонки", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(12.dp))
                    BigButton("Позвонить 112", Color(0xFFD32F2F)) { dialPhone(context, "112") }
                    Spacer(Modifier.height(8.dp))
                    BigButton("Позвонить 103", Color(0xFFD32F2F)) { dialPhone(context, "103") }
                    contacts.filter { it.phone.isNotBlank() }.forEach { c ->
                        Spacer(Modifier.height(8.dp))
                        BigButton("Позвонить: ${c.name}", Color(0xFF1976D2)) {
                            dialPhone(context, c.phone)
                        }
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("О приложении", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Не заменяет врача. Данные только на телефоне.",
                        fontSize = 16.sp, lineHeight = 22.sp)
                }
            }
        }
    }
}

/* ========================= УПРАВЛЕНИЕ КОНТАКТАМИ ========================= */

@Composable
fun ContactsScreen(
    contacts: List<Contact>,
    onBack: () -> Unit,
    onAdd: (Contact) -> Unit,
    onDelete: (Contact) -> Unit,
    onUpdate: (Contact) -> Unit
) {
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<Contact?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, null, modifier = Modifier.size(32.dp))
            }
            Text("Контакты", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))

        if (contacts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Контактов нет.\nДобавьте первого близкого.", fontSize = 20.sp, color = Color.Gray)
            }
        } else {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                contacts.forEach { c ->
                    ContactCard(
                        contact = c,
                        onCall = { /* через настройки */ },
                        onEdit = { editing = c },
                        onDelete = { onDelete(c) }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        BigButton("Добавить контакт", Color(0xFF1976D2)) { showAdd = true }
    }

    if (showAdd) {
        ContactDialog(
            onDismiss = { showAdd = false },
            onSave = { name, phone, email, nPanic, nFinish ->
                onAdd(Contact(System.currentTimeMillis(), name, phone, email, nPanic, nFinish))
                showAdd = false
            }
        )
    }

    editing?.let { c ->
        ContactDialog(
            initial = c,
            onDismiss = { editing = null },
            onSave = { name, phone, email, nPanic, nFinish ->
                onUpdate(c.copy(name = name, phone = phone, email = email,
                    notifyOnPanic = nPanic, notifyOnFinish = nFinish))
                editing = null
            }
        )
    }
}

@Composable
fun ContactCard(
    contact: Contact,
    onCall: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Person, null, tint = Color(0xFF1976D2), modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(contact.name, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    if (contact.phone.isNotBlank()) Text(contact.phone, fontSize = 16.sp, color = Color.Gray)
                    if (contact.email.isNotBlank()) Text(contact.email, fontSize = 14.sp, color = Color.Gray)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                if (contact.notifyOnPanic) {
                    AssistChip(onClick = {}, label = { Text("при панике") },
                        leadingIcon = { Icon(Icons.Filled.Notifications, null, Modifier.size(16.dp)) })
                    Spacer(Modifier.width(4.dp))
                }
                if (contact.notifyOnFinish) {
                    AssistChip(onClick = {}, label = { Text("в конце") },
                        leadingIcon = { Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp)) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Text("Изменить")
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text("Удалить", color = Color(0xFFD32F2F))
                }
            }
        }
    }
}

@Composable
fun ContactDialog(
    initial: Contact? = null,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Boolean, Boolean) -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var phone by rememberSaveable { mutableStateOf(initial?.phone ?: "") }
    var email by rememberSaveable { mutableStateOf(initial?.email ?: "") }
    var nPanic by rememberSaveable { mutableStateOf(initial?.notifyOnPanic ?: true) }
    var nFinish by rememberSaveable { mutableStateOf(initial?.notifyOnFinish ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новый контакт" else "Изменить контакт") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("Имя") }, singleLine = true)
                OutlinedTextField(value = phone, onValueChange = { phone = it },
                    label = { Text("Телефон (+7...)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(value = email, onValueChange = { email = it },
                    label = { Text("Email (необязательно)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = nPanic, onCheckedChange = { nPanic = it })
                    Text("Уведомлять при панике")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = nFinish, onCheckedChange = { nFinish = it })
                    Text("Уведомлять в конце («всё хорошо»)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && (phone.isNotBlank() || email.isNotBlank())) {
                    onSave(name.trim(), phone.trim(), email.trim(), nPanic, nFinish)
                }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

/* ========================= УТИЛИТЫ ========================= */

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