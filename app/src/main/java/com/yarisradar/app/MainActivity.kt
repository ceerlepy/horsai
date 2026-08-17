package com.yarisradar.app

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.channels.Channel
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
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
private val LoadingBlue = Color(0xFF1976D2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TwoHorseApp() }
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
    val start: Int? = null,
    val videoUrl: String? = null,
    val detailUrl: String? = null
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
    val races: List<Race>,
    val sixliStarts: List<Int> = emptyList()
)

data class ExpertHorseSignal(
    val support: Int = 0,
    val totalSources: Int = 0,
    val sources: List<String> = emptyList(),
    val strongSupport: Int = 0,
    val favoriteBankoSupport: Int = 0,
    val surpriseSupport: Int = 0,
    val negativeSupport: Int = 0,
    val sahaScore: Int = 0,
    val sahaNotes: List<String> = emptyList()
)

data class RaceExpertSignal(
    // sourceCount = bu koşu için gerçekten parse edilebilen kaynak sayısı.
    // reachableSourceCount = sayfasına erişilebilen kaynak sayısı; UI bunu "aktif" diye gösterir.
    val sourceCount: Int = 0,
    val configuredSourceCount: Int = 0,
    val reachableSourceCount: Int = 0,
    val freshSourceCount: Int = 0,
    val cachedSourceCount: Int = 0,
    val activeSources: List<String> = emptyList(),
    val reachableSources: List<String> = emptyList(),
    val unreachableSources: List<String> = emptyList(),
    val unusableSources: List<String> = emptyList(),
    val byHorse: Map<Int, ExpertHorseSignal> = emptyMap()
)

data class Pick(
    val horse: Horse,
    val score: Int,
    val label: String,
    val reasons: List<String>,
    val agfRank: Int? = null,
    val hpRank: Int? = null,
    val expertRank: Int? = null,
    val expertSupport: Int = 0,
    val expertTotal: Int = 0,
    val expertSources: List<String> = emptyList(),
    val expertStrong: Int = 0,
    val expertFavoriteBanko: Int = 0,
    val expertSurprise: Int = 0,
    val expertNegative: Int = 0,
    val sahaScore: Int = 0,
    val sahaNotes: List<String> = emptyList(),
    val marketLabel: String = "Belirsiz",
    val formLabel: String = "→ Dengeli"
)

private fun raceKey(race: Race): String = "${race.city}|${race.number}"

data class RaceVideo(val label: String, val url: String)

object VideoRepository {
    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun loadLast3(startUrl: String): List<RaceVideo> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(10000) {
            fun fetch(url: String): Document? = runCatching {
                val req = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36")
                    .header("Accept-Language", "tr-TR,tr;q=0.9")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) null else Jsoup.parse(r.body?.string().orEmpty(), r.request.url.toString())
                }
            }.getOrNull()

            fun anchorLabel(a: org.jsoup.nodes.Element): String {
                val own = a.text().replace(Regex("\\s+"), " ").trim()
                if (own.contains("Koşu", true) && Regex("\\b\\d{2}[./]\\d{2}[./]\\d{4}\\b").containsMatchIn(own)) return own
                val row = a.closest("tr")?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                if (row.contains("Koşu", true) && Regex("\\b\\d{2}[./]\\d{2}[./]\\d{4}\\b").containsMatchIn(row)) return row
                val parent = a.parent()?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                return parent
            }

            fun archiveLinks(doc: Document): List<RaceVideo> = doc.select("a[href]").mapNotNull { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                if (!href.contains("YarisVideoAt", true) || !href.contains("KosuKodu", true)) return@mapNotNull null
                val label = anchorLabel(a)
                val looksLikeRace = label.contains("Koşu", ignoreCase = true) &&
                    Regex("\\b\\d{2}[./]\\d{2}[./]\\d{4}\\b").containsMatchIn(label)
                if (!looksLikeRace) return@mapNotNull null
                RaceVideo(label, href)
            }.distinctBy { it.url }

            val firstDoc = fetch(startUrl) ?: return@withTimeoutOrNull emptyList()
            val archiveUrl = if (startUrl.contains("YarisVideoAt", true)) {
                startUrl
            } else {
                firstDoc.select("a[href*='YarisVideoAt'][href*='AtKodu'][href*='KosuKodu']")
                    .map { it.attr("abs:href").ifBlank { it.attr("href") } }
                    .firstOrNull { it.startsWith("http") }
            } ?: return@withTimeoutOrNull emptyList()

            val archiveDoc = if (archiveUrl == startUrl) firstDoc else fetch(archiveUrl) ?: return@withTimeoutOrNull emptyList()
            val currentKosu = Regex("[?&]KosuKodu=([^&]+)").find(archiveUrl)?.groupValues?.getOrNull(1)
            fun videoDate(v: RaceVideo): Long {
                val raw = Regex("\\b(\\d{2})[./](\\d{2})[./](\\d{4})\\b").find(v.label) ?: return 0L
                val (dd, mm, yyyy) = raw.destructured
                return runCatching {
                    java.util.GregorianCalendar(yyyy.toInt(), mm.toInt() - 1, dd.toInt()).timeInMillis
                }.getOrDefault(0L)
            }

            archiveLinks(archiveDoc)
                // TJK bazı geçmiş satırlarında "Koşmaz" kaydı da video bağlantısı üretebiliyor; gerçek yarış saymıyoruz.
                .filterNot { it.label.contains("Koşmaz", ignoreCase = true) }
                .filterNot { v -> currentKosu != null && v.url.contains("KosuKodu=$currentKosu") }
                .sortedByDescending(::videoDate)
                .take(3)
        } ?: emptyList()
    }

}

data class HistorySnapshot(val race: Race, val picks: List<Pick>)

object HistoryStore {
    private const val PREF = "two_horse_history_v1"
    private const val KEY_DATE = "date"
    private const val KEY_DATA = "snapshots"

    private fun today(): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("Europe/Istanbul")
    }.format(Date())

    fun capture(context: Context, meetings: List<Meeting>, experts: Map<String, RaceExpertSignal>) {
        if (meetings.isEmpty()) return
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val day = today()
        val root = if (prefs.getString(KEY_DATE, null) == day) {
            runCatching { JSONObject(prefs.getString(KEY_DATA, "{}") ?: "{}") }.getOrDefault(JSONObject())
        } else JSONObject()
        val now = System.currentTimeMillis()
        meetings.flatMap { it.races }.forEach { race ->
            val key = raceKey(race)
            // Yarış başlayana kadar snapshot'ı güncel tut (uzman kaynakları geldikçe puan/sıra değişebilir).
            // Yarış saati geçince artık dokunma: geçmişte görülen analiz yarış öncesi son halidir.
            if (secondsUntil(race.time, now) > 0L) {
                val picks = Predictor.picks(race, experts[key])
                if (picks.isNotEmpty()) root.put(key, snapshotToJson(HistorySnapshot(race, picks)))
            }
        }
        prefs.edit().putString(KEY_DATE, day).putString(KEY_DATA, root.toString()).apply()
    }

    fun load(context: Context): List<HistorySnapshot> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_DATE, null) != today()) {
            prefs.edit().clear().apply()
            return emptyList()
        }
        val root = runCatching { JSONObject(prefs.getString(KEY_DATA, "{}") ?: "{}") }.getOrDefault(JSONObject())
        return root.keys().asSequence().mapNotNull { key -> runCatching { snapshotFromJson(root.getJSONObject(key)) }.getOrNull() }.toList()
    }

    private fun horseToJson(h: Horse) = JSONObject().apply {
        put("no",h.no); put("name",h.name); h.weight?.let{put("weight",it)}; put("jockey",h.jockey)
        h.hp?.let{put("hp",it)}; put("last6",h.last6); put("best",h.best); h.odds?.let{put("odds",it)}
        h.agf?.let{put("agf",it)}; h.start?.let{put("start",it)}; put("videoUrl",h.videoUrl?:""); put("detailUrl",h.detailUrl?:"")
    }
    private fun horseFromJson(o: JSONObject)=Horse(o.getInt("no"),o.getString("name"),o.optDoubleN("weight"),o.optString("jockey"),o.optIntN("hp"),o.optString("last6"),o.optString("best"),o.optDoubleN("odds"),o.optIntN("agf"),o.optIntN("start"),o.optString("videoUrl").takeIf{it.isNotBlank()},o.optString("detailUrl").takeIf{it.isNotBlank()})
    private fun raceToJson(r: Race)=JSONObject().apply { put("number",r.number);put("time",r.time);put("title",r.title);put("distance",r.distance);put("surface",r.surface);put("city",r.city);put("horses",JSONArray().apply{r.horses.forEach{put(horseToJson(it))}}) }
    private fun raceFromJson(o:JSONObject):Race { val a=o.getJSONArray("horses"); val hs=(0 until a.length()).map{horseFromJson(a.getJSONObject(it))}; return Race(o.getInt("number"),o.getString("time"),o.optString("title"),o.optString("distance"),o.optString("surface"),hs,o.optString("city")) }
    private fun pickToJson(p:Pick)=JSONObject().apply {
        put("horse",horseToJson(p.horse));put("score",p.score);put("label",p.label);put("reasons",JSONArray(p.reasons)); p.agfRank?.let{put("agfRank",it)};p.hpRank?.let{put("hpRank",it)};p.expertRank?.let{put("expertRank",it)}
        put("expertSupport",p.expertSupport);put("expertTotal",p.expertTotal);put("expertSources",JSONArray(p.expertSources));put("expertStrong",p.expertStrong);put("expertFavoriteBanko",p.expertFavoriteBanko);put("expertSurprise",p.expertSurprise);put("expertNegative",p.expertNegative);put("sahaScore",p.sahaScore);put("sahaNotes",JSONArray(p.sahaNotes));put("marketLabel",p.marketLabel);put("formLabel",p.formLabel)
    }
    private fun strings(a:JSONArray)= (0 until a.length()).map{a.optString(it)}
    private fun pickFromJson(o:JSONObject)=Pick(horseFromJson(o.getJSONObject("horse")),o.getInt("score"),o.getString("label"),strings(o.getJSONArray("reasons")),o.optIntN("agfRank"),o.optIntN("hpRank"),o.optIntN("expertRank"),o.optInt("expertSupport"),o.optInt("expertTotal"),strings(o.optJSONArray("expertSources")?:JSONArray()),o.optInt("expertStrong"),o.optInt("expertFavoriteBanko"),o.optInt("expertSurprise"),o.optInt("expertNegative"),o.optInt("sahaScore"),strings(o.optJSONArray("sahaNotes")?:JSONArray()),o.optString("marketLabel","Belirsiz"),o.optString("formLabel","→ Dengeli"))
    private fun snapshotToJson(s:HistorySnapshot)=JSONObject().apply{put("race",raceToJson(s.race));put("picks",JSONArray().apply{s.picks.forEach{put(pickToJson(it))}})}
    private fun snapshotFromJson(o:JSONObject):HistorySnapshot { val a=o.getJSONArray("picks"); return HistorySnapshot(raceFromJson(o.getJSONObject("race")),(0 until a.length()).map{pickFromJson(a.getJSONObject(it))}) }
    private fun JSONObject.optIntN(k:String):Int?=if(has(k)&&!isNull(k)) getInt(k) else null
    private fun JSONObject.optDoubleN(k:String):Double?=if(has(k)&&!isNull(k)) getDouble(k) else null
}

object MeetingCache {
    private const val PREF = "two_horse_cache"
    private const val KEY = "today_meetings"
    private const val KEY_DATE = "cache_date"

    fun load(context: Context): List<Meeting> {
        return runCatching {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val today = turkishDate()
            if (prefs.getString(KEY_DATE, null) != today) return emptyList()
            val raw = prefs.getString(KEY, null).orEmpty()
            if (raw.isBlank()) return emptyList()
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val racesArr = m.getJSONArray("races")
                    val races = buildList {
                        for (r in 0 until racesArr.length()) {
                            val ro = racesArr.getJSONObject(r)
                            val horsesArr = ro.getJSONArray("horses")
                            val horses = buildList {
                                for (h in 0 until horsesArr.length()) {
                                    val ho = horsesArr.getJSONObject(h)
                                    add(Horse(
                                        no = ho.getInt("no"),
                                        name = ho.getString("name"),
                                        weight = ho.optDoubleOrNull("weight"),
                                        jockey = ho.optString("jockey"),
                                        hp = ho.optIntOrNull("hp"),
                                        last6 = ho.optString("last6"),
                                        best = ho.optString("best"),
                                        odds = ho.optDoubleOrNull("odds"),
                                        agf = ho.optIntOrNull("agf"),
                                        start = ho.optIntOrNull("start"),
                                        videoUrl = ho.optString("videoUrl").takeIf { it.isNotBlank() },
                                        detailUrl = ho.optString("detailUrl").takeIf { it.isNotBlank() }
                                    ))
                                }
                            }
                            add(Race(
                                number = ro.getInt("number"),
                                time = ro.getString("time"),
                                title = ro.getString("title"),
                                distance = ro.optString("distance"),
                                surface = ro.optString("surface"),
                                horses = horses,
                                city = ro.optString("city")
                            ))
                        }
                    }
                    add(Meeting(m.getString("city"), m.getString("date"), m.optString("trackInfo"), races, (m.optJSONArray("sixliStarts") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optInt(it) }.filter { it > 0 } }))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, meetings: List<Meeting>) {
        runCatching {
            val arr = JSONArray()
            meetings.forEach { m ->
                val races = JSONArray()
                m.races.forEach { r ->
                    val horses = JSONArray()
                    r.horses.forEach { h ->
                        horses.put(JSONObject().apply {
                            put("no", h.no); put("name", h.name)
                            h.weight?.let { put("weight", it) }; put("jockey", h.jockey)
                            h.hp?.let { put("hp", it) }; put("last6", h.last6); put("best", h.best)
                            h.odds?.let { put("odds", it) }; h.agf?.let { put("agf", it) }
                            h.start?.let { put("start", it) }; put("videoUrl", h.videoUrl ?: ""); put("detailUrl", h.detailUrl ?: "")
                        })
                    }
                    races.put(JSONObject().apply {
                        put("number", r.number); put("time", r.time); put("title", r.title)
                        put("distance", r.distance); put("surface", r.surface); put("city", r.city)
                        put("horses", horses)
                    })
                }
                arr.put(JSONObject().apply {
                    put("city", m.city); put("date", m.date); put("trackInfo", m.trackInfo); put("sixliStarts", JSONArray(m.sixliStarts)); put("races", races)
                })
            }
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_DATE, turkishDate()).putString(KEY, arr.toString()).apply()
        }
    }

    private fun turkishDate(): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("Europe/Istanbul")
    }.format(Date())

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
}

object ExpertRepository {
    private data class Source(
        val name: String,
        val strongOnly: Boolean = false,
        val candidates: (String, String) -> List<String>,
        val discoveryPages: (String, String) -> List<String>
    )
    private data class SourceDoc(val source: Source, val text: String, val fresh: Boolean)

    private const val PREF = "two_horse_expert_cache_v2"
    private val months = listOf("ocak", "subat", "mart", "nisan", "mayis", "haziran", "temmuz", "agustos", "eylul", "ekim", "kasim", "aralik")
    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val sources = listOf(
        Source(
            name = "HorseTurk",
            candidates = { city, date ->
                val (d, m, y) = dateParts(date)
                listOf(
                    "https://www.horseturk.com/at-yarisi-tahminleri-${slug(city)}-$d-${months[m-1]}-$y/",
                    "https://www.horseturk.com/?s=${urlEncode("$city $d ${months[m-1]} $y")}" 
                )
            },
            discoveryPages = { _, _ -> listOf("https://www.horseturk.com/") }
        ),
        Source(
            name = "Banko Tahminler",
            candidates = { city, date ->
                val (d, m, y) = dateParts(date)
                listOf(
                    "https://www.bankotahminler.com/bulten/",
                    "https://www.bankotahminler.com/ai-tahmin/$d-${months[m-1]}-$y-${slug(city)}/",
                    "https://www.bankotahminler.com/ai-tahmin/$d-${months[m-1]}-$y/"
                )
            },
            discoveryPages = { _, _ -> listOf("https://www.bankotahminler.com/ai-tahmin/") }
        ),
        Source(
            name = "Yıldızlı Bülten",
            candidates = { city, date ->
                val (d, m, y) = dateParts(date)
                val iso = "$y-${m.toString().padStart(2,'0')}-${d.toString().padStart(2,'0')}"
                listOf(
                    "https://www.ybulten.com.tr/bulten/$iso/${urlEncode(city)}",
                    "https://www.ybulten.com.tr/?s=${urlEncode("$city $d ${months[m-1]} $y")}"
                )
            },
            discoveryPages = { _, _ -> listOf("https://www.ybulten.com.tr/") }
        ),
        Source(
            name = "Liderform",
            candidates = { city, date ->
                val (d, m, y) = dateParts(date)
                listOf(
                    "https://liderform.com.tr/program/$y-${m.toString().padStart(2,'0')}-${d.toString().padStart(2,'0')}/${slug(city)}",
                    "https://liderform.com.tr/haberler/analizler"
                )
            },
            discoveryPages = { _, _ -> listOf("https://liderform.com.tr/haberler/analizler", "https://liderform.com.tr/") }
        ),
        Source(
            name = "Yarış Dergisi",
            candidates = { city, date ->
                val (d, m, y) = dateParts(date)
                listOf(
                    "https://www.yarisdergisi.com/?s=${urlEncode("$city $d ${months[m-1]} $y")}",
                    "https://www.yarisdergisi.com/?s=${urlEncode("$city tahmin")}" 
                )
            },
            discoveryPages = { _, _ -> listOf("https://www.yarisdergisi.com/") }
        ),
        Source(
            name = "Ganyan Canavarı",
            candidates = { city, date ->
                val (d, m, y) = dateParts(date)
                val cityId = ganyanCityId(city)
                buildList {
                    if (cityId != null) add("https://www.ganyancanavari.com.tr/site/$y/${m.toString().padStart(2,'0')}/${d.toString().padStart(2,'0')}/$cityId/${slug(city)}/gecmis-dereceler.html")
                    add("https://www.ganyancanavari.com.tr/?q=${urlEncode("$city $d ${months[m-1]} $y")}")
                }
            },
            discoveryPages = { _, _ -> listOf("https://www.ganyancanavari.com.tr/") }
        ),
        Source(
            name = "Puanlı Altılı Bülten",
            candidates = { city, date ->
                val (d, m, y) = dateParts(date)
                listOf(
                    "https://www.puanlibulten.com/",
                    "https://puanlialtilibulten.blogspot.com/search?q=${urlEncode("$city $d ${months[m-1]} $y")}",
                    "https://puanlialtilibulten.blogspot.com/search?q=${urlEncode(city)}"
                )
            },
            discoveryPages = { _, _ -> listOf("https://www.puanlibulten.com/", "https://puanlialtilibulten.blogspot.com/") }
        )
    )

    fun loadCachedOnly(context: Context, meetings: List<Meeting>): Map<String, RaceExpertSignal> {
        if (meetings.isEmpty()) return emptyMap()
        val cityDocs = meetings.distinctBy { it.city }.associate { meeting ->
            meeting.city to sources.mapNotNull { source ->
                loadCached(context, source.name, meeting.city, meeting.date)?.let { SourceDoc(source, it, false) }
            }
        }
        return buildSignals(meetings, cityDocs)
    }

    suspend fun loadIncremental(
        context: Context,
        meetings: List<Meeting>,
        onUpdate: suspend (Map<String, RaceExpertSignal>) -> Unit
    ): Map<String, RaceExpertSignal> = withContext(Dispatchers.IO) {
        if (meetings.isEmpty()) return@withContext emptyMap()

        // Cache önce: canlı kaynaklar gelene kadar ekranda 0/7 göstermeyelim.
        val docsByCity = meetings.distinctBy { it.city }.associate { meeting ->
            meeting.city to sources.mapNotNull { source ->
                loadCached(context, source.name, meeting.city, meeting.date)?.let { SourceDoc(source, it, false) }
            }.toMutableList()
        }.toMutableMap()

        val initial = buildSignals(meetings, docsByCity.mapValues { it.value.toList() })
        if (initial.isNotEmpty()) withContext(Dispatchers.Main) { onUpdate(initial) }

        supervisorScope {
            // Tüm kaynaklar gerçekten paralel çalışır. Global 15 sn kesme yok: yavaş bir kaynağın
            // güvenilir verisini sırf diğerlerinden geç geldi diye kaybetmeyiz. Her HTTP isteğinin
            // kendi timeout/retry sınırı fetchSource/client içinde kalır.
            val resultChannel = Channel<Triple<String, Source, SourceDoc?>>(Channel.UNLIMITED)
            val tasks = meetings.distinctBy { it.city }.flatMap { meeting ->
                sources.map { source ->
                    launch(Dispatchers.IO) {
                        val fresh = withTimeoutOrNull(30_000L) {
                            // Kaynak başına kesin üst sınır. fetchSource birden fazla URL keşfetse bile
                            // tek uzman sitesi toplamda 30 saniyeden uzun tutulmaz.
                            kotlinx.coroutines.runInterruptible {
                                fetchSource(source, meeting.city, meeting.date)
                            }
                        }
                        val cached = if (fresh == null) loadCached(context, source.name, meeting.city, meeting.date) else null
                        if (fresh != null) saveCached(context, source.name, meeting.city, meeting.date, fresh)
                        resultChannel.send(
                            Triple(
                                meeting.city,
                                source,
                                fresh?.let { SourceDoc(source, it, true) }
                                    ?: cached?.let { SourceDoc(source, it, false) }
                            )
                        )
                    }
                }
            }

            var remaining = tasks.size
            while (remaining > 0) {
                // İlk tamamlanan kaynağı al, ardından çok yakın zamanda gelen sonuçları tek UI
                // güncellemesinde birleştir. Böylece scroll sırasında 7 ayrı ağır recomposition yok.
                val batch = mutableListOf(resultChannel.receive())
                remaining--
                delay(300)
                while (true) {
                    val next = resultChannel.tryReceive().getOrNull() ?: break
                    batch += next
                    remaining--
                }

                var changed = false
                synchronized(docsByCity) {
                    batch.forEach { (city, source, doc) ->
                        if (doc != null) {
                            val list = docsByCity.getOrPut(city) { mutableListOf() }
                            list.removeAll { it.source.name == source.name }
                            list.add(doc)
                            changed = true
                        }
                    }
                }
                if (changed) {
                    val snapshot = synchronized(docsByCity) { docsByCity.mapValues { (_, v) -> v.toList() } }
                    // HTML expert aggregation/model input preparation stays off the Main thread.
                    val signals = withContext(Dispatchers.Default) { buildSignals(meetings, snapshot) }
                    withContext(Dispatchers.Main.immediate) { onUpdate(signals) }
                }
            }
            resultChannel.close()
        }

        buildSignals(meetings, synchronized(docsByCity) { docsByCity.mapValues { it.value.toList() } })
    }

    private fun buildSignals(meetings: List<Meeting>, cityDocs: Map<String, List<SourceDoc>>): Map<String, RaceExpertSignal> = buildMap {
        meetings.forEach { meeting ->
            val docs = cityDocs[meeting.city].orEmpty()
            meeting.races.forEach { race ->
                val supports = mutableMapOf<Int, MutableList<String>>()
                val strongs = mutableMapOf<Int, MutableList<String>>()
                val favoriteBankos = mutableMapOf<Int, MutableList<String>>()
                val surprises = mutableMapOf<Int, MutableList<String>>()
                val negatives = mutableMapOf<Int, MutableList<String>>()
                val sahaScores = mutableMapOf<Int, Int>()
                val sahaNotes = mutableMapOf<Int, MutableList<String>>()
                val validNos = race.horses.map { it.no }.toSet()
                val validatedDocs = docs.mapNotNull { doc ->
                    validatedRaceSection(doc.text, race, meeting.races.size)?.let { section -> doc to section }
                }.distinctBy { it.first.source.name }

                validatedDocs.forEach { (doc, raceText) ->
                    race.horses.forEach { horse ->
                        val signal = analyzeHorseText(raceText, horse, validNos)
                        val positive = if (doc.source.strongOnly) signal.strong else signal.positive
                        if (positive && !signal.negative) supports.getOrPut(horse.no) { mutableListOf() }.add(doc.source.name)
                        if (signal.strong && !signal.negative) strongs.getOrPut(horse.no) { mutableListOf() }.add(doc.source.name)
                        if (signal.favoriteBanko && !signal.negative) favoriteBankos.getOrPut(horse.no) { mutableListOf() }.add(doc.source.name)
                        if (signal.surprise && !signal.negative) surprises.getOrPut(horse.no) { mutableListOf() }.add(doc.source.name)
                        if (signal.negative) negatives.getOrPut(horse.no) { mutableListOf() }.add(doc.source.name)
                        if (signal.sahaScore != 0) sahaScores[horse.no] = (sahaScores[horse.no] ?: 0) + signal.sahaScore
                        if (signal.sahaNotes.isNotEmpty()) sahaNotes.getOrPut(horse.no) { mutableListOf() }.addAll(signal.sahaNotes)
                    }
                }
                val reachableDocs = docs.distinctBy { it.source.name }
                val reachableNames = reachableDocs.map { it.source.name }.distinct()
                val activeNames = validatedDocs.map { it.first.source.name }.distinct()
                val allNames = sources.map { it.name }
                val freshCount = validatedDocs.count { it.first.fresh }
                val cachedCount = validatedDocs.size - freshCount
                put(raceKey(race), RaceExpertSignal(
                    sourceCount = validatedDocs.size,
                    configuredSourceCount = sources.size,
                    reachableSourceCount = reachableDocs.size,
                    freshSourceCount = freshCount,
                    cachedSourceCount = cachedCount,
                    activeSources = activeNames,
                    reachableSources = reachableNames,
                    unreachableSources = allNames.filterNot { it in reachableNames },
                    unusableSources = reachableNames.filterNot { it in activeNames },
                    byHorse = race.horses.associate { horse ->
                        val src = supports[horse.no].orEmpty().distinct()
                        horse.no to ExpertHorseSignal(
                            support = src.size,
                            totalSources = validatedDocs.size,
                            sources = src,
                            strongSupport = strongs[horse.no].orEmpty().distinct().size,
                            favoriteBankoSupport = favoriteBankos[horse.no].orEmpty().distinct().size,
                            surpriseSupport = surprises[horse.no].orEmpty().distinct().size,
                            negativeSupport = negatives[horse.no].orEmpty().distinct().size,
                            sahaScore = sahaScores[horse.no] ?: 0,
                            sahaNotes = sahaNotes[horse.no].orEmpty().distinct()
                        )
                    }
                ))
            }
        }
    }

    private fun fetchSource(source: Source, city: String, date: String): String? {
        val attempted = mutableSetOf<String>()
        fun tryDoc(url: String): String? {
            if (!attempted.add(url)) return null
            val doc = fetchDocument(url) ?: return null
            val text = normalizeExpert(doc.body().text())
            if (looksRelevant(text, city, date)) return text
            discoverMatchingLinks(doc, city, date).forEach { discovered ->
                if (attempted.add(discovered)) {
                    fetchDocument(discovered)?.let { d2 ->
                        val t2 = normalizeExpert(d2.body().text())
                        if (looksRelevant(t2, city, date)) return t2
                    }
                }
            }
            return null
        }
        source.candidates(city, date).forEach { url -> tryDoc(url)?.let { return it } }
        source.discoveryPages(city, date).forEach { url -> tryDoc(url)?.let { return it } }
        return null
    }

    private fun fetchDocument(url: String): Document? {
        val userAgents = listOf(
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140 Safari/537.36"
        )
        repeat(2) { attempt ->
            val doc = runCatching {
                val req = okhttp3.Request.Builder().url(url)
                    .header("User-Agent", userAgents[attempt.coerceAtMost(userAgents.lastIndex)])
                    .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.6")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Cache-Control", "no-cache")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        if (r.code == 408 || r.code == 429 || r.code >= 500) Thread.sleep(250L * (attempt + 1))
                        null
                    } else {
                        val html = r.body?.string().orEmpty()
                        if (html.length < 250) null else Jsoup.parse(html, r.request.url.toString())
                    }
                }
            }.getOrNull()
            if (doc != null) return doc
        }
        return null
    }

    private fun discoverMatchingLinks(doc: Document, city: String, date: String): List<String> {
        val cityN = normalize(city)
        val (d, m, y) = dateParts(date)
        val month = months[m-1]
        return doc.select("a[href]").mapNotNull { a ->
            val text = normalizeExpert(a.text() + " " + a.attr("href"))
            val cityScore = if (text.contains(cityN)) 4 else 0
            val dayScore = if (Regex("(^|[^0-9])0?$d([^0-9]|${'$'})").containsMatchIn(text)) 2 else 0
            val monthScore = if (text.contains(month) || text.contains(m.toString().padStart(2,'0'))) 2 else 0
            val yearScore = if (text.contains(y.toString())) 1 else 0
            val topicScore = if (listOf("tahmin", "analiz", "kosu", "yaris", "bulten", "dikkat").any { text.contains(it) }) 2 else 0
            val score = cityScore + dayScore + monthScore + yearScore + topicScore
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (score >= 6 && href.startsWith("http")) score to href else null
        }.sortedByDescending { it.first }.map { it.second }.distinct().take(4)
    }

    private fun looksRelevant(text: String, city: String, date: String): Boolean {
        if (text.length < 180) return false
        val cityN = normalize(city)
        val (d, m, y) = dateParts(date)
        val dd = d.toString().padStart(2, '0')
        val mm = m.toString().padStart(2, '0')
        val exactDate = listOf(
            "$d ${months[m-1]} $y", "$dd ${months[m-1]} $y",
            "$dd.$mm.$y", "$d.$m.$y", "$dd/$mm/$y", "$d/$m/$y",
            "$dd-$mm-$y", "$y-$mm-$dd"
        ).any { text.contains(it) }
        val hasCity = text.contains(cityN)
        val hasDay = Regex("(^|[^0-9])0?$d([^0-9]|${'$'})").containsMatchIn(text)
        val hasMonth = text.contains(months[m-1]) || text.contains(mm)
        val hasYear = text.contains(y.toString())
        val hasRaceLanguage = listOf("kosu", "tahmin", "favori", "rakip", "surpriz", "banko", "agf", "ayak").any { text.contains(it) }
        return hasCity && hasRaceLanguage && (exactDate || (hasDay && hasMonth && hasYear))
    }

    private fun validatedRaceSection(text: String, race: Race, totalRaces: Int): String? {
        val raceNo = race.number
        val explicitPatterns = listOf("$raceNo kosu", "$raceNo. kosu", "${raceNo}kosu", "${raceNo} kosu olan")
        val explicitStarts = explicitPatterns.flatMap { pattern ->
            buildList {
                var pos = text.indexOf(pattern)
                while (pos >= 0) { add(pos); pos = text.indexOf(pattern, pos + pattern.length) }
            }
        }.distinct()
        if (explicitStarts.isNotEmpty()) {
            val next = raceNo + 1
            val candidates = explicitStarts.map { start ->
                val ends = listOf("$next kosu", "$next. kosu", "${next}kosu")
                    .map { text.indexOf(it, start + 4) }.filter { it > start }
                val end = ends.minOrNull() ?: min(text.length, start + 4200)
                text.substring(start, end)
            }.filter { sectionHasRaceSignal(it, race) }
            candidates.maxByOrNull { sectionQuality(it, race) }?.let { return it }
        }

        // Bazı tahmin siteleri koşu numarası yerine 1.AYAK/2.AYAK kullanıyor.
        // 2. altılının başlangıç koşusunu sayfadan bulup ayak -> koşu eşlemesi yap.
        val secondStart = Regex("2\\.?\\s*altili(?:\\s*ganyan)?(?:\\s*tahmin)?[^0-9]{0,80}(\\d{1,2})\\.?\\s*kosu")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val firstStart = if (secondStart != null) max(1, secondStart - 6) else max(1, totalRaces - 11)
        val useSecond = secondStart != null && raceNo >= secondStart
        val blockLabel = if (useSecond) "2 altili" else "1 altili"
        val blockStart = text.indexOf(blockLabel)
        if (blockStart >= 0) {
            val otherLabel = if (useSecond) "yedili" else "2 altili"
            val blockEndFound = text.indexOf(otherLabel, blockStart + blockLabel.length)
            val blockEnd = if (blockEndFound > blockStart) blockEndFound else min(text.length, blockStart + 7000)
            val block = text.substring(blockStart, blockEnd)
            val leg = if (useSecond) raceNo - (secondStart ?: raceNo) + 1 else raceNo - firstStart + 1
            if (leg in 1..6) {
                val legPatterns = listOf("$leg ayak", "$leg. ayak", "${leg}ayak")
                val legStarts = legPatterns.map { block.indexOf(it) }.filter { it >= 0 }
                if (legStarts.isNotEmpty()) {
                    val ls = legStarts.minOrNull() ?: 0
                    val nextLeg = leg + 1
                    val le = listOf("$nextLeg ayak", "$nextLeg. ayak", "${nextLeg}ayak")
                        .map { block.indexOf(it, ls + 3) }.filter { it > ls }.minOrNull() ?: min(block.length, ls + 900)
                    val section = block.substring(ls, le)
                    if (sectionHasRaceSignal(section, race)) return section
                }
            }
        }
        return null
    }

    private fun sectionQuality(section: String, race: Race): Int {
        val horseNames = race.horses.count { h -> normalize(h.name).length >= 4 && section.contains(normalize(h.name)) }
        val validNos = race.horses.map { it.no }.toSet()
        val nums = Regex("\\b\\d{1,2}\\b").findAll(section).mapNotNull { it.value.toIntOrNull() }.filter { it in validNos }.toSet().size
        val tipCount = listOf("favori", "banko", "tek", "rakip", "surpriz", "plase", "tahmin", "ayak").count { section.contains(it) }
        return horseNames * 8 + nums * 2 + tipCount
    }

    private fun sectionHasRaceSignal(section: String, race: Race): Boolean {
        val horseNames = race.horses.count { h -> normalize(h.name).length >= 4 && section.contains(normalize(h.name)) }
        val validNos = race.horses.map { it.no }.toSet()
        val nums = Regex("\\b\\d{1,2}\\b").findAll(section).mapNotNull { it.value.toIntOrNull() }
            .filter { it in validNos }.toSet()
        val hasTipLanguage = listOf("ayak", "favori", "banko", "tek", "rakip", "surpriz", "plase", "tahmin").any { section.contains(it) }
        return horseNames >= 1 || (hasTipLanguage && nums.size >= 2)
    }

    private data class ParsedHorseText(
        val positive: Boolean = false,
        val strong: Boolean = false,
        val favoriteBanko: Boolean = false,
        val surprise: Boolean = false,
        val negative: Boolean = false,
        val sahaScore: Int = 0,
        val sahaNotes: List<String> = emptyList()
    )

    private fun analyzeHorseText(text: String, horse: Horse, validNos: Set<Int>): ParsedHorseText {
        val needle = normalize(horse.name)
        val favoriteBankoWords = listOf("banko", "tek", "favori", "favorim", "gunun teki", "gunun bankosu", "bankom")
        val strongWords = (favoriteBankoWords + listOf("ilk sans", "ilk atim", "birinci sans", "cok sansli", "en sansli")).distinct()
        val positiveWords = listOf("rakip", "ihmal edilmemeli", "oner", "sansli", "aday", "plase", "degerlendir", "kazanabilir")
        val surpriseWords = listOf("surpriz", "supriz", "bomba", "bombasi", "ters")
        val negativeWords = listOf("gelmez", "yazmam", "onermiyorum", "onermem", "sansini az", "sansi az", "sans vermiyorum", "dusunmuyorum", "yetersiz", "elenir", "elerim", "elemem")

        var directStrong = false
        var directFavoriteBanko = false
        var directPositive = false
        var directSurprise = false
        var directNegative = false
        val notes = mutableListOf<String>()
        var saha = 0

        if (needle.length >= 4) {
            var start = text.indexOf(needle)
            while (start >= 0) {
                // At adının yakınındaki cümle/ifade dışına taşmamaya çalış. Önceki geniş pencere,
                // başka atlara ait yorumlardaki kelimeleri bu ata yanlış bağlayabiliyordu.
                val from = max(0, start - 110)
                val end = min(text.length, start + needle.length + 180)
                val snippet = text.substring(from, end)
                val local = snippet
                    .split(Regex("[.!?;|\n]") )
                    .firstOrNull { it.contains(needle) }
                    ?: snippet
                directFavoriteBanko = directFavoriteBanko || favoriteBankoWords.any { local.contains(it) }
                directStrong = directStrong || strongWords.any { local.contains(it) } || Regex("★{3,5}").containsMatchIn(local)
                directSurprise = directSurprise || surpriseWords.any { local.contains(it) }
                directPositive = directPositive || directStrong || directSurprise || positiveWords.any { local.contains(it) }
                directNegative = directNegative || negativeWords.any { phrase ->
                    Regex("(^|[^a-z0-9çğıöşü])" + Regex.escape(phrase) + "([^a-z0-9çğıöşü]|$)").containsMatchIn(local)
                }

                if (listOf("jokeyiyle daha once yaris kazan", "ayni jokeyle daha once kazan", "bu jokeyle daha once kazan").any { snippet.contains(it) }) {
                    saha += 3; notes += "Aynı jokeyle daha önce kazanmış"
                }
                if (listOf("sadece o ata binecek jokey", "yalniz bu ata binecek jokey", "jokey sadece bu ata binecek").any { snippet.contains(it) }) {
                    saha += 1; notes += "Jokey gün içinde yalnız bu ata biniyor"
                }
                if (listOf("taki degisikligi", "aksesuar degisikligi").any { snippet.contains(it) }) {
                    notes += "Takı değişikliği var"
                }
                if (listOf("sehir degisikligi", "hipodrom degisikligi").any { snippet.contains(it) }) {
                    notes += "Şehir/hipodrom değişikliği var"
                }
                start = text.indexOf(needle, start + needle.length)
            }
        }

        val listFavoriteBanko = numberRecommendation(text, horse.no, validNos, favoriteBankoWords)
        val listStrong = numberRecommendation(text, horse.no, validNos, strongWords)
        val listPositive = numberRecommendation(text, horse.no, validNos, positiveWords)
        val listSurprise = numberRecommendation(text, horse.no, validNos, surpriseWords)
        val listNegative = numberRecommendation(text, horse.no, validNos, negativeWords)
        val negative = directNegative || listNegative
        val favoriteBanko = directFavoriteBanko || listFavoriteBanko
        val strong = directStrong || listStrong || favoriteBanko
        val surprise = directSurprise || listSurprise
        val positive = directPositive || listPositive || strong || surprise
        return ParsedHorseText(positive, strong, favoriteBanko, surprise, negative, saha.coerceIn(-4, 6), notes.distinct())
    }

    private fun numberRecommendation(text: String, horseNo: Int, validNos: Set<Int>, labels: List<String>): Boolean {
        labels.forEach { label ->
            var i = text.indexOf(label)
            while (i >= 0) {
                val from = max(0, i - 65)
                val end = min(text.length, i + label.length + 85)
                val snippet = text.substring(from, end)
                val nums = Regex("\\b\\d{1,2}\\b").findAll(snippet).mapNotNull { it.value.toIntOrNull() }
                    .filter { it in validNos }.toSet()
                if (horseNo in nums) return true
                i = text.indexOf(label, i + label.length)
            }
        }
        return false
    }

    private fun horseMention(text: String, needle: String): Boolean {
        if (needle.length < 4) return false
        var start = text.indexOf(needle)
        while (start >= 0) {
            val from = max(0, start - 180)
            val end = min(text.length, start + needle.length + 260)
            val snippet = text.substring(from, end)
            if (listOf("favori", "ilk sans", "birinci", "rakip", "surpriz", "banko", "tek", "oner", "sansli", "aday", "plase").any { snippet.contains(it) }) return true
            start = text.indexOf(needle, start + needle.length)
        }
        return false
    }

    private fun strongMention(text: String, needle: String): Boolean {
        if (needle.length < 4) return false
        var start = text.indexOf(needle)
        while (start >= 0) {
            val from = max(0, start - 180)
            val end = min(text.length, start + needle.length + 320)
            val snippet = text.substring(from, end)
            if (Regex("★{3,5}").containsMatchIn(snippet) || listOf("favori", "banko", "tek", "ilk sans", "birinci").any { snippet.contains(it) }) return true
            start = text.indexOf(needle, start + needle.length)
        }
        return false
    }

    private fun saveCached(context: Context, source: String, city: String, date: String, text: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(cacheKey(source, city, date), text.take(120_000)).apply()
    }

    private fun loadCached(context: Context, source: String, city: String, date: String): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(cacheKey(source, city, date), null)

    private fun cacheKey(source: String, city: String, date: String): String = "${normalize(source)}|${normalize(city)}|$date"

    private fun normalizeExpert(v: String): String = v.lowercase(Locale.forLanguageTag("tr-TR"))
        .replace("ı", "i").replace("ş", "s").replace("ğ", "g").replace("ü", "u").replace("ö", "o").replace("ç", "c")
        .replace(Regex("[^a-z0-9 ★./:-]+"), " ").replace(Regex("\\s+"), " ").trim()

    private fun dateParts(date: String): Triple<Int, Int, Int> {
        val p = date.split("/")
        return Triple(p.getOrNull(0)?.toIntOrNull() ?: 1, p.getOrNull(1)?.toIntOrNull() ?: 1, p.getOrNull(2)?.toIntOrNull() ?: 2026)
    }
    private fun slug(v: String): String = normalize(v).replace(" ", "-")
    private fun normalize(v: String): String = v.lowercase(Locale.forLanguageTag("tr-TR"))
        .replace("ı", "i").replace("ş", "s").replace("ğ", "g").replace("ü", "u").replace("ö", "o").replace("ç", "c")
        .replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()
    private fun urlEncode(v: String): String = URLEncoder.encode(v, "UTF-8")
    private fun ganyanCityId(city: String): Int? = when (normalize(city)) {
        "istanbul" -> 3; "bursa" -> 4; "ankara" -> 5; "izmir" -> 6; "adana" -> 1; "elazig" -> 8; "kocaeli" -> 10; "sanliurfa" -> 9; "diyarbakir" -> 7; "antalya" -> 11; else -> null
    }
}
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
        if (lastError != null) android.util.Log.w("TwoHorse", "TJK fetch failed", lastError)
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
        val raceHeads: List<Triple<Int, String, String>> = h3s.mapIndexedNotNull { idx, text ->
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

        val sixliStarts = doc.select("h3").mapNotNull { h ->
            val hm = Regex("^(\\d+)\\.\\s*Koşu", RegexOption.IGNORE_CASE).find(h.text().replace(Regex("\\s+"), " ").trim())
                ?: return@mapNotNull null
            val raceNo = hm.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val context = buildString {
                var e: Element? = h.previousElementSibling()
                var seen = 0
                while (e != null && seen < 16) {
                    val t = e.text().replace(Regex("\\s+"), " ").trim()
                    if (Regex("^\\d+\\.\\s*Koşu", RegexOption.IGNORE_CASE).containsMatchIn(t)) break
                    append(' ').append(t)
                    e = e.previousElementSibling(); seen++
                }
            }
            raceNo.takeIf { context.contains("6'LI GANYAN", true) && context.contains("Bu koşudan başlar", true) }
        }.distinct().sorted()

        return Meeting(city, date, trackInfo, races, sixliStarts)
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
            val nameLink = nameCell.select("a[href]").firstOrNull()
            val detailUrl = nameLink?.let { a ->
                a.attr("abs:href").ifBlank {
                    val href = a.attr("href")
                    if (href.startsWith("http", true)) href else BASE + if (href.startsWith('/')) href else "/$href"
                }
            }
            val linkedName = nameLink?.ownText()?.trim().orEmpty()
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

            val directVideo = row.select("a[href]").firstOrNull { a ->
                val href = a.attr("href")
                href.contains("YarisVideoAt", true) && href.contains("AtKodu", true)
            }?.let { a ->
                a.attr("abs:href").ifBlank {
                    val href = a.attr("href")
                    if (href.startsWith("http", true)) href else BASE + if (href.startsWith('/')) href else "/$href"
                }
            }

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
                start = firstInt(cell(iStart)),
                videoUrl = directVideo,
                detailUrl = detailUrl
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
    fun picks(race: Race, expert: RaceExpertSignal? = null): List<Pick> {
        if (race.horses.isEmpty()) return emptyList()
        val maxHp = race.horses.mapNotNull { it.hp }.maxOrNull()?.coerceAtLeast(1) ?: 1
        val minWeight = race.horses.mapNotNull { it.weight }.minOrNull() ?: 0.0
        val maxWeight = race.horses.mapNotNull { it.weight }.maxOrNull() ?: minWeight
        val minOdds = race.horses.mapNotNull { it.odds }.minOrNull()?.coerceAtLeast(.1) ?: 1.0
        val maxAgf = race.horses.mapNotNull { it.agf }.maxOrNull()?.coerceAtLeast(1) ?: 1

        fun rankOf(horse: Horse, selector: (Horse) -> Double?): Int? {
            val value = selector(horse) ?: return null
            val vals = race.horses.mapNotNull { h -> selector(h)?.let { h.no to it } }
                .sortedByDescending { it.second }
            return vals.indexOfFirst { it.first == horse.no }.takeIf { it >= 0 }?.plus(1)
        }
        fun support(h: Horse): ExpertHorseSignal = expert?.byHorse?.get(h.no) ?: ExpertHorseSignal(totalSources = expert?.sourceCount ?: 0)
        fun expertRank(h: Horse): Int? {
            if ((expert?.sourceCount ?: 0) <= 0) return null
            if (support(h).support <= 0) return null
            val ordered = race.horses.map { it.no to support(it).support }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
            return ordered.indexOfFirst { it.first == h.no }.takeIf { it >= 0 }?.plus(1)
        }
        fun formScore(last6: String): Double {
            val places = last6.filter { it in '1'..'9' }.map { it.digitToInt() }.takeLast(5)
            if (places.isEmpty()) return 0.0
            return places.mapIndexed { i, p -> ((6 - p).coerceAtLeast(0)) * (1.0 + i * .12) }.sum()
        }
        fun formLabel(last6: String): String {
            val places = last6.filter { it in '1'..'9' }.map { it.digitToInt() }.takeLast(4)
            if (places.size < 2) return "→ Veri sınırlı"
            val recent = places.takeLast(2).average()
            val older = places.take(places.size - 2).ifEmpty { places.take(1) }.average()
            return when {
                recent + .35 < older -> "↗ Yükseliyor"
                recent > older + .7 -> "↘ Geriliyor"
                places.count { it <= 3 } >= 2 -> "↑ Formda"
                else -> "→ Dengeli"
            }
        }

        val maxFormScore = race.horses.maxOfOrNull { formScore(it.last6) }?.coerceAtLeast(1.0) ?: 1.0

        val raw = race.horses.map { h ->
            var weighted = 0.0
            var availableWeight = 0.0
            val reasons = mutableListOf<String>()
            fun addComponent(value: Double, weight: Double) {
                weighted += value.coerceIn(0.0, 1.0) * weight
                availableWeight += weight
            }

            h.agf?.let {
                addComponent(it.toDouble() / maxAgf, 30.0)
                if (it >= 18) reasons += "AGF desteği yüksek"
            }
            h.hp?.let {
                addComponent(it.toDouble() / maxHp, 10.0)
                if (it >= maxHp - 5) reasons += "HP gruba göre güçlü"
            }
            h.weight?.let { w ->
                val weightValue = if (maxWeight <= minWeight) .5 else (maxWeight - w) / (maxWeight - minWeight)
                addComponent(weightValue, 5.0)
                if (w <= minWeight + 1.0) reasons += "kilo avantajı"
            }
            h.odds?.let { o ->
                addComponent((minOdds / o).coerceAtMost(1.0), 10.0)
                if (o <= minOdds * 1.6) reasons += "piyasa desteği"
            }
            val fScore = formScore(h.last6)
            if (h.last6.isNotBlank()) addComponent(fScore / maxFormScore, 15.0)
            if (formLabel(h.last6).contains("Yükseliyor") || formLabel(h.last6).contains("Formda")) reasons += "yakın formu olumlu"
            if (h.best.isNotBlank()) reasons += "pist/mesafe derecesi var"

            val ex = support(h)
            if (ex.totalSources > 0) {
                val ratio = ex.support.toDouble() / ex.totalSources
                val strongRatio = ex.strongSupport.toDouble() / ex.totalSources
                val favoriteBankoRatio = ex.favoriteBankoSupport.toDouble() / ex.totalSources
                val surpriseRatio = ex.surpriseSupport.toDouble() / ex.totalSources
                val negativeRatio = ex.negativeSupport.toDouble() / ex.totalSources
                // Favori/banko/tek, güçlü desteğin bir alt kümesidir. Aynı siteyi iki tam oy gibi
                // saymıyoruz; açık favori/banko ifadesi güçlü desteğe küçük bir ek bonus verir.
                val expertValue = (ratio * .54 + strongRatio * .25 + favoriteBankoRatio * .15 + surpriseRatio * .06 - negativeRatio * .38).coerceIn(0.0, 1.0)
                addComponent(expertValue, 25.0)
                if (ex.sahaNotes.isNotEmpty()) {
                    val sahaValue = ((ex.sahaScore.coerceIn(-4, 6) + 4).toDouble() / 10.0)
                    addComponent(sahaValue, 5.0)
                }
                if (ex.support > 0) reasons += "uzman desteği ${ex.support}/${ex.totalSources}"
                if (ex.strongSupport > 0) reasons += "${ex.strongSupport} güçlü uzman sinyali"
                if (ex.favoriteBankoSupport > 0) reasons += "⭐ ${ex.favoriteBankoSupport} favori/banko sinyali"
                if (ex.negativeSupport > 0) reasons += "${ex.negativeSupport} olumsuz uzman görüşü"
                if (ex.sahaNotes.isNotEmpty()) reasons += ex.sahaNotes.first()
            }
            val score = if (availableWeight > 0.0) (weighted / availableWeight) * 100.0 else 50.0
            h to (score to reasons)
        }

        val maxRaw = raw.maxOf { it.second.first }
        val minRaw = raw.minOf { it.second.first }
        val sorted = raw.sortedByDescending { it.second.first }
        return sorted.mapIndexed { index, item ->
            val h = item.first
            val normalized = item.second.first.toInt().coerceIn(35, 96)
            val agf = h.agf ?: 0
            val hp = h.hp
            val weight = h.weight
            val ex = support(h)
            val reasons = item.second.second.toMutableList()
            if (reasons.isEmpty()) {
                when {
                    agf in 1..8 -> reasons += "AGF desteği sınırlı; sürpriz senaryosunda değerlendirilebilir"
                    hp != null && hp < maxHp - 10 -> reasons += "HP rakiplerin gerisinde; yarış temposuna ihtiyaç duyabilir"
                    weight != null && weight > minWeight + 3 -> reasons += "kilo dezavantajı var"
                    else -> reasons += "belirgin üstünlük yok; tempo ve pozisyon belirleyici"
                }
            }
            val marketRank = rankOf(h) { it.agf?.toDouble() ?: it.odds?.let { o -> 100.0 / o } }
            val market = when {
                marketRank == 1 -> "Çok güçlü"
                marketRank == 2 -> "Güçlü"
                marketRank != null && marketRank <= 4 -> "Orta"
                else -> "Zayıf"
            }
            val label = when {
                index == 0 -> "Favori"
                index == 1 -> "Ciddi rakip"
                agf <= 9 && normalized >= 60 -> "Sürpriz"
                else -> "Alternatif"
            }
            Pick(
                horse = h,
                score = normalized,
                label = label,
                reasons = reasons.distinct().take(4),
                agfRank = rankOf(h) { it.agf?.toDouble() },
                hpRank = rankOf(h) { it.hp?.toDouble() },
                expertRank = expertRank(h),
                expertSupport = ex.support,
                expertTotal = ex.totalSources,
                expertSources = ex.sources,
                expertStrong = ex.strongSupport,
                expertFavoriteBanko = ex.favoriteBankoSupport,
                expertSurprise = ex.surpriseSupport,
                expertNegative = ex.negativeSupport,
                sahaScore = ex.sahaScore,
                sahaNotes = ex.sahaNotes,
                marketLabel = market,
                formLabel = formLabel(h.last6)
            )
        }
    }
}

@Composable
fun TwoHorseApp() {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Green,
            secondary = Gold,
            background = Bg,
            surface = Surface,
            onSurface = Ink
        )
    ) {
        val context = LocalContext.current.applicationContext
        val initialCache = remember { MeetingCache.load(context) }
        // Program cache'i anında ekrana gelir. Uzman cache'inin parse/aggregation işi daha ağır
        // olabildiği için Main thread'i bloke etmeden hemen arka planda yüklenir.
        var meetings by remember { mutableStateOf(initialCache) }
        var expertSignals by remember { mutableStateOf<Map<String, RaceExpertSignal>>(emptyMap()) }
        var loading by remember { mutableStateOf(initialCache.isEmpty()) }
        var refreshing by remember { mutableStateOf(false) }
        var expertsRefreshing by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var selectedRace by remember { mutableStateOf<Race?>(null) }
        var historyOpen by remember { mutableStateOf(false) }
        var sixliOpen by remember { mutableStateOf(false) }
        var selectedHistory by remember { mutableStateOf<HistorySnapshot?>(null) }
        var expertRefreshToken by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        val activity = LocalContext.current as? ComponentActivity

        fun refresh(forceExperts: Boolean = false) {
            if (refreshing) return
            scope.launch {
                refreshing = true
                if (meetings.isEmpty()) loading = true
                error = null
                val result = runCatching { TjkRepository.loadToday() }
                val fresh = result.getOrDefault(emptyList())
                if (fresh.isNotEmpty()) {
                    meetings = fresh
                    MeetingCache.save(context, fresh)
                } else if (meetings.isEmpty()) {
                    error = result.exceptionOrNull()?.message ?: "Türkiye programı bulunamadı."
                }
                loading = false
                refreshing = false
                if (forceExperts) expertRefreshToken++
            }
        }

        // İlk açılışta cache hemen çizilir; TJK arka planda yenilenir.
        LaunchedEffect(Unit) { refresh(forceExperts = false) }

        // Uygulama gerçekten arka plana gittikten sonra tekrar ön plana gelirse otomatik yenile.
        // İç ekran navigasyonları bunu tetiklemez. O sırada refresh sürüyorsa ikinci istek başlatılmaz.
        DisposableEffect(activity) {
            val lifecycle = activity?.lifecycle
            var wasStopped = false
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> wasStopped = true
                    Lifecycle.Event.ON_START -> if (wasStopped) {
                        wasStopped = false
                        refresh(forceExperts = true)
                    }
                    else -> Unit
                }
            }
            lifecycle?.addObserver(observer)
            onDispose { lifecycle?.removeObserver(observer) }
        }

        LaunchedEffect(meetings, expertRefreshToken) {
            if (meetings.isNotEmpty()) {
                expertsRefreshing = true
                try {
                    // Yeni TJK programı geldiyse bile önce aynı günün uzman cache'ini göster.
                    val cachedExperts = withContext(Dispatchers.Default) {
                        ExpertRepository.loadCachedOnly(context, meetings)
                    }
                    if (cachedExperts.isNotEmpty()) expertSignals = cachedExperts
                    // Gelecek yarışların snapshot'ını hemen oluştur; canlı uzmanlar geldikçe yarış saatine kadar overwrite edilir.
                    withContext(Dispatchers.IO) { HistoryStore.capture(context, meetings, cachedExperts) }

                    val loadedExperts = ExpertRepository.loadIncremental(context, meetings) { partial ->
                        // Batch halinde gelen uzman sonucu UI state'ine tek atomik güncelleme olarak verilir.
                        expertSignals = partial
                        // JSON snapshot yazımı scroll/Main thread'i bloke etmesin.
                        launch(Dispatchers.IO) { HistoryStore.capture(context, meetings, partial) }
                    }
                    expertSignals = loadedExperts
                    withContext(Dispatchers.IO) { HistoryStore.capture(context, meetings, loadedExperts) }
                } finally {
                    expertsRefreshing = false
                }
            } else {
                expertsRefreshing = false
            }
        }

        BackHandler(enabled = selectedRace != null || selectedHistory != null || historyOpen || sixliOpen) {
            when { selectedRace != null -> selectedRace = null; selectedHistory != null -> selectedHistory = null; historyOpen -> historyOpen = false; sixliOpen -> sixliOpen = false }
        }

        Surface(Modifier.fillMaxSize(), color = Bg) {
            when {
                selectedRace != null -> RaceDetail(selectedRace!!, expertSignals[raceKey(selectedRace!!)], expertsRefreshing, onBack = { selectedRace = null })
                selectedHistory != null -> HistoryDetail(selectedHistory!!, onBack = { selectedHistory = null })
                historyOpen -> HistoryScreen(HistoryStore.load(context), onBack = { historyOpen = false }, onOpen = { selectedHistory = it })
                sixliOpen -> SixliScreen(meetings, expertSignals, onBack = { sixliOpen = false }, onRace = { selectedRace = it })
                else -> Home(meetings, expertSignals, loading, refreshing, error, { refresh(forceExperts = true) }, { selectedRace = it }, { historyOpen = true }, { sixliOpen = true })
            }
        }
    }
}

@Composable
fun Home(
    meetings: List<Meeting>,
    experts: Map<String, RaceExpertSignal>,
    loading: Boolean,
    refreshing: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onRace: (Race) -> Unit,
    onHistory: () -> Unit,
    onSixli: () -> Unit
) {
    var selectedCity by remember(meetings) { mutableStateOf("Tümü") }
    var otherExpanded by remember { mutableStateOf(false) }
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowTick = System.currentTimeMillis()
        }
    }

    // Başlamış/biten koşular hiçbir ekranda tekrar gösterilmez.
    val activeMeetings = remember(meetings, nowTick) {
        meetings.mapNotNull { meeting ->
            val futureRaces = meeting.races
                .filter { secondsUntil(it.time, nowTick) > 0L }
                .sortedBy { secondsUntil(it.time, nowTick) }
            if (futureRaces.isEmpty()) null else meeting.copy(races = futureRaces)
        }
    }
    val allUpcoming = remember(activeMeetings, nowTick) {
        activeMeetings.flatMap { it.races }.sortedBy { secondsUntil(it.time, nowTick) }
    }

    // Hızlı erişim tek bir şehir içindir: o anda Türkiye'deki EN YAKIN koşunun şehri.
    // O şehrin ilk 6 yaklaşan koşusu üstte, fazlası collapsible bölümde görünür.
    val leadCity = allUpcoming.firstOrNull()?.city
    val leadMeeting = activeMeetings.firstOrNull { it.city == leadCity }
    val leadRaces = leadMeeting?.races.orEmpty().sortedBy { secondsUntil(it.time, nowTick) }
    val quickRaces = leadRaces.take(6)
    val overflowLeadRaces = leadRaces.drop(6)

    // Üst blokta bir şehrin tüm kalan koşuları zaten kapsandığından aşağıda tekrar etmiyoruz.
    val lowerMeetings = activeMeetings.filter { it.city != leadCity }
    val cities = remember(lowerMeetings) { listOf("Tümü") + lowerMeetings.map { it.city }.distinct() }
    LaunchedEffect(cities) {
        if (selectedCity !in cities) selectedCity = "Tümü"
    }
    LaunchedEffect(leadCity) { otherExpanded = false }

    val visibleMeetings = if (selectedCity == "Tümü") lowerMeetings else lowerMeetings.filter { it.city == selectedCity }
    val lowerRaceCount = lowerMeetings.sumOf { it.races.size }

    Column(Modifier.fillMaxSize()) {
        TopHeader(onRefresh, refreshing, onHistory, onSixli)
        when {
            loading -> LoadingState()
            meetings.isEmpty() -> EmptyState(error, onRefresh)
            allUpcoming.isEmpty() -> LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 28.dp)
            ) { item { NextRaceHero(null, nowTick, onRace) } }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { NextRaceHero(quickRaces.firstOrNull(), nowTick, onRace) }

                if (quickRaces.size > 1) {
                    item { SectionHeader("Sıradaki yarışlar", "${leadCity.orEmpty()} · en yakın 6") }
                    items(quickRaces.drop(1)) { race -> UpcomingRaceCard(race, nowTick, experts[raceKey(race)], onRace) }
                }

                if (overflowLeadRaces.isNotEmpty()) {
                    item {
                        RemainingRacesToggle(
                            count = overflowLeadRaces.size,
                            expanded = otherExpanded,
                            onToggle = { otherExpanded = !otherExpanded }
                        )
                    }
                    if (otherExpanded) {
                        items(overflowLeadRaces) { race -> UpcomingRaceCard(race, nowTick, experts[raceKey(race)], onRace) }
                    }
                }

                if (lowerMeetings.isNotEmpty()) {
                    item { CityFilter(cities, selectedCity) { selectedCity = it } }
                    item { SectionHeader("Diğer şehirler", "$lowerRaceCount koşu · ${lowerMeetings.size} şehir") }
                    visibleMeetings.forEach { meeting ->
                        item { MeetingHeader(meeting) }
                        items(meeting.races) { race -> RaceCard(race, nowTick, experts[raceKey(race)], onRace) }
                    }
                }
            }
        }
    }
}


data class SixliCoupon(val name: String, val subtitle: String, val legs: List<List<Pick>>) {
    val combinations: Long get() = legs.fold(1L) { acc, leg -> acc * leg.size.coerceAtLeast(1) }
}

data class SixliWindow(val meeting: Meeting, val startRace: Int, val races: List<Race>)

private fun availableSixliWindows(meetings: List<Meeting>, now: Long = System.currentTimeMillis()): List<SixliWindow> {
    return meetings.flatMap { m ->
        val starts = if (m.sixliStarts.isNotEmpty()) m.sixliStarts else {
            // Eski cache'te marker yoksa yalnız güvenli fallback: programda tam 6 koşuluk bloklar.
            when (m.races.size) {
                6 -> listOf(m.races.firstOrNull()?.number ?: 1)
                8 -> listOf(1, 3)
                else -> emptyList()
            }
        }
        starts.mapNotNull { start ->
            val rs = (start until start + 6).mapNotNull { n -> m.races.firstOrNull { it.number == n } }
            if (rs.size == 6 && secondsUntil(rs.first().time, now) > 0L) SixliWindow(m, start, rs) else null
        }
    }.sortedBy { secondsUntil(it.races.first().time, now) }
}

private fun buildSixliCoupons(window: SixliWindow, experts: Map<String, RaceExpertSignal>): List<SixliCoupon> {
    val ranked = window.races.map { r -> Predictor.picks(r, experts[raceKey(r)]) }
    fun gap(ps: List<Pick>): Int = if (ps.size >= 2) ps[0].score - ps[1].score else 99
    fun certainty(ps: List<Pick>): Double {
        if (ps.isEmpty()) return -999.0
        val p = ps.first()
        val bankoBonus = p.expertFavoriteBanko * 2.5
        val supportRatio = if (p.expertTotal > 0) p.expertSupport.toDouble() / p.expertTotal else 0.0
        return p.score + gap(ps) * 1.8 + bankoBonus + supportRatio * 8.0
    }
    val bankerIndex = ranked.indices.maxByOrNull { certainty(ranked[it]) } ?: 0
    val secondStrong = ranked.indices.filter { it != bankerIndex }.maxByOrNull { certainty(ranked[it]) }

    fun countFor(ps: List<Pick>, mode: Int, idx: Int): Int {
        if (ps.isEmpty()) return 0
        val g = gap(ps)
        val top = ps.first().score
        val field = ps.size
        return when (mode) {
            0 -> when {
                idx == bankerIndex -> 1
                idx == secondStrong && top >= 74 && g >= 7 -> min(2, field)
                top >= 76 && g >= 8 -> min(2, field)
                top >= 68 && g >= 4 -> min(3, field)
                else -> min(4, field)
            }
            1 -> when {
                top >= 80 && g >= 10 -> min(2, field)
                top >= 72 && g >= 5 -> min(3, field)
                else -> min(4, field)
            }
            else -> when {
                top >= 84 && g >= 12 -> min(2, field)
                top >= 76 && g >= 7 -> min(3, field)
                top >= 66 -> min(4, field)
                else -> min(6, field)
            }
        }
    }
    fun make(mode: Int, name: String, subtitle: String) = SixliCoupon(name, subtitle, ranked.mapIndexed { i, ps -> ps.take(countFor(ps, mode, i)) })
    return listOf(
        make(0, "Dar Kupon", "Bir ayakta TEK · diğer ayaklar güvene göre 2–4 at"),
        make(1, "Dengeli Kupon", "Maliyet ve güven dengesi · ayaklar 2–4 at"),
        make(2, "En Risksiz", "En geniş koruma · açık ayaklarda 4–6 ata kadar")
    )
}

@Composable
private fun SixliScreen(meetings: List<Meeting>, experts: Map<String, RaceExpertSignal>, onBack: () -> Unit, onRace: (Race) -> Unit) {
    var selectedWindowKey by remember { mutableStateOf<String?>(null) }
    val windows = remember(meetings) { availableSixliWindows(meetings) }
    val selected = windows.firstOrNull { "${it.meeting.city}-${it.startRace}" == selectedWindowKey } ?: windows.firstOrNull()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Geri") }
            Column(Modifier.weight(1f)) {
                Text("6’lı Kupon", fontWeight = FontWeight.Black, fontSize = 24.sp, color = Ink)
                Text("Model + AGF + uzman + form verisinden 3 strateji", color = Muted, fontSize = 11.sp)
            }
        }
        if (windows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Şu an başlamamış 6’lı Ganyan bulunamadı.", color = Muted)
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Aktif 6’lılar", fontWeight = FontWeight.Black, fontSize = 13.sp, color = Ink)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    windows.forEach { w ->
                        val key = "${w.meeting.city}-${w.startRace}"
                        FilterChip(
                            selected = selected?.meeting?.city == w.meeting.city && selected?.startRace == w.startRace,
                            onClick = { selectedWindowKey = key },
                            label = { Text("${w.meeting.city} · ${w.startRace}. koşu") }
                        )
                    }
                }
            }
            selected?.let { w ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = PaleGreen), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("${w.meeting.city} · ${w.startRace}. koşudan başlar", color = Green, fontWeight = FontWeight.Black, fontSize = 17.sp)
                            Text("${w.races.first().time} → ${w.races.last().time} · 6 ayağın birincisini bulma oyunu", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
                val coupons = buildSixliCoupons(w, experts)
                coupons.forEachIndexed { ci, coupon ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(20.dp), border = CardDefaults.outlinedCardBorder()) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(coupon.name, fontWeight = FontWeight.Black, fontSize = 17.sp, color = Ink)
                                        Text(coupon.subtitle, color = Muted, fontSize = 11.sp)
                                    }
                                    Surface(color = if (ci == 0) PaleGold else PaleGreen, shape = RoundedCornerShape(12.dp)) {
                                        Text("${coupon.combinations} kombinasyon", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = if (ci == 0) Gold else Green, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                coupon.legs.forEachIndexed { i, leg ->
                                    val race = w.races[i]
                                    val isSingle = leg.size == 1
                                    Row(
                                        Modifier.fillMaxWidth().clickable { onRace(race) }.padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(color = if (isSingle) PaleGold else Bg, shape = RoundedCornerShape(10.dp)) {
                                            Text("${i + 1}. AYAK", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontWeight = FontWeight.Black, fontSize = 10.sp, color = if (isSingle) Gold else Ink)
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Text(leg.joinToString(" - ") { it.horse.no.toString() }, modifier = Modifier.weight(1f), fontWeight = FontWeight.Black, color = Ink, fontSize = 15.sp)
                                        if (isSingle) Text("TEK · ${leg.firstOrNull()?.score ?: 0}/100", color = Gold, fontWeight = FontWeight.Black, fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("Koşuya dokunarak adayların gerekçelerini görebilirsin.", color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
                item {
                    Text("Not: 6’lı Ganyan, TJK’ya göre aynı gün belirlenen 6 koşunun birincilerini bulma oyunudur. Kuponlar tahmindir; kombinasyon sayısı seçilen at adetlerinin çarpımıdır.", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(snapshots: List<HistorySnapshot>, onBack: () -> Unit, onOpen: (HistorySnapshot) -> Unit) {
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while(true){ delay(1000); nowTick=System.currentTimeMillis() } }
    val ended = remember(snapshots, nowTick) { snapshots.filter { secondsUntil(it.race.time, nowTick) <= 0L }.sortedByDescending { it.race.time } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack){ Icon(Icons.Default.ArrowBack,"Geri") }
            Column { Text("Geçmiş",fontWeight=FontWeight.Black,fontSize=24.sp); Text("Yalnız bugün biten koşular · kayıtlı tahmin",color=Muted,fontSize=11.sp) }
        }
        if(ended.isEmpty()) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){ Text("Henüz bu kurulumda yarış öncesi kaydedilip biten koşu yok.",color=Muted) }
        else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(ended){ snap -> Card(Modifier.fillMaxWidth().clickable{onOpen(snap)},colors=CardDefaults.cardColors(containerColor=Surface),shape=RoundedCornerShape(16.dp)){
                Row(Modifier.fillMaxWidth().padding(15.dp),verticalAlignment=Alignment.CenterVertically){ Column(Modifier.weight(1f)){Text("${snap.race.city} · ${snap.race.number}. Koşu",fontWeight=FontWeight.Black);Text("${snap.race.time} · Bitti · ${snap.picks.size} at",color=Muted,fontSize=11.sp)}; Text("Kayıtlı analiz ›",color=Green,fontWeight=FontWeight.Bold,fontSize=11.sp) }
            }}
        }
    }
}

@Composable
private fun HistoryDetail(snapshot: HistorySnapshot, onBack: () -> Unit) {
    val race=snapshot.race
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,"Geri")};Column{Text("${race.city} · ${race.number}. Koşu",fontWeight=FontWeight.Black,fontSize=20.sp);Text("Yarış öncesi kaydedilmiş analiz",color=Muted,fontSize=10.sp)}}
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            item { Text("Kayıtlı sıralama",fontWeight=FontWeight.Black,fontSize=18.sp) }
            items(snapshot.picks){ HorseCard(it) }
        }
    }
}

@Composable
private fun RemainingRacesToggle(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(18.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Diğer kalan koşular", color = Ink, fontWeight = FontWeight.Black, fontSize = 14.sp)
                Text("$count yaklaşan koşu", color = Muted, fontSize = 11.sp)
            }
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Kapat" else "Aç",
                tint = Ink
            )
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
private fun TopHeader(onRefresh: () -> Unit, refreshing: Boolean, onHistory: () -> Unit, onSixli: () -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < 360
    Surface(color = Bg) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(if (compact) 48.dp else 54.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.two_horse_logo),
                    contentDescription = "Two Horse",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Two Horse", fontWeight = FontWeight.Black, fontSize = if (compact) 23.sp else 27.sp, color = Ink, maxLines = 1)
                Text(if (refreshing) "Canlı veri güncelleniyor…" else "Türkiye yarış analizi", color = Muted, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Refresh, "Yenile", tint = Ink) }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.MoreVert, "Menü", tint = Ink) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("6’lı Kupon") }, onClick = { menuOpen = false; onSixli() })
                    DropdownMenuItem(text = { Text("Geçmiş") }, onClick = { menuOpen = false; onHistory() })
                }
            }
        }
    }
}

@Composable
private fun NextRaceHero(race: Race?, nowTick: Long, onRace: (Race) -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < 360
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
                Text("${race.city} · ${race.number}. Koşu", color = Color.White, fontWeight = FontWeight.Black, fontSize = if (compact) 23.sp else 27.sp)
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
private fun UpcomingRaceCard(race: Race, nowTick: Long, expert: RaceExpertSignal?, onRace: (Race) -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < 360
    val picks = remember(race, expert) { Predictor.picks(race, expert) }
    val favorite = picks.firstOrNull()
    val secs = secondsUntil(race.time, nowTick)
    Card(
        Modifier.fillMaxWidth().clickable { onRace(race) },
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(18.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(if (compact) 62.dp else 70.dp)) {
                Text(race.time, fontWeight = FontWeight.Black, fontSize = if (compact) 16.sp else 18.sp, color = Ink)
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
        Text("Kalan diğer şehir yarışları", fontWeight = FontWeight.Black, fontSize = 17.sp, color = Ink)
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
fun RaceCard(race: Race, nowTick: Long, expert: RaceExpertSignal?, onRace: (Race) -> Unit) {
    val picks = remember(race, expert) { Predictor.picks(race, expert) }
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
fun RaceDetail(race: Race, expert: RaceExpertSignal?, expertsRefreshing: Boolean, onBack: () -> Unit) {
    val picks = remember(race, expert) { Predictor.picks(race, expert) }
    val surprise = picks.firstOrNull { it.label == "Sürpriz" } ?: picks.getOrNull(2)
    val compact = LocalConfiguration.current.screenWidthDp < 360

    BackHandler(onBack = onBack)

    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(start = if (compact) 12.dp else 18.dp, end = if (compact) 12.dp else 18.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.ArrowBack, "Geri")
                }
                Spacer(Modifier.width(2.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${race.city} · ${race.number}. Koşu · ${race.time}",
                        fontWeight = FontWeight.Black,
                        fontSize = if (compact) 18.sp else 21.sp,
                        maxLines = 2
                    )
                    Text(
                        listOf(race.distance.takeIf { it.isNotBlank() }?.plus(" m"), race.surface)
                            .filterNotNull().joinToString(" · "),
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
            }
        }
        if (picks.isNotEmpty()) {
            item { ExpertStatusCard(expert, expertsRefreshing) }
            item { ResultHero(picks.first(), picks.getOrNull(1), surprise) }
            item { SectionTitle("Olası sıralama · tüm atlar") }
            items(picks, key = { it.horse.no }) { pick -> HorseCard(pick) }
            item {
                SectionTitle("Kuponlar")
                CouponRow("Dar", picks.take(min(2, picks.size)).joinToString(" – ") { it.horse.no.toString() }, "Yüksek risk")
                Spacer(Modifier.height(8.dp))
                CouponRow("Dengeli", picks.take(min(3, picks.size)).joinToString(" – ") { it.horse.no.toString() }, "Ana senaryo")
                Spacer(Modifier.height(8.dp))
                CouponRow("Geniş", picks.take(min(5, picks.size)).joinToString(" – ") { it.horse.no.toString() }, "Sürpriz koruması")
            }
        }
    }
}

@Composable
private fun ExpertStatusCard(expert: RaceExpertSignal?, loading: Boolean) {
    val configured = expert?.configuredSourceCount ?: 7
    val reachable = expert?.reachableSourceCount ?: 0
    val usable = expert?.sourceCount ?: 0
    val fresh = expert?.freshSourceCount ?: 0
    val cached = expert?.cachedSourceCount ?: 0
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp)) {
            // Küçük telefonlarda sağdaki durum metni başlığı ezmesin: iki bağımsız satır.
            Text("Uzman verisi", fontWeight = FontWeight.Black, fontSize = 12.sp, color = Ink)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                        color = LoadingBlue
                    )
                } else {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Uzman taraması tamamlandı",
                        tint = Green,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "$reachable/$configured siteye ulaşıldı · $usable/$configured bu koşuda yorum bulundu",
                    color = if (usable > 0) Green else Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (loading) {
                Spacer(Modifier.height(3.dp))
                Text("Uzman kaynakları paralel taranıyor; sonuçlar geldikçe analiz güncellenir.", color = LoadingBlue, fontSize = 8.sp)
            }
            if (reachable > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("Yorum verisi: $fresh canlı")
                        if (cached > 0) append(" · $cached önbellekten")
                        expert?.reachableSources?.takeIf { it.isNotEmpty() }?.let { append(" · Erişilen: "); append(it.joinToString(", ")) }
                    },
                    color = Muted, fontSize = 9.sp, maxLines = 3, overflow = TextOverflow.Ellipsis
                )
                expert?.unreachableSources?.takeIf { it.isNotEmpty() }?.let { names ->
                    Spacer(Modifier.height(2.dp))
                    Text("Ulaşılamayan: ${names.joinToString(", ")}", color = Muted, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                expert?.unusableSources?.takeIf { it.isNotEmpty() }?.let { names ->
                    Spacer(Modifier.height(2.dp))
                    Text("Site açıldı, bu koşunun yorumu doğrulanamadı: ${names.joinToString(", ")}", color = Muted, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text("Uzman siteleri yanıt vermese bile TJK verisiyle analiz devam eder.", color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun ResultHero(favorite: Pick, rival: Pick?, surprise: Pick?) {
    Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("OLASI KAZANAN", color = Gold, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("#${favorite.horse.no} ${favorite.horse.name}", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
                    Text("Güven Puanı ${favorite.score}/100", color = Color.White.copy(.72f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.size(62.dp).background(Color.White.copy(.10f), CircleShape), contentAlignment = Alignment.Center) {
                    Text(favorite.score.toString(), color = Gold, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            MetricLine("AGF", favorite.agfRank?.let { "#$it · %${favorite.horse.agf ?: 0}" } ?: "Veri yok")
            MetricLine("HP", favorite.hpRank?.let { "#$it · ${favorite.horse.hp ?: 0} puan" } ?: "Veri yok")
            MetricLine(
                "Uzman desteği",
                if (favorite.expertTotal > 0) {
                    val rank = favorite.expertRank?.let { "#$it · " }.orEmpty()
                    buildString {
                        append("${rank}Destek ${favorite.expertSupport}/${favorite.expertTotal}")
                        if (favorite.expertStrong > 0) append(" · Güçlü ${favorite.expertStrong}/${favorite.expertTotal}")
                        if (favorite.expertFavoriteBanko > 0) append(" · ⭐ ${favorite.expertFavoriteBanko}/${favorite.expertTotal} Favori/Banko")
                        if (favorite.expertSurprise > 0) append(" · ${favorite.expertSurprise} sürpriz")
                        if (favorite.expertNegative > 0) append(" · ${favorite.expertNegative} olumsuz")
                    }
                } else "Kaynak bekleniyor / bulunamadı"
            )
            if (favorite.sahaNotes.isNotEmpty()) {
                MetricLine("Saha", favorite.sahaNotes.joinToString(" · "))
            }
            if (favorite.expertSources.isNotEmpty()) {
                Text("Kaynaklar: ${favorite.expertSources.joinToString(", ")}", color = Color.White.copy(.55f), fontSize = 9.sp)
            }
            MetricLine("Piyasa", favorite.marketLabel)
            MetricLine("Form", favorite.formLabel)

            if (favorite.reasons.isNotEmpty()) {
                Spacer(Modifier.height(13.dp))
                Text(favorite.reasons.joinToString(" · "), color = Color.White.copy(.76f), fontSize = 12.sp, lineHeight = 18.sp)
            }
            if (rival != null || surprise != null) {
                Spacer(Modifier.height(15.dp))
                HorizontalDivider(color = Color.White.copy(.12f))
                Spacer(Modifier.height(12.dp))
                rival?.let {
                    Text("En ciddi rakip: #${it.horse.no} ${it.horse.name} · ${it.score}/100", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                surprise?.let {
                    Spacer(Modifier.height(if (rival != null) 7.dp else 0.dp))
                    Text("💣 Sürpriz: #${it.horse.no} ${it.horse.name} · ${it.score}/100", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        val stacked = maxWidth < 330.dp || value.length > 30 || label.length > 12
        if (stacked) {
            Column(Modifier.fillMaxWidth()) {
                Text(label, color = Color.White.copy(.58f), fontSize = 10.sp, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    value,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Color.White.copy(.58f), fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Spacer(Modifier.width(10.dp))
                Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val videoSource = p.horse.videoUrl ?: p.horse.detailUrl
    var videoExpanded by remember(p.horse.no, videoSource) { mutableStateOf(false) }
    var videoLoading by remember(p.horse.no) { mutableStateOf(false) }
    var videos by remember(p.horse.no) { mutableStateOf<List<RaceVideo>>(emptyList()) }

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
                    Text("${p.score}/100", color = Green, fontWeight = FontWeight.Black, fontSize = 14.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(
                        p.horse.jockey.takeIf { it.isNotBlank() },
                        p.horse.weight?.let { "${it} kg" }
                    ).joinToString(" · "),
                    color = Muted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.background(labelBg, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 5.dp)) {
                        Text(p.label, color = labelFg, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(9.dp))
                DetailMetric("AGF", p.agfRank?.let { "#$it · %${p.horse.agf ?: 0}" } ?: "—")
                DetailMetric("HP", p.hpRank?.let { "#$it · ${p.horse.hp ?: 0}" } ?: "—")
                DetailMetric(
                    "Uzman",
                    if (p.expertTotal > 0) buildString {
                        append("${p.expertRank?.let { "#$it · " }.orEmpty()}Destek ${p.expertSupport}/${p.expertTotal}")
                        if (p.expertStrong > 0) append(" · Güçlü ${p.expertStrong}/${p.expertTotal}")
                        if (p.expertFavoriteBanko > 0) append(" · ⭐ ${p.expertFavoriteBanko}/${p.expertTotal} Favori/Banko")
                        if (p.expertSurprise > 0) append(" · ${p.expertSurprise} sürpriz")
                        if (p.expertNegative > 0) append(" · ${p.expertNegative} olumsuz")
                    } else "—"
                )
                if (p.sahaNotes.isNotEmpty()) DetailMetric("Saha", p.sahaNotes.joinToString(" · "))
                DetailMetric("Piyasa", p.marketLabel)
                DetailMetric("Form", p.formLabel)
                if (p.reasons.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(p.reasons.joinToString(" · "), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                }

                videoSource?.let { videoUrl ->
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            if (videos.isNotEmpty()) {
                                videoExpanded = !videoExpanded
                            } else if (!videoLoading) {
                                videoLoading = true
                                scope.launch {
                                    val found = VideoRepository.loadLast3(videoUrl)
                                    videos = found
                                    videoLoading = false
                                    videoExpanded = true
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp)
                    ) {
                        if (videoLoading) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                        Text("Son 3 yarış videosu", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                    if (videoExpanded) {
                        if (videos.isEmpty() && !videoLoading) {
                            Text("Bu at için oynatılabilir geçmiş yarış videosu bulunamadı.", color = Muted, fontSize = 9.sp)
                        } else {
                            videos.take(3).forEachIndexed { index, video ->
                                TextButton(
                                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.url))) } },
                                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("${index + 1}. ${video.label.ifBlank { "Geçmiş yarış" }}", fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, modifier = Modifier.width(58.dp))
        Text(value, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
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
