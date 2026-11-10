// Chord-A-Licious for Android - GPL-3.0-or-later. The analysis runs on the Chord-A-Licious server.
package nz.co.chordalicious.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class Me(val loggedIn: Boolean, val email: String = "", val tier: String = "", val full: Boolean = false, val daysLeft: Int = 0, val ends: Long = 0, val tracks: Int = 0)
data class Bar(val num: Int, val chord: String, val detail: String?, val start: Double, val end: Double = 0.0)
data class Lyric(val t: Double, val text: String)
data class Song(val title: String, val artist: String, val key: String, val bpm: Double, val bars: List<Bar>,
                val audioUrl: String = "", val duration: Double = 0.0, val lyrics: List<Lyric> = emptyList(),
                val artUrl: String = "", val peaks: List<Float> = emptyList(), val favId: String = "", val favCat: String = "", val recording: Boolean = false,
                val linkUrl: String = "")
class Tpl(val mode: String, val i: Int, val sig: String, val inst: String, val tun: String, val name: String)
data class Fav(val id: String, val title: String, val artist: String, val category: String, val hasAudio: Boolean)
class ApiError(val msg: String, val upgrade: Boolean = false, val login: Boolean = false) : IOException(msg)

/** Keeps the server's session cookie between app starts (private app storage). */
class PrefsCookieJar(ctx: Context) : CookieJar {
    private val ownHost = BuildConfig.SERVER.removePrefix("https://").substringBefore("/")
    private val p = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val e = p.edit()
        for (c in cookies) if (c.name == "cal_session") { if (c.expiresAt < System.currentTimeMillis()) e.remove("tok") else e.putString("tok", c.value) }
        e.apply()
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (url.host != ownHost) return emptyList()          // never send the session to another site (album art etc.)
        val t = p.getString("tok", null) ?: return emptyList()
        return listOf(Cookie.Builder().name("cal_session").value(t).domain(url.host).path("/").secure().httpOnly().build())
    }
    fun clear() = p.edit().clear().apply()
}

class Api(private val ctx: Context, private val base: String = BuildConfig.SERVER) {
    private val jar = PrefsCookieJar(ctx)
    private val http = OkHttpClient.Builder().cookieJar(jar)
        .addInterceptor { c -> c.proceed(c.request().newBuilder().header("X-Client", "android").build()) }   // so the server's statistics can tell the app from the website
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()

    private fun detail(r: Response): ApiError {
        val body = r.body?.string().orEmpty()
        val msg = try { JSONObject(body).optString("detail") } catch (_: Exception) { "" }
        return ApiError(msg.ifBlank { "The server said ${r.code}." }, upgrade = r.code == 402 || r.code == 403, login = r.code == 401)
    }

    suspend fun me(): Me = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$base/me").build()).execute().use { r ->
            if (!r.isSuccessful) throw detail(r)
            val j = JSONObject(r.body!!.string())
            if (!j.optBoolean("logged_in")) Me(false)
            else Me(true, j.optString("email"), j.optString("tier"), j.optBoolean("full"), j.optInt("days_left"), j.optLong("ends"), j.optJSONObject("tracks")?.optInt("balance") ?: 0)
        }
    }

    suspend fun login(email: String, password: String) = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("email", email).add("password", password).add("next", "/me").build()
        http.newCall(Request.Builder().url("$base/login").post(form).build()).execute().use { r ->
            if (r.code == 401) throw ApiError("That e-mail and password don't match.")
            if (r.code == 403) throw ApiError("Please confirm your e-mail first (check your inbox), then log in.")
            if (r.code == 429) throw ApiError("Too many attempts. Please wait a few minutes.")
            if (!r.isSuccessful) throw detail(r)
        }
        if (!me().loggedIn) throw ApiError("Log in failed. Please try again.")
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        try { http.newCall(Request.Builder().url("$base/logout").post(FormBody.Builder().build()).build()).execute().close() } catch (_: Exception) {}
        jar.clear()
    }

    /** Uploads the chosen audio file and returns the chords. */
    suspend fun analyse(uri: Uri): Song = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        var name = "song.mp3"
        cr.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) } }
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw ApiError("Couldn't read that file.")
        upload(bytes, name, false)
    }

    /** The person's own playing, recorded on the phone: sent as a recording (Pro). */
    suspend fun analyseRecording(file: File, title: String): Song = withContext(Dispatchers.IO) {
        val clean = title.replace(Regex("[^A-Za-z0-9 _()'-]"), "").trim().ifBlank { "My recording" }
        upload(file.readBytes(), "$clean.m4a", true).copy(recording = true)
    }

    @Volatile private var activeCall: okhttp3.Call? = null
    @Volatile private var cancelled = false
    private fun call0Cancelled() = activeCall?.isCanceled() == true
    /** Stops an analysis that is in progress (the Cancel button on the listening screen). */
    fun cancelAnalysis() { cancelled = true; activeCall?.cancel() }

    private suspend fun upload(bytes: ByteArray, name: String, recording: Boolean): Song {
        cancelled = false
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody("application/octet-stream".toMediaType())).build()
        // A dropped connection (a stale one reused from the pool, or a hiccup on the way up) is retried on a fresh connection, as the website does.
        var attempt = 0
        while (true) {
            try {
                http.connectionPool.evictAll()
                val req = Request.Builder().url("$base/predict-chords?sevenths=true" + if (recording) "&recording=true" else "").header("Connection", "close").post(body).build()
                val call = http.newCall(req); activeCall = call
                return call.execute().use { r ->
                    if (!r.isSuccessful) throw detail(r)
                    parse(JSONObject(r.body!!.string()))
                }
            } catch (e: ApiError) { throw e
            } catch (e: IOException) { if (cancelled || call0Cancelled()) throw kotlinx.coroutines.CancellationException("Cancelled"); if (++attempt > 2) throw e; delay(500L * attempt) }
        }
    }

    fun displayName(uri: Uri): String {
        var name = ""
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i) ?: "" } }
        return name
    }

    // ---- Chord templates ("always play this chord like this"), kept on the account and shared with the website
    suspend fun templates(): Map<String, Tpl> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$base/me/templates").build()).execute().use { r ->
            if (!r.isSuccessful) throw detail(r)
            val o = JSONObject(r.body!!.string()).optJSONObject("templates") ?: return@use emptyMap<String, Tpl>()
            o.keys().asSequence().associateWith { k -> val v = o.getJSONObject(k); Tpl(v.optString("mode"), v.optInt("i"), v.optString("sig"), v.optString("inst"), v.optString("tun"), v.optString("name")) }
        }
    }

    suspend fun saveTemplates(m: Map<String, Tpl>) = withContext(Dispatchers.IO) {
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k, JSONObject().put("mode", v.mode).put("i", v.i).put("sig", v.sig).put("inst", v.inst).put("tun", v.tun).put("name", v.name)) }
        val body = JSONObject().put("templates", o).toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/me/templates").put(body).build()).execute().use { r -> if (!r.isSuccessful) throw detail(r) }
    }

    /** Asks before each print: Free accounts get a few a day. Returns null when allowed, else the reason. */
    suspend fun usePrint(): String? = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$base/me/print").post("".toRequestBody(null)).build()).execute().use { r ->
            if (!r.isSuccessful) throw detail(r)
            val j = JSONObject(r.body!!.string())
            if (j.optBoolean("ok", true)) null else "Free accounts can print ${j.optInt("limit", 3)} times a day. Go Pro for unlimited printing."
        }
    }

    /** A YouTube link to a song someone has already analysed on the website: its saved chords come back (no listening or analysing here). Null when nobody has analysed it yet. */
    suspend fun liveCache(link: String): Song? = withContext(Dispatchers.IO) {
        val id = Regex("(?:youtu\\.be/|[?&]v=|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})").find(link.trim())?.groupValues?.get(1)
            ?: throw ApiError("Paste a YouTube link to the song.")
        liveCacheKey("yt:$id", "https://www.youtube.com/watch?v=$id")
    }

    /** The saved analysis for a recognition-database key ("yt:ID", "up:HASH", ...), or null when there is none. */
    suspend fun liveCacheKey(key: String, ytLink: String = ""): Song? = withContext(Dispatchers.IO) {
        val u = "$base/live-cache".toHttpUrl().newBuilder().addQueryParameter("key", key).build()
        val j = http.newCall(Request.Builder().url(u).build()).execute().use { r ->
            if (!r.isSuccessful) throw detail(r)
            JSONObject(r.body!!.string())
        }
        if (!j.optBoolean("hit")) return@withContext null
        if (j.optString("key").isBlank()) j.put("key", j.optString("key_name"))
        if (ytLink.isNotBlank()) j.put("yt_link", ytLink)
        try {                                                   // lyrics and artwork are fetched fresh, as the website does (never stored)
            val u = "$base/live-info".toHttpUrl().newBuilder().addQueryParameter("title", j.optString("title")).addQueryParameter("artist", j.optString("artist"))
                .addQueryParameter("duration", j.optDouble("duration", 0.0).toString()).build()
            http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                if (r.isSuccessful) { val k = JSONObject(r.body!!.string()); j.put("lyric_lines", k.optJSONArray("lyric_lines") ?: org.json.JSONArray()); if (k.optString("art_url").isNotBlank()) j.put("art_url", k.optString("art_url")) }
            }
        } catch (_: Exception) {}
        parse(j)
    }

    /** A few seconds heard through the microphone (16-bit mono PCM at 22050 Hz): is it a song the server already knows? */
    class Ident(val match: Boolean, val key: String, val title: String, val artist: String, val busy: Boolean = false)
    suspend fun identify(pcm: ByteArray): Ident = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/identify").post(pcm.toRequestBody("application/octet-stream".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            if (r.code == 503) return@use Ident(false, "", "", "", busy = true)
            if (!r.isSuccessful) throw detail(r)
            val j = JSONObject(r.body!!.string())
            Ident(j.optBoolean("match"), j.optString("key"), j.optString("title"), j.optString("artist"))
        }
    }

    // ---- Favourites (same endpoints as the website)
    suspend fun favourites(): List<Fav> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$base/favourites").build()).execute().use { r ->
            if (!r.isSuccessful) throw detail(r)
            val a = JSONObject(r.body!!.string()).optJSONArray("favourites") ?: return@use emptyList<Fav>()
            (0 until a.length()).map { val o = a.getJSONObject(it); Fav(o.optString("id"), o.optString("title"), o.optString("artist"), o.optString("category"), o.optBoolean("has_audio")) }
        }
    }

    suspend fun openFavourite(id: String): Song = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$base/favourites/$id").build()).execute().use { r ->
            if (!r.isSuccessful) throw detail(r)
            val j = JSONObject(r.body!!.string())
            parse(j).copy(favId = id, favCat = j.optString("category"))
        }
    }

    suspend fun deleteFavourite(id: String) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$base/favourites/$id").delete().build()).execute().use { r -> if (!r.isSuccessful) throw detail(r) }
    }

    /** Saves the song as it is now (edits included). Passing an existing id replaces that favourite. Returns its id. */
    suspend fun saveFavourite(song: Song, bars: List<Bar>, chords: List<String>, edited: Set<Int>, category: String, id: String, share: Boolean = false): String = withContext(Dispatchers.IO) {
        val ms = org.json.JSONArray()
        bars.forEachIndexed { i, b ->
            val o = JSONObject().put("num", i + 1).put("chord", chords[i]).put("start", b.start).put("end", if (b.end > b.start) b.end else (bars.getOrNull(i + 1)?.start ?: song.duration))
                .put("edited", i in edited)
            if (b.detail != null && i !in edited) o.put("detail", JSONObject().put("chord", b.detail))
            ms.put(o)
        }
        val pk = org.json.JSONArray(); song.peaks.forEach { pk.put(it.toDouble()) }
        val j = JSONObject().put("id", id).put("category", category).put("title", song.title).put("artist", song.artist)
            .put("duration", song.duration).put("bpm", song.bpm).put("key_text", if (song.key.isBlank()) "" else "Key: " + song.key).put("peaks", pk).put("measures", ms)
            .put("audio_id", song.audioUrl.substringAfterLast('/')).put("yt_link", song.linkUrl).put("share", share && !song.recording).put("kind", if (song.recording) "recording" else "")
        if (song.artUrl.startsWith("https://") || song.artUrl.startsWith("data:image/")) j.put("art_url", song.artUrl)
        http.newCall(Request.Builder().url("$base/favourites").post(j.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) throw detail(r)
            JSONObject(r.body!!.string()).optString("id")
        }
    }

    private fun parse(j: JSONObject): Song {
        val ms = j.getJSONArray("measures")
        val bars = (0 until ms.length()).map { i ->
            val m = ms.getJSONObject(i)
            val d = m.optJSONObject("detail")?.optString("chord", "")?.takeIf { it.isNotBlank() }
            Bar(m.optInt("num", i + 1), m.optString("chord", "?"), d, m.optDouble("start", 0.0), m.optDouble("end", 0.0))
        }
        val pk = j.optJSONArray("peaks")
        val peaks = if (pk == null) emptyList() else (0 until pk.length()).map { pk.optDouble(it, 0.0).toFloat() }
        val ll = j.optJSONArray("lyric_lines")
        val lyrics = if (ll == null) emptyList() else (0 until ll.length()).mapNotNull { k ->
            val o = ll.optJSONObject(k) ?: return@mapNotNull null
            val t = o.optString("text", ""); if (t.isBlank()) null else Lyric(o.optDouble("t", 0.0), t)
        }
        return Song(j.optString("title").ifBlank { j.optString("filename") }, j.optString("artist"), j.optString("key").ifBlank { Regex("([A-G][#b]?\\s+(Major|Minor))", RegexOption.IGNORE_CASE).find(j.optString("key_text"))?.value ?: "" }, j.optDouble("bpm", 0.0), bars,
                    j.optString("audio_url", ""), j.optDouble("duration", 0.0), lyrics, j.optString("art_url", ""), peaks, recording = j.optString("kind") == "recording",
                    linkUrl = j.optString("yt_link", "").takeIf { it.startsWith("http") } ?: "")

    }

    /** Asks the server for a one-time link; the caller opens it in the browser (Stripe checkout). */
    suspend fun checkoutUrl(plan: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("plan", plan).toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/mobile/handoff").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw detail(r)
            JSONObject(r.body!!.string()).getString("url")
        }
    }

    /** Downloads the server's temporary playback copy of the analysed song (needs the signed-in session). */
    suspend fun downloadAudio(path: String): File = withContext(Dispatchers.IO) {
        ctx.cacheDir.listFiles { x -> x.name.startsWith("playback_") }?.forEach { it.delete() }
        val f = File(ctx.cacheDir, "playback_${System.nanoTime()}.audio")
        http.newCall(Request.Builder().url(base + path).build()).execute().use { r ->
            if (!r.isSuccessful) throw detail(r)
            r.body!!.byteStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        }
        f
    }

    private val plain = OkHttpClient()
    /** Album art: the server sends a data: URL or an https link. Other sites never get the session cookie. */
    suspend fun loadArt(url: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (url.startsWith("data:")) {
                val b = android.util.Base64.decode(url.substringAfter("base64,"), android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)
            } else if (url.startsWith("https://")) {
                plain.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body!!.byteStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                }
            } else null
        } catch (e: Exception) { null }
    }
}
