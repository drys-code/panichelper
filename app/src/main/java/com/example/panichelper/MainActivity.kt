package com.example.panichelper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PanicHelperApp()
        }
    }
}

data class CheckEntry(
    val id: Long,
    val date: Long,
    val systolic: Int?,
    val diastolic: Int?,
    val pulse: Int?,
    val anxiety: Int,
    val note: String,
    val triggers: List<String>
)

data class AttackEntry(
    val id: Long,
    val date: Long,
    val durationMin: Int,
    val intensity: Int,
    val symptoms: List<String>,
    val triggers: List<String>,
    val helped: List<String>,
    val note: String
)

class Storage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "panic_helper",
        Context.MODE_PRIVATE
    )

    private val checksKey = "checks"
    private val attacksKey = "attacks"

    fun loadChecks(): List<CheckEntry> {
        val json = prefs.getString(checksKey, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val o = arr.getJSONObject(i)
                    CheckEntry(
                        id = o.optLong("id"),
                        date = o.optLong("date"),
                        systolic = o.optInt("sys", -1).takeIf { it > 0 },
                        diastolic = o.optInt("dia", -1).takeIf { it > 0 },
                        pulse = o.optInt("pulse", -1).takeIf { it > 0 },
                        anxiety = o.optInt("anxiety", 0),
                        note = o.optString("note"),
                        triggers = jsonArrayToList(o.optJSONArray("triggers"))
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveChecks(list: List<CheckEntry>) {
        val arr = JSONArray()
        list.forEach { c ->
            val o = JSONObject()
            o.put("id", c.id)
            o.put("date", c.date)
            o.put("sys", c.systolic ?: -1)
            o.put("dia", c.diastolic ?: -1)
            o.put("pulse", c.pulse ?: -1)
            o.put("anxiety", c.anxiety)
            o.put("note", c.note)
            o.put("triggers", listToJson(c.triggers))
            arr.put(o)
        }
        prefs.edit().putString(checksKey, arr.toString()).apply()
    }

    fun addCheck(entry: CheckEntry) {
        saveChecks(loadChecks() + entry)
    }

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
                        symptoms = jsonArrayToList(o.optJSONArray("symptoms")),
                        triggers = jsonArrayToList(o.optJSONArray("triggers")),
                        helped = jsonArrayToList(o.optJSONArray("helped")),
                        note = o.optString("note")
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAttacks(list: List<AttackEntry>) {
        val arr = JSONArray()
        list.forEach { a ->
            val o = JSONObject()
            o.put("id", a.id)
            o.put("date", a.date)
            o.put("duration", a.durationMin)
            o.put("intensity", a.intensity)
            o.put("symptoms", listToJson(a.symptoms))
            o.put("triggers", listToJson(a.triggers))
            o.put("helped", listToJson(a.helped))
            o.put("note", a.note)
            arr.put(o)
        }
        prefs.edit().putString(attacksKey, arr.toString()).apply()
    }

    fun addAttack(entry: AttackEntry) {
        saveAttacks(loadAttacks() + entry)
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

val commonTriggers = listOf(
    "Неизвестно",
    "Плохой сон",
    "Новости",
    "Конфликт",
    "Одиночество",
    "Мысли о здоровье/сердце",
    "Духота",
    "Физическая нагрузка",
    "Кофе/чай",
    "Лекарства",
    "Другое"
)

val commonSymptoms = listOf(
    "Сильное сердцебиение",
    "Перебои/замирание сердца",
    "Давление в груди",
    "Нехватка воздуха",
    "Головокружение",
    "Дрожь",
    "Потливость",
    "Страх смерти",
    "Онемение",
    "Тошнота"
)

val commonHelped = listOf(
    "Дыхание",
    "Заземление",
    "Холодная вода",
    "Разговор",
    "Прогулка",
    "Лекарство, назначенное врачом",
    "Отвлечение",
    "Ничего не помогло"
)

data class BottomTab(
    val title: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanicHelperApp() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        val context = LocalContext.current
        val storage = remember { Storage(context) }

        val checks = remember {
            mutableStateListOf<CheckEntry>().apply {
                addAll(storage.loadChecks().sortedByDescending { it.date })
            }
        }

        val attacks = remember {
            mutableStateListOf<AttackEntry>().apply {
                addAll(storage.loadAttacks().sortedByDescending { it.date })
            }
        }

        var selectedTab by rememberSaveable { mutableStateOf(0) }

        val tabs = listOf(
            BottomTab("Главная", Icons.Filled.Home),
            BottomTab("Дневник", Icons.Filled.Add),
            BottomTab("Приступ", Icons.Filled.Warning),
            BottomTab("Помощь", Icons.Filled.Favorite),
            BottomTab("Анализ", Icons.Filled.Info)
        )

        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title) },
                            alwaysShowLabel = false
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    0 -> HomeScreen(checks, attacks) { selectedTab = it }
                    1 -> DiaryScreen(checks) { entry ->
                        storage.addCheck(entry)
                        checks.add(0, entry)
                    }
                    2 -> AttackScreen(attacks) { entry ->
                        storage.addAttack(entry)
                        attacks.add(0, entry)
                    }
                    3 -> HelpScreen()
                    4 -> StatsScreen(checks, attacks)
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    checks: List<CheckEntry>,
    attacks: List<AttackEntry>,
    goTo: (Int) -> Unit
) {
    val context = LocalContext.current
    val lastCheck = checks.firstOrNull()
    val lastAttack = attacks.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "АнтиПаника",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Card {
            Text(
                text = "Приложение не заменяет врача и не ставит диагноз. " +
                        "Если есть боль в груди, сильная одышка, обморок, резкая слабость, " +
                        "нарушение речи или асимметрия лица — вызывайте скорую помощь.",
                modifier = Modifier.padding(12.dp)
            )
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = "Экстренная помощь",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Если сейчас очень плохо или есть опасные симптомы — позвоните в скорую.",
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { dialPhone(context, "112") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("112")
                    }
                    Button(
                        onClick = { dialPhone(context, "103") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("103")
                    }
                }
            }
        }

        if (lastCheck != null && isRedFlag(lastCheck)) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "В последней записи есть высокие показатели или сильная тревога. " +
                            "Покажите данные врачу. Если есть опасные симптомы — позвоните 112.",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { goTo(1) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Добавить измерение")
            }

            Button(
                onClick = { goTo(2) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Записать приступ")
            }

            Button(
                onClick = { goTo(3) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Дыхание и помощь")
            }
        }

        Text(
            text = "Последнее измерение",
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp
        )

        if (lastCheck == null) {
            Text("Пока нет измерений. Добавьте первую запись во вкладке “Дневник”.")
        } else {
            CheckCard(lastCheck)
        }

        Text(
            text = "Последний приступ",
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp
        )

        if (lastAttack == null) {
            Text("Пока нет записей о приступах.")
        } else {
            AttackCard(lastAttack)
        }
    }
}

@Composable
fun DiaryScreen(
    checks: List<CheckEntry>,
    onAddCheck: (CheckEntry) -> Unit
) {
    val context = LocalContext.current

    var sys by remember { mutableStateOf("") }
    var dia by remember { mutableStateOf("") }
    var pulse by remember { mutableStateOf("") }
    var anxiety by remember { mutableStateOf(5f) }
    var note by remember { mutableStateOf("") }
    var selectedTriggers by remember { mutableStateOf(emptyList<String>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Дневник самочувствия",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Text("Отметьте давление, пульс и уровень тревоги.")

        NumberField(
            label = "Систолическое давление (верхнее)",
            value = sys,
            onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) sys = it }
        )

        NumberField(
            label = "Диастолическое давление (нижнее)",
            value = dia,
            onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) dia = it }
        )

        NumberField(
            label = "Пульс",
            value = pulse,
            onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) pulse = it }
        )

        Text("Тревога: ${anxiety.toInt()}/10")
        Slider(
            value = anxiety,
            onValueChange = { anxiety = it },
            valueRange = 0f..10f,
            steps = 9
        )

        CheckboxSection(
            title = "Возможные триггеры",
            items = commonTriggers,
            selected = selectedTriggers,
            onToggle = { item -> selectedTriggers = toggle(selectedTriggers, item) }
        )

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Заметка: что происходило, мысли, самочувствие") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp),
            singleLine = false
        )

        Button(
            onClick = {
                val s = if (sys.isBlank()) null else sys.toIntOrNull()
                val d = if (dia.isBlank()) null else dia.toIntOrNull()
                val p = if (pulse.isBlank()) null else pulse.toIntOrNull()

                if (sys.isNotBlank() && s == null) {
                    Toast.makeText(context, "Проверьте верхнее давление", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (dia.isNotBlank() && d == null) {
                    Toast.makeText(context, "Проверьте нижнее давление", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (pulse.isNotBlank() && p == null) {
                    Toast.makeText(context, "Проверьте пульс", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val now = System.currentTimeMillis()
                val entry = CheckEntry(
                    id = now,
                    date = now,
                    systolic = s,
                    diastolic = d,
                    pulse = p,
                    anxiety = anxiety.toInt(),
                    note = note.trim(),
                    triggers = selectedTriggers
                )

                onAddCheck(entry)

                sys = ""
                dia = ""
                pulse = ""
                note = ""
                anxiety = 5f
                selectedTriggers = emptyList()

                Toast.makeText(context, "Сохранено", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сохранить измерение")
        }

        Text(
            text = "Последние записи",
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp
        )

        checks.take(20).forEach { CheckCard(it) }
    }
}

@Composable
fun AttackScreen(
    attacks: List<AttackEntry>,
    onAddAttack: (AttackEntry) -> Unit
) {
    val context = LocalContext.current

    var duration by remember { mutableStateOf("") }
    var intensity by remember { mutableStateOf(6f) }
    var note by remember { mutableStateOf("") }
    var selectedSymptoms by remember { mutableStateOf(emptyList<String>()) }
    var selectedTriggers by remember { mutableStateOf(emptyList<String>()) }
    var selectedHelped by remember { mutableStateOf(emptyList<String>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Записать приступ",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        NumberField(
            label = "Длительность в минутах",
            value = duration,
            onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) duration = it }
        )

        Text("Интенсивность: ${intensity.toInt()}/10")
        Slider(
            value = intensity,
            onValueChange = { intensity = it },
            valueRange = 0f..10f,
            steps = 9
        )

        CheckboxSection(
            title = "Симптомы",
            items = commonSymptoms,
            selected = selectedSymptoms,
            onToggle = { item -> selectedSymptoms = toggle(selectedSymptoms, item) }
        )

        CheckboxSection(
            title = "Возможные триггеры",
            items = commonTriggers,
            selected = selectedTriggers,
            onToggle = { item -> selectedTriggers = toggle(selectedTriggers, item) }
        )

        CheckboxSection(
            title = "Что помогло",
            items = commonHelped,
            selected = selectedHelped,
            onToggle = { item -> selectedHelped = toggle(selectedHelped, item) }
        )

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Заметка") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 100.dp),
            singleLine = false
        )

        Button(
            onClick = {
                val d = duration.toIntOrNull()
                if (d == null || d <= 0) {
                    Toast.makeText(context, "Укажите длительность", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val now = System.currentTimeMillis()
                val entry = AttackEntry(
                    id = now,
                    date = now,
                    durationMin = d,
                    intensity = intensity.toInt(),
                    symptoms = selectedSymptoms,
                    triggers = selectedTriggers,
                    helped = selectedHelped,
                    note = note.trim()
                )

                onAddAttack(entry)

                duration = ""
                intensity = 6f
                note = ""
                selectedSymptoms = emptyList()
                selectedTriggers = emptyList()
                selectedHelped = emptyList()

                Toast.makeText(context, "Приступ сохранен", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Сохранить приступ")
        }

        Text(
            text = "Последние приступы",
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp
        )

        attacks.take(20).forEach { AttackCard(it) }
    }
}

@Composable
fun HelpScreen() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Помощь",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = "Если есть боль в груди, обморок, сильная одышка, нарушение речи или асимметрия лица — вызывайте скорую.",
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { dialPhone(context, "112") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("112")
                    }
                    Button(
                        onClick = { dialPhone(context, "103") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("103")
                    }
                }
            }
        }

        BreathingCard()
        GroundingCard()
        PlanCard()
    }
}

@Composable
fun BreathingCard() {
    var running by remember { mutableStateOf(false) }
    var inhale by remember { mutableStateOf(true) }
    var cycles by remember { mutableStateOf(0) }

    val scale by animateFloatAsState(
        targetValue = if (inhale) 1f else 0.65f,
        animationSpec = tween(if (inhale) 4000 else 6000),
        label = "breath"
    )

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            inhale = true
            delay(4000)
            inhale = false
            delay(6000)
            cycles++
        }
    }

    Card {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Дыхание: вдох 4 секунды, выдох 6 секунд",
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(8.dp))

            if (!running) {
                Button(onClick = {
                    cycles = 0
                    running = true
                }) {
                    Text("Начать дыхание")
                }
            } else {
                Button(onClick = {
                    running = false
                    inhale = true
                }) {
                    Text("Остановить")
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size((180 * scale).dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2E7D32))
                )
            }

            Text(
                text = if (inhale) "Вдох носом" else "Медленный выдох ртом",
                fontSize = 18.sp
            )

            Text("Циклов: $cycles")

            Text(
                text = "Если появляется головокружение — остановитесь и дышите как удобно.",
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun GroundingCard() {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "Заземление 5-4-3-2-1",
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Назовите:\n" +
                        "5 предметов, которые видите;\n" +
                        "4 предмета, которые можете потрогать;\n" +
                        "3 звука, которые слышите;\n" +
                        "2 запаха, которые чувствуете;\n" +
                        "1 вкус или сделайте глоток воды."
            )
        }
    }
}

@Composable
fun PlanCard() {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "План действий",
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "1. Сказать себе: “Это волна, она пройдет”.\n" +
                        "2. Сесть или лечь удобно.\n" +
                        "3. Дышать: вдох короче, выдох длиннее.\n" +
                        "4. Выпить воды или умыться прохладной водой.\n" +
                        "5. Позвонить близкому человеку.\n" +
                        "6. Если есть опасные симптомы — вызвать скорую."
            )
        }
    }
}

@Composable
fun StatsScreen(
    checks: List<CheckEntry>,
    attacks: List<AttackEntry>
) {
    val sortedChecks = checks.sortedBy { it.date }
    val recentChecks = sortedChecks.takeLast(30)

    val anxietyValues = recentChecks.map { it.anxiety.toFloat() }
    val pulseValues = recentChecks.mapNotNull { it.pulse }.map { it.toFloat() }
    val sysValues = recentChecks.mapNotNull { it.systolic }.map { it.toFloat() }
    val diaValues = recentChecks.mapNotNull { it.diastolic }.map { it.toFloat() }

    val weekly = attacksPerWeek(attacks)
    val top = topTriggers(checks, attacks)

    val totalAttacks = attacks.size
    val avgIntensity = attacks.map { it.intensity }.avg()
    val avgAnxiety = sortedChecks.map { it.anxiety }.avg()
    val avgSys = sortedChecks.mapNotNull { it.systolic }.avg()
    val avgDia = sortedChecks.mapNotNull { it.diastolic }.avg()
    val avgPulse = sortedChecks.mapNotNull { it.pulse }.avg()

    val highBpCount = sortedChecks.count {
        (it.systolic ?: 0) >= 180 || (it.diastolic ?: 0) >= 110
    }

    val cardiacAttacks = attacks.count { a ->
        a.symptoms.any {
            it.contains("сердце", ignoreCase = true) ||
                    it.contains("Перебои", ignoreCase = true) ||
                    it.contains("Давление в груди", ignoreCase = true)
        }
    }

    val withTriggerAvg = checks.filter { it.triggers.isNotEmpty() }.map { it.anxiety }.avg()
    val withoutTriggerAvg = checks.filter { it.triggers.isEmpty() }.map { it.anxiety }.avg()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Анализ",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Card {
            Column(Modifier.padding(12.dp)) {
                Text("Сводка", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("Приступов всего: $totalAttacks")
                Text("Средняя интенсивность приступа: ${avgIntensity ?: "—"}/10")
                Text("Средняя тревога: ${avgAnxiety ?: "—"}/10")
                Text("Среднее АД: ${avgSys ?: "—"}/${avgDia ?: "—"}")
                Text("Средний пульс: ${avgPulse ?: "—"}")

                if (highBpCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Есть $highBpCount записей с очень высоким давлением. Это важно показать врачу.",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (cardiacAttacks > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "В $cardiacAttacks приступах были сердечные симптомы. Дневник нужно показать терапевту или кардиологу.",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (withTriggerAvg != null && withoutTriggerAvg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Средняя тревога с триггерами: $withTriggerAvg/10, без триггеров: $withoutTriggerAvg/10."
                    )
                }
            }
        }

        Card {
            Column(Modifier.padding(12.dp)) {
                Text("Топ триггеров", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                if (top.isEmpty()) {
                    Text("Пока нет данных по триггерам.")
                } else {
                    top.forEachIndexed { index, pair ->
                        Text("${index + 1}. ${pair.first} — ${pair.second}")
                    }
                }
            }
        }

        ChartCard("Тревога по последним записям") {
            SimpleLineChart(anxietyValues, Color(0xFF6750A4))
        }

        ChartCard("Пульс") {
            SimpleLineChart(pulseValues, Color(0xFFEF6C00))
        }

        ChartCard("Систолическое давление") {
            SimpleLineChart(sysValues, Color(0xFF1565C0))
        }

        ChartCard("Диастолическое давление") {
            SimpleLineChart(diaValues, Color(0xFF2E7D32))
        }

        ChartCard("Приступы за 8 недель") {
            if (weekly.sum() == 0) {
                Text("Нет приступов за последние 8 недель.")
            } else {
                SimpleBarChart(weekly.map { it.toFloat() }, MaterialTheme.colorScheme.primary)
            }
        }

        Card {
            Text(
                text = "Покажите эти графики врачу. Особенно важны записи с высоким давлением, пульсом и сердечными симптомами.",
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
fun ChartCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SimpleLineChart(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (values.size < 2) {
        Text("Недостаточно данных для графика")
        return
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        val padding = 24f
        val min = values.minOrNull() ?: 0f
        val max = values.maxOrNull() ?: 1f
        val range = if (max - min < 1f) 1f else max - min
        val stepX = (size.width - padding * 2) / (values.size - 1)

        var prev: Offset? = null

        values.forEachIndexed { index, value ->
            val x = padding + index * stepX
            val y = size.height - padding - ((value - min) / range) * (size.height - padding * 2)
            val point = Offset(x, y)

            prev?.let {
                drawLine(
                    color = color,
                    start = it,
                    end = point,
                    strokeWidth = 4f
                )
            }

            drawCircle(
                color = color,
                radius = 6f,
                center = point
            )

            prev = point
        }
    }
}

@Composable
fun SimpleBarChart(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (values.isEmpty()) return

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        val padding = 24f
        val max = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val slot = (size.width - padding * 2) / values.size
        val barWidth = slot * 0.7f
        val gap = slot * 0.3f

        values.forEachIndexed { index, value ->
            val barHeight = (value / max) * (size.height - padding * 2)
            val left = padding + index * slot + gap / 2
            val top = size.height - padding - barHeight

            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight)
            )
        }
    }
}

@Composable
fun CheckCard(item: CheckEntry) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = formatDate(item.date),
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "АД: ${item.systolic ?: "—"}/${item.diastolic ?: "—"}  " +
                        "Пульс: ${item.pulse ?: "—"}  " +
                        "Тревога: ${item.anxiety}/10"
            )
            if (item.triggers.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Триггеры: ${item.triggers.joinToString()}")
            }
            if (item.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Заметка: ${item.note}")
            }
        }
    }
}

@Composable
fun AttackCard(item: AttackEntry) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = formatDate(item.date),
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Длительность: ${item.durationMin} мин  Интенсивность: ${item.intensity}/10"
            )
            if (item.symptoms.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Симптомы: ${item.symptoms.joinToString()}")
            }
            if (item.triggers.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Триггеры: ${item.triggers.joinToString()}")
            }
            if (item.helped.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Что помогло: ${item.helped.joinToString()}")
            }
            if (item.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Заметка: ${item.note}")
            }
        }
    }
}

@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
        singleLine = true
    )
}

@Composable
fun CheckboxSection(
    title: String,
    items: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit
) {
    Column {
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold
        )
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = selected.contains(item),
                    onCheckedChange = { onToggle(item) }
                )
                Text(
                    text = item,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

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
        Toast.makeText(context, "Не удалось открыть номер", Toast.LENGTH_SHORT).show()
    }
}

fun isRedFlag(entry: CheckEntry): Boolean {
    val sys = entry.systolic ?: 0
    val dia = entry.diastolic ?: 0
    val pulse = entry.pulse ?: 0
    return sys >= 180 || dia >= 110 || pulse >= 150 || entry.anxiety >= 9
}

fun toggle(list: List<String>, item: String): List<String> {
    return if (list.contains(item)) list - item else list + item
}

private fun List<Int>.avg(): Int? {
    if (isEmpty()) return null
    return (sum().toFloat() / size).toInt()
}

private fun topTriggers(
    checks: List<CheckEntry>,
    attacks: List<AttackEntry>,
    limit: Int = 5
): List<Pair<String, Int>> {
    val map = mutableMapOf<String, Int>()
    val all = checks.flatMap { it.triggers } + attacks.flatMap { it.triggers }
    all.forEach { item ->
        map[item] = (map[item] ?: 0) + 1
    }
    return map.entries
        .sortedByDescending { it.value }
        .take(limit)
        .map { it.key to it.value }
}

private fun attacksPerWeek(
    attacks: List<AttackEntry>,
    weeks: Int = 8
): List<Int> {
    val now = System.currentTimeMillis()
    val weekMs = 7L * 24L * 3600L * 1000L
    val counts = MutableList(weeks) { 0 }

    attacks.forEach { attack ->
        val age = now - attack.date
        if (age >= 0 && age < weekMs * weeks) {
            val idx = (age / weekMs).toInt()
            if (idx in 0 until weeks) {
                counts[weeks - 1 - idx] += 1
            }
        }
    }

    return counts
}