package com.yarisradar.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

private val Ink = Color(0xFF17231B)
private val Green = Color(0xFF1F7A4C)
private val Mint = Color(0xFFE8F4EC)
private val Cream = Color(0xFFF7F7F3)
private val Gold = Color(0xFFF2B84B)
private val SoftRed = Color(0xFFFFECE8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { YarisRadarApp() }
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
    val horses: List<Horse>
)

data class Meeting(val city: String, val date: String, val track: String, val races: List<Race>)

data class Pick(val horse: Horse, val score: Int, val label: String, val reasons: List<String>)

object TjkRepository {
    private val cities = listOf("İstanbul", "Ankara", "İzmir", "Bursa", "Kocaeli", "Adana", "Şanlıurfa", "Elazığ", "Diyarbakır", "Antalya")

    suspend fun loadToday(): List<Meeting> = withContext(Dispatchers.IO) {
        val date = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())
        cities.mapNotNull { city ->
            runCatching { loadCity(date, city) }.getOrNull()?.takeIf { it.races.isNotEmpty() }
        }
    }

    private fun loadCity(date: String, city: String): Meeting {
        val encodedDate = URLEncoder.encode(date, "UTF-8")
        val encodedCity = URLEncoder.encode(city, "UTF-8")
        val url = "https://www.tjk.org/TR/yarissever/Info/Sehir/GunlukYarisProgrami?QueryParameter_Tarih=$encodedDate&SehirAdi=$encodedCity"
        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Android) YarisRadar/1.0")
            .timeout(15000)
            .get()

        val races = mutableListOf<Race>()
        val tables = doc.select("table")
        for (table in tables) {
            val headers = table.select("th").joinToString(" ") { it.text() }
            if (!headers.contains("At İsmi", ignoreCase = true) && !headers.contains("At İsmi", ignoreCase = false)) continue

            val horseRows = table.select("tr").drop(1).mapNotNull { row ->
                val cells = row.select("td")
                if (cells.size < 10) return@mapNotNull null
                val n = cells.getOrNull(1)?.text()?.trim()?.toIntOrNull() ?: return@mapNotNull null
                val name = cells.getOrNull(2)?.text()?.replace(Regex("\\s+"), " ")?.trim()?.substringBefore(" (") ?: return@mapNotNull null
                val weight = cells.getOrNull(4)?.text()?.replace(",", ".")?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
                val jockey = cells.getOrNull(5)?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                val start = cells.getOrNull(8)?.text()?.filter { it.isDigit() }?.toIntOrNull()
                val hp = cells.getOrNull(9)?.text()?.filter { it.isDigit() }?.toIntOrNull()
                val last6 = cells.getOrNull(10)?.text()?.trim().orEmpty()
                val best = cells.getOrNull(13)?.text()?.trim().orEmpty()
                val odds = cells.getOrNull(14)?.text()?.replace(",", ".")?.toDoubleOrNull()
                val agf = Regex("%(\\d+)").find(cells.getOrNull(15)?.text().orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
                Horse(n, name, weight, jockey, hp, last6, best, odds, agf, start)
            }
            if (horseRows.isEmpty()) continue

            var cursor = table.previousElementSibling()
            var title = ""
            var time = ""
            repeat(12) {
                if (cursor == null) return@repeat
                val text = cursor!!.text().trim()
                if (title.isBlank() && (text.contains("Çim") || text.contains("Kum") || text.contains("Sentetik"))) title = text
                val m = Regex("(\\d+)\\. Koşu\\s+(\\d{1,2}\\.\\d{2})").find(text)
                if (m != null) time = m.groupValues[2]
                cursor = cursor!!.previousElementSibling()
            }
            val raceNo = races.size + 1
            val surface = when {
                title.contains("Sentetik", true) -> "Sentetik"
                title.contains("Çim", true) -> "Çim"
                title.contains("Kum", true) -> "Kum"
                else -> ""
            }
            val distance = Regex("(\\d{3,4})\\s+(Çim|Kum|Sentetik)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.getOrNull(1).orEmpty()
            races += Race(raceNo, time.ifBlank { "--:--" }, title.ifBlank { "$raceNo. Koşu" }, distance, surface, horseRows)
        }
        return Meeting(city, date, city, races)
    }
}

object Predictor {
    fun picks(race: Race): List<Pick> {
        if (race.horses.isEmpty()) return emptyList()
        val maxHp = race.horses.mapNotNull { it.hp }.maxOrNull()?.coerceAtLeast(1) ?: 1
        val minWeight = race.horses.mapNotNull { it.weight }.minOrNull() ?: 0.0
        val minOdds = race.horses.mapNotNull { it.odds }.minOrNull()?.coerceAtLeast(.1) ?: 1.0
        val raw = race.horses.map { h ->
            var score = 45.0
            val reasons = mutableListOf<String>()
            h.agf?.let { score += it * .72; if (it >= 15) reasons += "AGF desteği %$it" }
            h.hp?.let { hp -> score += (hp.toDouble()/maxHp)*15; if (hp >= maxHp-5) reasons += "yüksek handikap puanı" }
            h.weight?.let { w ->
                val advantage = max(0.0, 5.0 - (w-minWeight))
                score += advantage * 1.4
                if (w <= minWeight + 1.0) reasons += "kilo avantajı"
            }
            h.odds?.let { o ->
                score += min(12.0, (minOdds/o)*12)
                if (o <= minOdds*1.6) reasons += "ganyan piyasası güçlü"
            }
            val form = h.last6.takeLast(4)
            val wins = form.count { it == '1' }
            val places = form.count { it in '1'..'3' }
            score += wins*4 + places*1.5
            if (wins > 0) reasons += "yakın formunda galibiyet"
            if (h.best.isNotBlank()) reasons += "pist/mesafe derecesi mevcut"
            h to (score to reasons)
        }
        val maxRaw = raw.maxOf { it.second.first }
        val minRaw = raw.minOf { it.second.first }
        return raw.sortedByDescending { it.second.first }.mapIndexed { idx, item ->
            val normalized = if (maxRaw == minRaw) 70 else (52 + ((item.second.first-minRaw)/(maxRaw-minRaw))*39).toInt()
            val label = when (idx) { 0 -> "Favori"; 1 -> "Ciddi rakip"; else -> if ((item.first.agf ?: 0) <= 8 && normalized >= 60) "Sürpriz" else "Alternatif" }
            Pick(item.first, normalized, label, item.second.second.distinct().take(3))
        }
    }
}

@Composable
fun YarisRadarApp() {
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Cream, surface = Color.White, onSurface = Ink)) {
        var meetings by remember { mutableStateOf<List<Meeting>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var selectedRace by remember { mutableStateOf<Race?>(null) }
        val scope = rememberCoroutineScope()

        fun refresh() { scope.launch {
            loading = true; error = null
            meetings = runCatching { TjkRepository.loadToday() }.onFailure { error = it.message }.getOrDefault(emptyList())
            loading = false
        }}
        LaunchedEffect(Unit) { refresh() }

        Surface(Modifier.fillMaxSize(), color = Cream) {
            if (selectedRace != null) RaceDetail(selectedRace!!, onBack = { selectedRace = null })
            else Home(meetings, loading, error, onRefresh = { refresh() }, onRace = { selectedRace = it })
        }
    }
}

@Composable
fun Home(meetings: List<Meeting>, loading: Boolean, error: String?, onRefresh: () -> Unit, onRace: (Race)->Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Green, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text("YR", color=Color.White, fontWeight=FontWeight.Black) }
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text("Yarış Radar", fontWeight=FontWeight.Black, fontSize=25.sp); Text("Türkiye · canlı TJK verisi", color=Color.Gray, fontSize=13.sp) }
            IconButton(onClick=onRefresh) { Icon(Icons.Default.Refresh, "Yenile") }
        }
        Spacer(Modifier.height(18.dp))
        Card(colors=CardDefaults.cardColors(containerColor=Ink), shape=RoundedCornerShape(22.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Bugünün yarışları", color=Color.White, fontSize=21.sp, fontWeight=FontWeight.Bold); Text("Favori · sürpriz · gerekçe · kupon", color=Color.White.copy(.7f)) }
                Icon(Icons.Default.Insights, null, tint=Gold, modifier=Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) { CircularProgressIndicator() }
            meetings.isEmpty() -> EmptyState(error, onRefresh)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding=PaddingValues(bottom=32.dp)) {
                meetings.forEach { meeting ->
                    item { Text(meeting.city.uppercase(), fontWeight=FontWeight.ExtraBold, color=Green, fontSize=13.sp) }
                    items(meeting.races) { race -> RaceCard(race, onRace) }
                }
            }
        }
    }
}

@Composable
fun EmptyState(error: String?, onRefresh:()->Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
        Icon(Icons.Default.CloudOff, null, modifier=Modifier.size(52.dp), tint=Color.Gray)
        Spacer(Modifier.height(12.dp)); Text("Canlı program alınamadı", fontWeight=FontWeight.Bold)
        Text(error ?: "TJK bağlantısını kontrol edip tekrar deneyin.", color=Color.Gray)
        Spacer(Modifier.height(16.dp)); Button(onClick=onRefresh) { Text("Tekrar dene") }
    }
}

@Composable
fun RaceCard(race: Race, onRace:(Race)->Unit) {
    val p = Predictor.picks(race)
    Card(Modifier.fillMaxWidth().clickable { onRace(race) }, shape=RoundedCornerShape(20.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.background(Mint, RoundedCornerShape(10.dp)).padding(horizontal=10.dp, vertical=7.dp)) { Text("${race.number}. KOŞU", color=Green, fontWeight=FontWeight.Bold, fontSize=12.sp) }
                Spacer(Modifier.width(9.dp)); Text(race.time, fontWeight=FontWeight.Bold)
                Spacer(Modifier.weight(1f)); Text(listOf(race.distance, race.surface).filter{it.isNotBlank()}.joinToString(" m "), color=Color.Gray, fontSize=12.sp)
            }
            Spacer(Modifier.height(10.dp)); Text(race.title, maxLines=2, fontWeight=FontWeight.SemiBold, fontSize=14.sp)
            if (p.isNotEmpty()) {
                Spacer(Modifier.height(13.dp)); Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    PickChip("🥇 ${p[0].horse.no} ${p[0].horse.name}", Mint, Green)
                    p.firstOrNull { it.label=="Sürpriz" }?.let { PickChip("💣 ${it.horse.no}", SoftRed, Color(0xFFB3422E)) }
                }
            }
        }
    }
}

@Composable fun PickChip(text:String, bg:Color, fg:Color) { Box(Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal=10.dp, vertical=6.dp)) { Text(text, color=fg, fontWeight=FontWeight.Bold, fontSize=12.sp) } }

@Composable
fun RaceDetail(race: Race, onBack:()->Unit) {
    val context = LocalContext.current
    val picks = remember(race) { Predictor.picks(race) }
    val surprise = picks.firstOrNull { it.label=="Sürpriz" } ?: picks.getOrNull(2)
    val fieldNotes = remember { mutableStateListOf<String>() }
    var note by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp), contentPadding=PaddingValues(bottom=40.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(12.dp)); Row(verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack, "Geri") }
                Column(Modifier.weight(1f)) { Text("${race.number}. Koşu · ${race.time}", fontSize=23.sp, fontWeight=FontWeight.Black); Text(race.title, fontSize=13.sp, color=Color.Gray) }
            }
        }
        if (picks.isNotEmpty()) item {
            Card(colors=CardDefaults.cardColors(containerColor=Ink), shape=RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(19.dp)) {
                    Text("OLASI SONUÇ", color=Gold, fontWeight=FontWeight.Bold, fontSize=12.sp)
                    Spacer(Modifier.height(7.dp)); Text("#${picks[0].horse.no} ${picks[0].horse.name}", color=Color.White, fontSize=26.sp, fontWeight=FontWeight.Black)
                    Text("Model skoru ${picks[0].score}/100", color=Color.White.copy(.7f))
                    if (surprise != null) { Spacer(Modifier.height(13.dp)); HorizontalDivider(color=Color.White.copy(.15f)); Spacer(Modifier.height(13.dp)); Text("💣 Sürpriz: #${surprise.horse.no} ${surprise.horse.name}", color=Color.White, fontWeight=FontWeight.Bold) }
                }
            }
        }
        item { SectionTitle("Atlar ve analiz") }
        items(picks) { pick -> HorseCard(pick) }
        item {
            SectionTitle("Kupon önerileri")
            val safe = picks.take(min(4,picks.size)).joinToString(" – ") { it.horse.no.toString() }
            val balanced = picks.take(min(3,picks.size)).joinToString(" – ") { it.horse.no.toString() }
            val narrow = picks.take(min(2,picks.size)).joinToString(" – ") { it.horse.no.toString() }
            CouponCard("Dar", narrow, "Maliyet düşük · risk yüksek")
            Spacer(Modifier.height(8.dp)); CouponCard("Dengeli", balanced, "Ana adaylar + yakın rakip")
            Spacer(Modifier.height(8.dp)); CouponCard("Güvenli", safe, "Daha geniş kapsama")
        }
        item {
            SectionTitle("Yorumcu / internet taraması")
            Text("Uygulama resmi TJK verisini canlı okur. Yorumcu siteleri için kaynakları tek dokunuşla açar; kaynakların kullanım şartlarını ihlal edecek toplu scraping yapmaz.", color=Color.Gray, fontSize=13.sp)
            Spacer(Modifier.height(8.dp))
            val q = "${race.number}. koşu at yarışı tahmini bugün TJK"
            OutlinedButton(onClick={ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q,"UTF-8")))) }) { Icon(Icons.Default.Search,null); Spacer(Modifier.width(6.dp)); Text("Web yorumlarını ara") }
        }
        item {
            SectionTitle("Saha görüşü")
            OutlinedTextField(value=note, onValueChange={note=it}, modifier=Modifier.fillMaxWidth(), label={Text("Padok / saha notu")}, placeholder={Text("Örn. 6 çok diri görünüyor, terleme yok")})
            Spacer(Modifier.height(8.dp)); Button(onClick={ if(note.isNotBlank()){ fieldNotes.add(note.trim()); note="" } }) { Text("Notu ekle") }
            fieldNotes.forEach { Text("• $it", modifier=Modifier.padding(top=8.dp)) }
        }
        item { Text("Tahminler olasılıksaldır; bahis sonucu garanti edilmez. 18+", color=Color.Gray, fontSize=11.sp) }
    }
}

@Composable fun SectionTitle(t:String){ Text(t, fontWeight=FontWeight.Black, fontSize=18.sp, modifier=Modifier.padding(top=5.dp)) }

@Composable
fun HorseCard(p: Pick) {
    Card(shape=RoundedCornerShape(18.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment=Alignment.Top) {
            Box(Modifier.size(42.dp).background(if(p.label=="Favori") Mint else Cream, RoundedCornerShape(13.dp)), contentAlignment=Alignment.Center) { Text(p.horse.no.toString(), fontWeight=FontWeight.Black, fontSize=18.sp) }
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
                Row { Text(p.horse.name, fontWeight=FontWeight.ExtraBold, fontSize=16.sp); Spacer(Modifier.weight(1f)); Text("${p.score}", color=Green, fontWeight=FontWeight.Black) }
                Text("${p.horse.jockey}  ·  ${p.horse.weight?.let{"${it} kg"} ?: "kilo —"}  ·  HP ${p.horse.hp ?: "—"}", fontSize=12.sp, color=Color.Gray)
                Spacer(Modifier.height(5.dp)); PickChip(p.label, if(p.label=="Sürpriz") SoftRed else Mint, if(p.label=="Sürpriz") Color(0xFFB3422E) else Green)
                if(p.reasons.isNotEmpty()) { Spacer(Modifier.height(7.dp)); Text(p.reasons.joinToString(" · "), fontSize=12.sp, color=Ink.copy(.7f)) }
            }
        }
    }
}

@Composable fun CouponCard(name:String, nums:String, sub:String) {
    Card(shape=RoundedCornerShape(16.dp), colors=CardDefaults.cardColors(containerColor=Color.White)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)){ Text(name, fontWeight=FontWeight.Bold); Text(sub, color=Color.Gray, fontSize=12.sp) }; Text(nums, fontWeight=FontWeight.Black, color=Green, fontSize=18.sp) } }
}
