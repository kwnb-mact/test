package com.example.timeline

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Data class representing an event on the timeline.
 */
data class Event(
    val id: Int,
    val group: String,
    val title: String,
    val detail: String,
    val credit: String,
    val type: String,
    val region: String,
    val tags: List<String>,
    val start: LocalDateTime,
    val end: LocalDateTime?,
    val displayDate: String,
    val images: List<String>
)

/** Simple CSV parser that supports quoted fields. */
fun parseCSV(text: String): List<Map<String, String>> {
    val rows = mutableListOf<List<String>>()
    var i = 0
    var field = StringBuilder()
    val row = mutableListOf<String>()
    var inQuotes = false

    fun pushField() {
        row.add(field.toString())
        field = StringBuilder()
    }

    fun pushRow() {
        rows.add(row.toList())
        row.clear()
    }

    while (i < text.length) {
        val ch = text[i]
        when {
            inQuotes -> {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"'); i++
                    } else inQuotes = false
                } else field.append(ch)
            }
            ch == '"' -> inQuotes = true
            ch == ',' -> pushField()
            ch == '\n' -> { pushField(); pushRow() }
            ch == '\r' -> {}
            else -> field.append(ch)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) { pushField(); pushRow() }
    if (rows.isEmpty()) return emptyList()
    val header = rows.first().map { it.trim() }
    val dataRows = rows.drop(1).filter { r -> r.any { it.trim().isNotEmpty() } }
    return dataRows.map { r -> header.mapIndexed { idx, h -> h to (r.getOrNull(idx) ?: "") }.toMap() }
}

private fun parseDate(rec: Map<String, String>): LocalDateTime? {
    val y = rec["Year"]?.toIntOrNull() ?: return null
    val m = rec["Month"]?.toIntOrNull() ?: 1
    val d = rec["Day"]?.toIntOrNull() ?: 1
    val time = rec["Time"] ?: "00:00:00"
    val formatter = DateTimeFormatter.ofPattern("H[:m][:s]")
    val t = try { java.time.LocalTime.parse(time, formatter) } catch (e: Exception) { java.time.LocalTime.MIDNIGHT }
    return LocalDateTime.of(y, m.coerceIn(1, 12), d.coerceIn(1, 28), t.hour, t.minute, t.second)
}

private fun parseEndDate(rec: Map<String, String>): LocalDateTime? {
    val y = rec["End Year"]?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: return null
    val m = rec["End Month"]?.toIntOrNull() ?: 12
    val d = rec["End Day"]?.toIntOrNull() ?: 28
    val time = rec["End Time"] ?: "00:00:00"
    val formatter = DateTimeFormatter.ofPattern("H[:m][:s]")
    val t = try { java.time.LocalTime.parse(time, formatter) } catch (e: Exception) { java.time.LocalTime.MIDNIGHT }
    return LocalDateTime.of(y, m.coerceIn(1, 12), d.coerceIn(1, 28), t.hour, t.minute, t.second)
}

/** Default sample CSV used when network fails. */
const val FALLBACK_CSV = """Group,Year,Month,Day,Time,End Year,End Month,End Day,End Time,Display Date,Title,Detail,URL Image,URL Image,Credit,Type,Region,tag
世界,1868,1,1,,1877,1,1,,1868-1877,明治維新,日本の近代化を推し進めた変革,,,,Wikipedia,政治,日本,近代
世界,1914,7,28,,,1918,11,11,,1914-1918,第一次世界大戦,欧州を中心に勃発した世界大戦,,,,Wikipedia,戦争,欧州,20世紀
世界,1939,9,1,,,1945,9,2,,1939-1945,第二次世界大戦,世界規模の戦争,,,,Wikipedia,戦争,世界,20世紀
世界,1969,7,20,20:17Z,,,,,1969-07-20,アポロ11号月面着陸,人類初の月面着陸,https://upload.wikimedia.org/wikipedia/commons/9/9c/Aldrin_Apollo_11.jpg,,NASA,科学,米国,宇宙"""

suspend fun loadCSV(url: String): List<Map<String, String>> = withContext(Dispatchers.IO) {
    return@withContext try {
        val conn = URL(url).openConnection()
        val text = conn.getInputStream().use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }
        parseCSV(text)
    } catch (e: Exception) {
        parseCSV(FALLBACK_CSV)
    }
}

enum class Orientation { Horizontal, Vertical }

/** Main composable replicating the React timeline viewer. */
@Composable
fun TimelineCsvApp() {
    var csvUrl by remember { mutableStateOf(DEFAULT_CSV_URL) }
    var events by remember { mutableStateOf(listOf<Event>()) }
    var orientation by remember { mutableStateOf(Orientation.Horizontal) }
    var pxPerYear by remember { mutableStateOf(120f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    var query by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf("ALL") }
    var typeFilter by remember { mutableStateOf("ALL") }
    var regionFilter by remember { mutableStateOf("ALL") }
    var selected by remember { mutableStateOf<Event?>(null) }
    var showImages by remember { mutableStateOf(true) }

    LaunchedEffect(csvUrl) {
        val raw = loadCSV(csvUrl)
        events = raw.mapIndexedNotNull { idx, r ->
            val start = parseDate(r) ?: return@mapIndexedNotNull null
            val end = parseEndDate(r)
            val display = r["Display Date"]?.takeIf { it.isNotBlank() }
                ?: start.toLocalDate().toString() + (end?.let { " – ${it.toLocalDate()}" } ?: "")
            Event(
                id = idx + 1,
                group = r["Group"]?.ifBlank { "(未分類)" } ?: "(未分類)",
                title = r["Title"]?.ifBlank { "(無題)" } ?: "(無題)",
                detail = r["Detail"] ?: "",
                credit = r["Credit"] ?: "",
                type = r["Type"] ?: "",
                region = r["Region"] ?: "",
                tags = (r["tag"] ?: r["tags"] ?: "").split(';', '、', ',', ' ').filter { it.isNotBlank() },
                start = start,
                end = end,
                displayDate = display,
                images = listOfNotNull(r["URL Image"], r["URL Image__2"]).filter { it.isNotBlank() }
            )
        }
    }

    val groups = remember(events) { events.map { it.group }.toSet().toList() }
    val types = remember(events) { events.map { it.type }.filter { it.isNotBlank() }.toSet().toList() }
    val regions = remember(events) { events.map { it.region }.filter { it.isNotBlank() }.toSet().toList() }

    val filtered = events.filter { e ->
        (groupFilter == "ALL" || e.group == groupFilter) &&
            (typeFilter == "ALL" || e.type == typeFilter) &&
            (regionFilter == "ALL" || e.region == regionFilter) &&
            (query.isBlank() || e.title.contains(query) || e.detail.contains(query))
    }

    val range = remember(filtered, events) {
        val list = if (filtered.isNotEmpty()) filtered else events
        val min = list.minOfOrNull { it.start } ?: LocalDateTime.now().minusYears(5)
        val max = list.maxOfOrNull { it.end ?: it.start } ?: LocalDateTime.now().plusYears(1)
        min to max
    }

    val pxPerDay = pxPerYear / 365f

    Column(Modifier.fillMaxSize()) {
        Controls(
            csvUrl = csvUrl,
            onCsvUrlChange = { csvUrl = it },
            onReload = { csvUrl = it },
            orientation = orientation,
            onOrientationChange = { orientation = it },
            pxPerYear = pxPerYear,
            onPxPerYearChange = { pxPerYear = it },
            showImages = showImages,
            onShowImagesChange = { showImages = it },
            query = query,
            onQueryChange = { query = it },
            groups = groups,
            types = types,
            regions = regions,
            groupFilter = groupFilter,
            typeFilter = typeFilter,
            regionFilter = regionFilter,
            onGroupFilterChange = { groupFilter = it },
            onTypeFilterChange = { typeFilter = it },
            onRegionFilterChange = { regionFilter = it }
        )

        Box(Modifier.fillMaxSize().clipToBounds()) {
            TimelineCanvas(
                events = filtered,
                orientation = orientation,
                pxPerDay = pxPerDay,
                minDate = range.first,
                panX = panX,
                panY = panY,
                onTransform = { zoom, dx, dy ->
                    pxPerYear = (pxPerYear * zoom).coerceIn(10f, 3000f)
                    panX += dx
                    panY += dy
                },
                onSelect = { selected = it }
            )
            if (selected != null) {
                DetailPanel(selected!!, showImages) { selected = null }
            }
        }
    }
}

@Composable
private fun Controls(
    csvUrl: String,
    onCsvUrlChange: (String) -> Unit,
    onReload: (String) -> Unit,
    orientation: Orientation,
    onOrientationChange: (Orientation) -> Unit,
    pxPerYear: Float,
    onPxPerYearChange: (Float) -> Unit,
    showImages: Boolean,
    onShowImagesChange: (Boolean) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    groups: List<String>,
    types: List<String>,
    regions: List<String>,
    groupFilter: String,
    typeFilter: String,
    regionFilter: String,
    onGroupFilterChange: (String) -> Unit,
    onTypeFilterChange: (String) -> Unit,
    onRegionFilterChange: (String) -> Unit
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(8.dp)) {
            OutlinedTextField(
                value = csvUrl,
                onValueChange = onCsvUrlChange,
                label = { Text("CSV URL") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onReload(csvUrl) }) { Text("読み込む") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("向き")
            Spacer(Modifier.width(4.dp))
            SegmentedButton(orientation, onOrientationChange)
            Spacer(Modifier.width(16.dp))
            Text("ズーム")
            Slider(
                value = pxPerYear,
                onValueChange = onPxPerYearChange,
                valueRange = 10f..3000f,
                modifier = Modifier.width(200.dp)
            )
            Spacer(Modifier.width(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(showImages, onShowImagesChange)
                Text("画像を表示")
            }
            Spacer(Modifier.width(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("検索") },
                modifier = Modifier.width(220.dp)
            )
        }
        FilterRow("グループ", groups, groupFilter, onGroupFilterChange)
        FilterRow("タイプ", types, typeFilter, onTypeFilterChange)
        FilterRow("地域", regions, regionFilter, onRegionFilterChange)
    }
}

@Composable
private fun FilterRow(title: String, items: List<String>, selected: String, onSelected: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(4.dp))
        FilterChip(
            selected = selected == "ALL",
            onClick = { onSelected("ALL") },
            label = { Text("ALL") }
        )
        items.forEach { item ->
            Spacer(Modifier.width(4.dp))
            FilterChip(
                selected = selected == item,
                onClick = { onSelected(item) },
                label = { Text(item) }
            )
        }
    }
}

@Composable
private fun SegmentedButton(value: Orientation, onChange: (Orientation) -> Unit) {
    Row {
        Button(
            onClick = { onChange(Orientation.Horizontal) },
            colors = if (value == Orientation.Horizontal) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
            border = if (value == Orientation.Horizontal) null else ButtonDefaults.outlinedButtonBorder
        ) { Text("横") }
        Spacer(Modifier.width(4.dp))
        Button(
            onClick = { onChange(Orientation.Vertical) },
            colors = if (value == Orientation.Vertical) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
            border = if (value == Orientation.Vertical) null else ButtonDefaults.outlinedButtonBorder
        ) { Text("縦") }
    }
}

@Composable
private fun TimelineCanvas(
    events: List<Event>,
    orientation: Orientation,
    pxPerDay: Float,
    minDate: LocalDateTime,
    panX: Float,
    panY: Float,
    onTransform: (zoom: Float, dx: Float, dy: Float) -> Unit,
    onSelect: (Event) -> Unit
) {
    val laneHeight = 64.dp
    val laneWidth = 220.dp
    val headerHeight = 48.dp

    val groups = remember(events) { events.map { it.group }.toSet().sorted() }
    val laneMap = remember(groups) { groups.mapIndexed { index, s -> s to index }.toMap() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(events, orientation, pxPerDay, panX, panY) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransform(zoom, pan.x, pan.y)
                }
            }
            .graphicsLayer { translationX = panX; translationY = panY }
            .clipToBounds()
    ) {
        events.forEach { ev ->
            val laneIdx = laneMap[ev.group] ?: 0
            val startDays = Duration.between(minDate, ev.start).toDays().toFloat()
            val endDays = Duration.between(minDate, ev.end ?: ev.start).toDays().toFloat()
            val x = startDays * pxPerDay + laneWidth.toPx()
            val x2 = endDays * pxPerDay + laneWidth.toPx()
            val y = headerHeight.toPx() + laneIdx * laneHeight.toPx() + 8.dp.toPx()
            val width = max(8f, x2 - x)
            val height = laneHeight.toPx() - 16.dp.toPx()
            Box(
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(width.toDp(), height.toDp())
                    .background(Color(0x1188FFFF), shape = MaterialTheme.shapes.medium)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), shape = MaterialTheme.shapes.medium)
                    .clickable { onSelect(ev) }
                    .padding(4.dp),
                contentAlignment = Alignment.TopStart
            ) {
                Text(ev.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DetailPanel(event: Event, showImages: Boolean, onClose: () -> Unit) {
    Surface(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .width(360.dp)
            .fillMaxHeight(),
        tonalElevation = 8.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("詳細", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = null) }
            }
            Text(event.displayDate, style = MaterialTheme.typography.bodySmall)
            Text(event.title, style = MaterialTheme.typography.titleLarge)
            if (event.group.isNotBlank()) Text(event.group, style = MaterialTheme.typography.labelMedium)
            if (event.detail.isNotBlank()) Text(event.detail, style = MaterialTheme.typography.bodyMedium)
            if (showImages && event.images.isNotEmpty()) {
                event.images.forEach { url ->
                    Text(url, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (event.credit.isNotBlank()) Text("Credit: ${event.credit}", style = MaterialTheme.typography.bodySmall)
            if (event.tags.isNotEmpty()) {
                Text(event.tags.joinToString(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private val Dp.toPx: Float @Composable get() = with(LocalDensity.current) { toPx() }
@Composable
private fun Float.toDp(): Dp = (this / LocalDensity.current.density).dp

const val DEFAULT_CSV_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vQEwX_Ym_sWe9w9zcqaE7tsBM74xZ1i-LMQ5nisE_VaQFhtREQJuLqep-XQoEwm0VZZBOQCabZse0TH/pub?output=csv"

