package com.yarisradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val Bg = Color(0xFFF4F6F5)
private val Surface = Color(0xFFFFFFFF)
private val Ink = Color(0xFF111815)
private val Muted = Color(0xFF69736E)
private val Green = Color(0xFF0E6B47)
private val Green2 = Color(0xFF17865A)
private val PaleGreen = Color(0xFFE5F4EC)
private val Gold = Color(0xFFD89B2B)
private val PaleGold = Color(0xFFFFF3D9)
private val Red = Color(0xFFB64A3A)
private val PaleRed = Color(0xFFFFECE8)
private val Border = Color(0xFFE1E7E3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HorsAiApp() }
    }
}

data class Horse(
    val no: Int,
    val name: String,
    val weight: Double? = null,
    val jockey: String = "",
    val hp: Int? = null,
    val last6: String = "",
    val best: String = "",
    val odds: Double? = null,
    val agf: Int? = null,
    val start: Int? = null
)

data class Race(
    val number: Int,
    val time: String,
    val title: String,
    val distance: String,
    val surface: String,
    val horses: List<Horse>,
    val city: String = ""
)

data class Meeting(
    val city: String,
    val date: String,
    val trackInfo: String,
    val races: List<Race>
)

data class Pick(val horse: Horse, val score: Int, val label: String, val reasons: List<String>)

object TjkRepository {
    private const val BASE = "https://www.tjk.org"
    private val turkeyTz = java.util.TimeZone.getTimeZone("Europe/Istanbul")
    private val domesticCities = listOf(
        "İstanbul", "Ankara", "İzmir", "Bursa", "Kocaeli", "Adana", "Şanlıurfa",
        "Elazığ", "Diyarbakır", "Antalya"
    )

    private val http by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(14, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(18, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    suspend fun loadToday(): List<Meeting> = withContext(Dispatchers.IO) {
        val date = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { timeZone = turkeyTz }.format(Date())

        // Önce tek bir resmi TJK sayfasından bugün yarış olan şehirleri keşfet.
        // Böylece önceki sürümdeki gibi 10 şehir x birden çok URL isteği atıp TJK tarafından
        // yavaşlatılma/engellenme riskini ciddi biçimde azaltıyoruz.
        val discoveryDoc = fetchFirst(
            listOf(
                "$BASE/TR/YarisSever/Info/Page/GunlukYarisProgrami?Era=today&QueryParameter_Tarih=${enc(date)}",
                "$BASE/TR/Kurumsal/Info/Page/GunlukYarisProgrami?Era=today&QueryParameter_Tarih=${enc(date)}",
                "$BASE/TR/map/Info/Page/GunlukYarisProgrami?Era=today&QueryParameter_Tarih=${enc(date)}"
            )
        )

        // TJK'nın Page/GunlukYarisProgrami adresi yalnızca toplantı/şehir seçme kabuğunu
        // döndürüyor. Koşu tabloları şehir linklerindeki Info/Sehir/GunlukYarisProgrami
        // sayfasında. Bu yüzden şehir adını tahmin edip Page URL'sine eklemek yerine,
        // TJK'nın kendi ürettiği şehir linklerini aynen takip ediyoruz (SehirId dahil).
        val cityLinks = discoveryDoc?.let { discoverDomesticCityLinks(it) }.orEmpty()

        coroutineScope {
            val loaded = if (cityLinks.isNotEmpty()) {
                cityLinks.map { (city, url) ->
                    async { runCatching { loadCityFromUrl(city, date, url) }.getOrNull() }
                }.awaitAll()
            } else {
                // Son çare: eski yöntem. Çoğu durumda discovery linkleri bulunduğu için buraya düşmez.
                domesticCities.map { city ->
                    async { runCatching { loadCity(city, date) }.getOrNull() }
                }.awaitAll()
            }

            loaded.filterNotNull()
                .filter { it.races.isNotEmpty() }
                .distinctBy { it.city }
                .sortedBy { it.races.firstOrNull()?.time ?: "99:99" }
        }
    }

    private fun loadCityFromUrl(city: String, date: String, url: String): Meeting? {
        val doc = fetchFirst(listOf(url)) ?: return null
        return parseMeeting(doc, city, date).takeIf { it.races.isNotEmpty() }
    }

    private fun loadCity(city: String, date: String): Meeting? {
        // Fallback URL'leri şehir detay endpoint'ine gider. Page endpoint'inde yarış tabloları yoktur.
        // İstanbul için SehirId=3 bilinmektedir; diğer şehirlerde discovery akışı kullanılmalıdır.
        val cityId = if (city.equals("İstanbul", true)) 3 else null
        val suffix = buildString {
            append("?Era=today&QueryParameter_Tarih=${enc(date)}&SehirAdi=${enc(city)}")
            cityId?.let { append("&SehirId=$it") }
        }
        val urls = listOf(
            "$BASE/TR/YarisSever/Info/Sehir/GunlukYarisProgrami$suffix",
            "$BASE/TR/Kurumsal/Info/Sehir/GunlukYarisProgrami$suffix",
            "$BASE/TR/map/Info/Sehir/GunlukYarisProgrami$suffix"
        )
        val doc = fetchFirst(urls) ?: return null
        return parseMeeting(doc, city, date).takeIf { it.races.isNotEmpty() }
    }

    private fun fetchFirst(urls: List<String>): Document? {
        var lastError: Throwable? = null
        for (url in urls) {
            repeat(2) { attempt ->
                try {
                    return fetch(url)
                } catch (t: Throwable) {
                    lastError = t
                    if (attempt == 0) Thread.sleep(350)
                }
            }
        }
        if (lastError != null) android.util.Log.w("HorsAI", "TJK fetch failed", lastError)
        return null
    }

    private fun fetch(url: String): Document {
        val request = okhttp3.Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
            .header("Referer", "$BASE/")
            .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.6")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Cache-Control", "no-cache")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("TJK HTTP ${response.code}")
            val html = response.body?.string().orEmpty()
            if (html.length < 500 || (!html.contains("Koşu", true) && !html.contains("Yarış Programı", true))) {
                error("TJK geçersiz/eksik yanıt")
            }
            return Jsoup.parse(html, response.request.url.toString())
        }
    }

    private fun discoverDomesticCityLinks(doc: Document): List<Pair<String, String>> {
        val found = linkedMapOf<String, String>()
        doc.select("a[href]").forEach { a ->
            val text = clean(a.text())
            val city = domesticCities.firstOrNull { c ->
                text.equals(c, true) || text.startsWith("$c (", true)
            } ?: return@forEach

            val href = a.attr("href")
            if (!href.contains("/Info/Sehir/GunlukYarisProgrami", true)) return@forEach
            val absolute = a.attr("abs:href").ifBlank {
                if (href.startsWith("http", true)) href else BASE + if (href.startsWith('/')) href else "/$href"
            }
            found.putIfAbsent(city, absolute)
        }
        return found.entries.map { it.key to it.value }
    }

    private fun parseMeeting(doc: Document, city: String, date: String): Meeting {
        val bodyText = doc.body().text().replace(Regex("\\s+"), " ")
        val trackInfo = Regex("(Çim:\\s*.*?)(?=PDF Program|Özet PDF|CSV Program|1\\. Koşu)", setOf(RegexOption.IGNORE_CASE))
            .find(bodyText)?.groupValues?.getOrNull(1)?.trim().orEmpty().take(220)

        val h3s = doc.select("h3").map { it.text().replace(Regex("\\s+"), " ").trim() }
        val raceHeads = h3s.mapIndexedNotNull { idx, text ->
            val m = Regex("^(\\d+)\\.\\s*Koşu\\s+(\\d{1,2}[.:]\\d{2})", RegexOption.IGNORE_CASE).find(text)
                ?: return@mapIndexedNotNull null
            val title = h3s.drop(idx + 1).firstOrNull { candidate ->
                candidate.isNotBlank() &&
                    !Regex("^\\d+\\.\\s*Koşu", RegexOption.IGNORE_CASE).containsMatchIn(candidate) &&
                    (candidate.contains("Çim", true) || candidate.contains("Kum", true) || candidate.contains("Sentetik", true))
            }.orEmpty()
            Triple(m.groupValues[1].toInt(), m.groupValues[2].replace('.', ':'), title)
        }.distinctBy { it.first }.sortedBy { it.first }

        // Ana program tablosunu başlık isimlerinden tanıyoruz. Gizli/yardımcı tabloları,
        // aynı at numaraları tekrar ediyorsa yarış sayısıyla sınırlandırıyoruz.
        val candidateTables = doc.select("table").filter { table ->
            val header = table.select("tr").firstOrNull()?.select("th,td")?.joinToString(" ") { it.text() }.orEmpty()
            header.contains("At İsmi", true) && header.contains("Jokey", true) &&
                (header.contains("Sıklet", true) || header.contains("Siklet", true))
        }

        val parsedTables = candidateTables.mapNotNull { table ->
            parseHorses(table).takeIf { horses ->
                horses.size >= 2 && horses.map { it.no }.distinct().size == horses.size
            }
        }

        val raceCount = if (raceHeads.isNotEmpty()) raceHeads.size else parsedTables.size
        val tables = parsedTables.take(raceCount)

        val races = tables.mapIndexedNotNull { index, horses ->
            if (horses.isEmpty()) return@mapIndexedNotNull null
            val head = raceHeads.getOrNull(index)
            val number = head?.first ?: index + 1
            val time = head?.second ?: "--:--"
            val title = head?.third?.takeIf { it.isNotBlank() } ?: "$number. Koşu"
            val surface = when {
                title.contains("Sentetik", true) -> "Sentetik"
                title.contains("Çim", true) -> "Çim"
                title.contains("Kum", true) -> "Kum"
                else -> ""
            }
            val distance = Regex("(\\d{3,4})\\s+(Çim|Kum|Sentetik)", RegexOption.IGNORE_CASE)
                .find(title)?.groupValues?.getOrNull(1).orEmpty()
            Race(number, time, title, distance, surface, horses, city)
        }

        return Meeting(city, date, trackInfo, races)
    }

    private fun parseHorses(table: Element): List<Horse> {
        val firstRow = table.select("tr").firstOrNull() ?: return emptyList()
        val headerCells = firstRow.select("th").ifEmpty { firstRow.select("td") }
        val headers = headerCells.map { normalizeHeader(it.text()) }
        if (headers.isEmpty()) return emptyList()

        fun idx(vararg names: String): Int = headers.indexOfFirst { h ->
            names.any { n -> h == normalizeHeader(n) || h.contains(normalizeHeader(n)) }
        }
        val iNo = idx("N", "No")
        val iName = idx("At İsmi", "At Ismi")
        val iWeight = idx("Sıklet", "Siklet")
        val iJockey = idx("Jokey")
        val iStart = idx("St")
        val iHp = idx("HP")
        val iLast6 = idx("Son 6 Y.", "Son 6")
        val iBest = idx("En İyi D.", "En Iyi D")
        val iOdds = idx("Gny")
        val iAgf = idx("AGF")
        if (iNo < 0 || iName < 0) return emptyList()

        return table.select("tr").drop(1).mapNotNull { row ->
            val cells = row.select("td")
            fun cellEl(i: Int): Element? = if (i >= 0 && i < cells.size) cells[i] else null
            fun cell(i: Int): String = cellEl(i)?.text().orEmpty()
            val no = cell(iNo).trim().filter { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null

            val nameCell = cellEl(iName) ?: return@mapNotNull null
            val linkedName = nameCell.select("a").firstOrNull()?.ownText()?.trim().orEmpty()
            val rawName = if (linkedName.isNotBlank()) linkedName else nameCell.ownText().trim()
            val name = rawName
                .replace(Regex("\\s+"), " ")
                .substringBefore("^")
                .substringBefore(" (")
                .trim()
            if (name.isBlank()) return@mapNotNull null

            val agfText = cell(iAgf)
            val agf = Regex("%?\\s*(\\d{1,3})(?:[,.]\\d+)?")
                .find(agfText)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..100 }

            Horse(
                no = no,
                name = name,
                weight = numericDouble(cell(iWeight)),
                jockey = clean(cell(iJockey)),
                hp = firstInt(cell(iHp)),
                last6 = cell(iLast6).replace(Regex("[^0-9-]"), "").take(8),
                best = clean(cell(iBest)).lineSequence().firstOrNull().orEmpty(),
                odds = numericDouble(cell(iOdds)),
                agf = agf,
                start = firstInt(cell(iStart))
            )
        }.distinctBy { it.no }
    }

    private fun normalizeHeader(value: String): String = value
        .lowercase(Locale.forLanguageTag("tr-TR"))
        .replace("ı", "i").replace("ş", "s").replace("ğ", "g")
        .replace("ü", "u").replace("ö", "o").replace("ç", "c")
        .replace(Regex("[^a-z0-9]+"), "")

    private fun clean(value: String?): String = value.orEmpty().replace(Regex("\\s+"), " ").trim()
    private fun firstInt(value: String?): Int? = Regex("\\d+").find(value.orEmpty())?.value?.toIntOrNull()
    private fun numericDouble(value: String?): Double? {
        val m = Regex("\\d+(?:[,.]\\d+)?").find(value.orEmpty()) ?: return null
        return m.value.replace(',', '.').toDoubleOrNull()
    }
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
}

object Predictor {
    fun picks(race: Race): List<Pick> {
        if (race.horses.isEmpty()) return emptyList()
        val maxHp = race.horses.mapNotNull { it.hp }.maxOrNull()?.coerceAtLeast(1) ?: 1
        val minWeight = race.horses.mapNotNull { it.weight }.minOrNull() ?: 0.0
        val minOdds = race.horses.mapNotNull { it.odds }.minOrNull()?.coerceAtLeast(.1) ?: 1.0

        val raw = race.horses.map { h ->
            var score = 42.0
            val reasons = mutableListOf<String>()
            h.agf?.let {
                score += it * .78
                if (it >= 18) reasons += "AGF desteği yüksek"
            }
            h.hp?.let {
                score += (it.toDouble() / maxHp) * 16
                if (it >= maxHp - 5) reasons += "HP gruba göre güçlü"
            }
            h.weight?.let { w ->
                val advantage = max(0.0, 5.0 - (w - minWeight))
                score += advantage * 1.25
                if (w <= minWeight + 1.0) reasons += "kilo avantajı"
            }
            h.odds?.let { o ->
                score += min(13.0, (minOdds / o) * 13)
                if (o <= minOdds * 1.6) reasons += "piyasa desteği"
            }
            val form = h.last6.takeLast(5)
            val wins = form.count { it == '1' }
            val places = form.count { it in '1'..'3' }
            score += wins * 4.0 + places * 1.6
            if (wins > 0) reasons += "yakın formunda galibiyet"
            if (h.best.isNotBlank()) reasons += "pist/mesafe derecesi var"
            h to (score to reasons)
        }

        val maxRaw = raw.maxOf { it.second.first }
        val minRaw = raw.minOf { it.second.first }
        val sorted = raw.sortedByDescending { it.second.first }
        return sorted.mapIndexed { index, item ->
            val normalized = if (maxRaw == minRaw) 70 else
                (54 + ((item.second.first - minRaw) / (maxRaw - minRaw)) * 40).toInt().coerceIn(45, 94)
            val agf = item.first.agf ?: 0
            val label = when {
                index == 0 -> "Favori"
                index == 1 -> "Ciddi rakip"
                agf <= 9 && normalized >= 60 -> "Sürpriz"
                else -> "Alternatif"
            }
            Pick(item.first, normalized, label, item.second.second.distinct().take(3))
        }
    }
}

@Composable
fun HorsAiApp() {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Green,
            secondary = Gold,
            background = Bg,
            surface = Surface,
            onSurface = Ink
        )
    ) {
        var meetings by remember { mutableStateOf<List<Meeting>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var selectedRace by remember { mutableStateOf<Race?>(null) }
        val scope = rememberCoroutineScope()

        fun refresh() {
            scope.launch {
                loading = true
                error = null
                val result = runCatching { TjkRepository.loadToday() }
                meetings = result.getOrDefault(emptyList())
                if (meetings.isEmpty()) error = result.exceptionOrNull()?.message ?: "Türkiye programı bulunamadı."
                loading = false
            }
        }

        LaunchedEffect(Unit) { refresh() }

        Surface(Modifier.fillMaxSize(), color = Bg) {
            selectedRace?.let { race ->
                RaceDetail(race, onBack = { selectedRace = null })
            } ?: Home(meetings, loading, error, onRefresh = ::refresh, onRace = { selectedRace = it })
        }
    }
}

@Composable
fun Home(
    meetings: List<Meeting>,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onRace: (Race) -> Unit
) {
    var selectedCity by remember(meetings) { mutableStateOf("Tümü") }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowTick = System.currentTimeMillis()
        }
    }

    // Yalnızca henüz başlamamış koşular uygulamada görünür. Sayaç sıfıra
    // ulaştığı anda koşu listeden ve şehir filtresinden otomatik kalkar.
    val activeMeetings = remember(meetings, nowTick) {
        meetings.mapNotNull { meeting ->
            val futureRaces = meeting.races
                .filter { secondsUntil(it.time, nowTick) > 0L }
                .sortedBy { secondsUntil(it.time, nowTick) }
            if (futureRaces.isEmpty()) null else meeting.copy(races = futureRaces)
        }
    }
    val cities = remember(activeMeetings) { listOf("Tümü") + activeMeetings.map { it.city }.distinct() }
    LaunchedEffect(cities) {
        if (selectedCity !in cities) selectedCity = "Tümü"
    }
    val upcoming = remember(activeMeetings, nowTick) {
        activeMeetings.flatMap { it.races }.sortedBy { secondsUntil(it.time, nowTick) }
    }
    val visibleMeetings = if (selectedCity == "Tümü") activeMeetings else activeMeetings.filter { it.city == selectedCity }
    val activeRaceCount = activeMeetings.sumOf { it.races.size }

    Column(Modifier.fillMaxSize()) {
        TopHeader(onRefresh)
        when {
            loading -> LoadingState()
            meetings.isEmpty() -> EmptyState(error, onRefresh)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { NextRaceHero(upcoming.firstOrNull(), nowTick, onRace) }
                if (upcoming.isNotEmpty()) {
                    if (upcoming.size > 1) {
                        item { SectionHeader("Sıradaki yarışlar", "Zamana göre en yakın") }
                        items(upcoming.drop(1).take(4)) { race -> UpcomingRaceCard(race, nowTick, onRace) }
                    }
                    item { CityFilter(cities, selectedCity) { selectedCity = it } }
                    item { SectionHeader("Kalan yarışlar", "$activeRaceCount koşu · ${activeMeetings.size} şehir") }
                    visibleMeetings.forEach { meeting ->
                        item { MeetingHeader(meeting) }
                        items(meeting.races) { race -> RaceCard(race, nowTick, onRace) }
                    }
                }
            }
        }
    }
}

private fun secondsUntil(time: String, nowMillis: Long = System.currentTimeMillis()): Long {
    val parts = time.trim().split(":")
    if (parts.size != 2) return Long.MAX_VALUE
    val h = parts[0].toIntOrNull() ?: return Long.MAX_VALUE
    val m = parts[1].toIntOrNull() ?: return Long.MAX_VALUE
    val tz = java.util.TimeZone.getTimeZone("Europe/Istanbul")
    val target = Calendar.getInstance(tz).apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, h)
        set(Calendar.MINUTE, m)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return (target.timeInMillis - nowMillis) / 1000L
}

private fun countdownText(seconds: Long): String {
    if (seconds <= 0L) return "00:00"
    val hours = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, mins, secs)
    else "%02d:%02d".format(mins, secs)
}


@Composable
private fun TopHeader(onRefresh: () -> Unit) {
    Surface(color = Bg) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Ink),
                contentAlignment = Alignment.Center
            ) { Text("HA", color = Gold, fontWeight = FontWeight.Black, fontSize = 16.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("HorsAI", fontWeight = FontWeight.Black, fontSize = 26.sp, color = Ink)
                Text("Türkiye yarış analizi", color = Muted, fontSize = 12.sp)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Yenile", tint = Ink) }
        }
    }
}

@Composable
private fun NextRaceHero(race: Race?, nowTick: Long, onRace: (Race) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = Ink),
        shape = RoundedCornerShape(28.dp)
    ) {
        if (race == null) {
            Column(Modifier.padding(22.dp)) {
                Text("Bugünkü canlı yarışlar tamamlandı", color = Color.White, fontWeight = FontWeight.Black, fontSize = 21.sp)
                Spacer(Modifier.height(5.dp))
                Text("Bugün için başlamamış koşu kalmadı.", color = Color.White.copy(.65f), fontSize = 12.sp)
            }
        } else {
            val secs = secondsUntil(race.time, nowTick)
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(Gold, RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 6.dp)) {
                        Text("${countdownText(secs)} KALDI", color = Ink, fontWeight = FontWeight.Black, fontSize = 10.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(race.time, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
                Spacer(Modifier.height(18.dp))
                Text("Sıradaki yarış", color = Color.White.copy(.58f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("${race.city} · ${race.number}. Koşu", color = Color.White, fontWeight = FontWeight.Black, fontSize = 27.sp)
                Spacer(Modifier.height(5.dp))
                Text(listOf(race.distance.takeIf { it.isNotBlank() }?.plus(" m"), race.surface).filterNotNull().joinToString(" · "), color = Color.White.copy(.68f), fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { onRace(race) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink),
                    shape = RoundedCornerShape(15.dp),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 12.dp)
                ) { Text("Analizi aç", fontWeight = FontWeight.Black) }
            }
        }
    }
}

@Composable
private fun UpcomingRaceCard(race: Race, nowTick: Long, onRace: (Race) -> Unit) {
    val picks = remember(race) { Predictor.picks(race) }
    val favorite = picks.firstOrNull()
    val secs = secondsUntil(race.time, nowTick)
    Card(
        Modifier.fillMaxWidth().clickable { onRace(race) },
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(18.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(70.dp)) {
                Text(race.time, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Ink)
                Text(countdownText(secs), color = Green, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            Box(Modifier.width(1.dp).height(44.dp).background(Border))
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text("${race.city} · ${race.number}. Koşu", fontWeight = FontWeight.Black, fontSize = 14.sp)
                Text(listOf(race.distance.takeIf { it.isNotBlank() }?.plus(" m"), race.surface).filterNotNull().joinToString(" · "), color = Muted, fontSize = 11.sp)
                if (favorite != null) Text("Favori: #${favorite.horse.no} ${favorite.horse.name}", color = Green, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontWeight = FontWeight.Black, fontSize = 19.sp, color = Ink, modifier = Modifier.weight(1f))
        Text(subtitle, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun CityFilter(cities: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text("Şehirler", fontWeight = FontWeight.Black, fontSize = 17.sp, color = Ink)
        Spacer(Modifier.height(9.dp))
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cities) { city ->
                FilterChip(
                    selected = city == selected,
                    onClick = { onSelect(city) },
                    label = { Text(city, fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Ink,
                        selectedLabelColor = Color.White,
                        containerColor = Surface,
                        labelColor = Ink
                    )
                )
            }
        }
    }
}

@Composable
private fun MeetingHeader(meeting: Meeting) {
    Column(Modifier.padding(top = 8.dp, bottom = 1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(meeting.city, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Ink)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.background(PaleGreen, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 4.dp)) {
                Text("${meeting.races.size} koşu", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (meeting.trackInfo.isNotBlank()) Text(meeting.trackInfo, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RaceCard(race: Race, nowTick: Long, onRace: (Race) -> Unit) {
    val picks = remember(race) { Predictor.picks(race) }
    val favorite = picks.firstOrNull()
    val surprise = picks.firstOrNull { it.label == "Sürpriz" }
    Card(
        Modifier.fillMaxWidth().clickable { onRace(race) },
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(20.dp),
        border = CardDefaults.outlinedCardBorder().copy(width = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(PaleGreen, RoundedCornerShape(9.dp)).padding(horizontal = 9.dp, vertical = 6.dp)) {
                    Text("${race.city} · ${race.number}. KOŞU", color = Green, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
                }
                Spacer(Modifier.width(9.dp))
                Text(race.time, color = Ink, fontWeight = FontWeight.Black, fontSize = 15.sp)
                Spacer(Modifier.width(7.dp))
                Text(countdownText(secondsUntil(race.time, nowTick)), color = Green, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(listOf(race.distance.takeIf { it.isNotBlank() }?.plus(" m"), race.surface).filterNotNull().joinToString(" · "), color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(race.title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (favorite != null) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Border)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("MODEL FAVORİSİ", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("#${favorite.horse.no} ${favorite.horse.name}", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                    ScoreBadge(favorite.score)
                }
                if (surprise != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("💣 Sürpriz  #${surprise.horse.no} ${surprise.horse.name}", color = Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ScoreBadge(score: Int) {
    Box(
        Modifier.size(48.dp).background(PaleGreen, CircleShape),
        contentAlignment = Alignment.Center
    ) { Text(score.toString(), color = Green, fontWeight = FontWeight.Black, fontSize = 15.sp) }
}

@Composable
fun RaceDetail(race: Race, onBack: () -> Unit) {
    val picks = remember(race) { Predictor.picks(race) }
    val surprise = picks.firstOrNull { it.label == "Sürpriz" } ?: picks.getOrNull(2)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 42.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Geri") }
                Column(Modifier.weight(1f)) {
                    Text("${race.number}. Koşu · ${race.time}", fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Text(listOf(race.distance.takeIf { it.isNotBlank() }?.plus(" m"), race.surface).filterNotNull().joinToString(" · "), color = Muted, fontSize = 12.sp)
                }
            }
        }
        if (picks.isNotEmpty()) {
            item { ResultHero(picks.first(), surprise) }
            item { SectionTitle("Olası sıralama") }
            items(picks) { pick -> HorseCard(pick) }
            item {
                SectionTitle("Kuponlar")
                CouponRow("Dar", picks.take(min(2, picks.size)).joinToString(" – ") { it.horse.no.toString() }, "Yüksek risk")
                Spacer(Modifier.height(8.dp))
                CouponRow("Dengeli", picks.take(min(3, picks.size)).joinToString(" – ") { it.horse.no.toString() }, "Ana senaryo")
                Spacer(Modifier.height(8.dp))
                CouponRow("Geniş", picks.take(min(5, picks.size)).joinToString(" – ") { it.horse.no.toString() }, "Sürpriz koruması")
            }
        }
        item {
            Text("Model; TJK programındaki AGF, HP, kilo, ganyan, son form ve pist/mesafe verilerini birlikte puanlar. Tahmin garanti değildir.", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ResultHero(favorite: Pick, surprise: Pick?) {
    Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("OLASI KAZANAN", color = Gold, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("#${favorite.horse.no} ${favorite.horse.name}", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
                    Text("Model güveni ${favorite.score}/100", color = Color.White.copy(.66f), fontSize = 12.sp)
                }
                Box(Modifier.size(58.dp).background(Color.White.copy(.10f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Stars, null, tint = Gold, modifier = Modifier.size(29.dp))
                }
            }
            if (favorite.reasons.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(favorite.reasons.joinToString(" · "), color = Color.White.copy(.78f), fontSize = 12.sp)
            }
            if (surprise != null) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Color.White.copy(.12f))
                Spacer(Modifier.height(12.dp))
                Text("💣 Sürpriz  #${surprise.horse.no} ${surprise.horse.name}", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HorseCard(p: Pick) {
    val labelBg = when (p.label) {
        "Favori" -> PaleGreen
        "Sürpriz" -> PaleRed
        else -> PaleGold
    }
    val labelFg = when (p.label) {
        "Favori" -> Green
        "Sürpriz" -> Red
        else -> Gold
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(18.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(42.dp).background(Ink, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(p.horse.no.toString(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.horse.name, fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(p.score.toString(), color = Green, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    listOfNotNull(
                        p.horse.jockey.takeIf { it.isNotBlank() },
                        p.horse.weight?.let { "${it} kg" },
                        p.horse.hp?.let { "HP $it" },
                        p.horse.agf?.let { "AGF %$it" }
                    ).joinToString(" · "),
                    color = Muted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(7.dp))
                Box(Modifier.background(labelBg, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 5.dp)) {
                    Text(p.label, color = labelFg, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
                if (p.reasons.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    Text(p.reasons.joinToString(" · "), color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Ink, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun CouponRow(title: String, numbers: String, subtitle: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp), border = CardDefaults.outlinedCardBorder()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Black, fontSize = 14.sp)
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
            Text(numbers, color = Green, fontWeight = FontWeight.Black, fontSize = 17.sp)
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Green)
            Spacer(Modifier.height(14.dp))
            Text("TJK programı hazırlanıyor…", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun EmptyState(error: String?, onRefresh: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(26.dp), border = CardDefaults.outlinedCardBorder()) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(58.dp).background(PaleRed, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CloudOff, null, tint = Red)
                }
                Spacer(Modifier.height(16.dp))
                Text("Program yüklenemedi", fontWeight = FontWeight.Black, fontSize = 19.sp)
                Spacer(Modifier.height(6.dp))
                Text(error ?: "TJK bağlantısı geçici olarak yanıt vermedi.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = Green), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(7.dp))
                    Text("Yeniden dene")
                }
            }
        }
    }
}
