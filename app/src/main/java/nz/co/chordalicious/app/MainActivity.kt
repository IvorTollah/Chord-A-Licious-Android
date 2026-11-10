// Chord-A-Licious for Android - GPL-3.0-or-later
package nz.co.chordalicious.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.rememberLazyGridState

class MainActivity : ComponentActivity() {
    private val refresh = mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val api = Api(applicationContext)
        setContent { CalTheme { Surface(Modifier.fillMaxSize(), color = Cal.bg) { Box(Modifier.fillMaxSize().safeDrawingPadding()) { App(api, refresh.intValue) } } } }
    }
    // Stripe -> browser -> chordalicious://billing-done lands here: re-check the account.
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); refresh.intValue++ }
}

@Composable
fun App(api: Api, refreshTick: Int) {
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<Me?>(null) }
    var song by remember { mutableStateOf<Song?>(null) }
    val recents = remember { mutableStateListOf<Song>() }          // songs analysed or opened in this session
    val openSong = { s: Song -> recents.removeAll { it.title == s.title && it.artist == s.artist }; recents.add(0, s); while (recents.size > 8) recents.removeAt(recents.size - 1); song = s }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE) }
    var welcome by remember { mutableStateOf(!prefs.getBoolean("welcomed", false)) }
    LaunchedEffect(refreshTick) { me = try { api.me() } catch (_: Exception) { Me(false) } }
    when {
        me == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        !me!!.loggedIn && welcome -> WelcomeScreen { prefs.edit().putBoolean("welcomed", true).apply(); welcome = false }
        !me!!.loggedIn -> LoginScreen(api, onAbout = { welcome = true }) { scope.launch { me = try { api.me() } catch (_: Exception) { Me(false) } } }
        song != null -> ResultScreen(api, song!!, full = me!!.full) { song = null }
        else -> HomeScreen(api, me!!, recents, onSong = openSong, onLogout = { scope.launch { api.logout(); me = Me(false) } })
    }
}

@Composable
fun Header(sub: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Image(painterResource(R.drawable.badge), contentDescription = "Chord-A-Licious", modifier = Modifier.size(64.dp))
        Column {
            Text("Chord-A-Licious", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Cal.text)
            if (sub != null) Text(sub, fontSize = 13.sp, color = Cal.muted)
        }
    }
}

@Composable
fun Pill(text: String, color: androidx.compose.ui.graphics.Color = Cal.steel, modifier: Modifier = Modifier, fill: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent, onClick: (() -> Unit)? = null) {
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(50)).background(fill).border(1.dp, color, RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(horizontal = 8.dp, vertical = 5.dp))
}

@Composable
fun LoginScreen(api: Api, onAbout: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope(); val ctx = androidx.compose.ui.platform.LocalContext.current
    var email by remember { mutableStateOf("") }; var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        Header("Chords for the music you love")
        Spacer(Modifier.height(20.dp))
        Panel(Modifier.fillMaxWidth()) {
            Text("Log in", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            err?.let { Text(it, color = Cal.bad) }
            Button(enabled = !busy && email.isNotBlank() && pw.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true; err = null
                scope.launch { try { api.login(email.trim(), pw); onDone() } catch (e: ApiError) { err = e.msg } catch (e: Exception) { err = "Couldn't reach the server." }; busy = false }
            }) { Text(if (busy) "Logging in…" else "Log in") }
        }
        // Sign-up and password reset stay on the website (e-mail confirmation happens there)
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.SERVER}/signup?src=android"))) }) { Text("Create a free account (30-day Pro trial)") }
        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.SERVER}/forgot"))) }) { Text("Forgot your password?") }
        TextButton(onClick = onAbout) { Text("About Chord-A-Licious") }
    }
}

@Composable
fun HomeScreen(api: Api, me: Me, recents: List<Song>, onSong: (Song) -> Unit, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope(); val ctx = androidx.compose.ui.platform.LocalContext.current
    var busy by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf<String?>(null) }
    var quip by remember { mutableStateOf("") }
    LaunchedEffect(busy) { if (busy) { val order = ROCK_QUIPS.shuffled(); var k = 0; while (true) { quip = order[k++ % order.size]; delay(3200) } } }
    var showFavs by remember { mutableStateOf(false) }
    var showRec by remember { mutableStateOf(false) }
    // recognise a song playing nearby: listen through the microphone and ask the server's recognition database
    var listening by remember { mutableStateOf(false) }; var heardS by remember { mutableIntStateOf(0) }
    var idJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun startRecognise() {
        msg = null; listening = true; heardS = 0
        idJob = scope.launch {
            var rec: android.media.AudioRecord? = null
            try {
                val sr = 22050
                val minBuf = android.media.AudioRecord.getMinBufferSize(sr, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                val mic = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, sr, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr * 4))
                rec = mic
                if (mic.state != android.media.AudioRecord.STATE_INITIALIZED) throw ApiError("Couldn't start the microphone.")
                mic.startRecording()
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(sr / 5 * 2)
                var nextTry = 6; var working = false; var found: Api.Ident? = null; var failure: String? = null
                while (found == null && failure == null && out.size() < sr * 2 * 30) {
                    val n = withContext(Dispatchers.IO) { mic.read(buf, 0, buf.size) }
                    if (n > 0) out.write(buf, 0, n)
                    val secs = out.size() / (sr * 2); heardS = secs
                    if (secs >= nextTry && !working) {
                        nextTry = secs + 4; working = true
                        val snap = out.toByteArray()
                        launch { try { val h = api.identify(snap); if (h.match) found = h } catch (e: ApiError) { failure = e.msg } catch (_: Exception) {} finally { working = false } }
                    }
                }
                try { mic.stop() } catch (_: Exception) {}
                val hit = found
                if (failure != null) msg = failure
                else if (hit == null) msg = "Couldn't recognise that song. It may not be in the Chord-A-Licious recognition database yet. Try again closer to the music, or without other noise."
                else {
                    val s = api.liveCacheKey(hit.key, if (hit.key.startsWith("yt:")) "https://www.youtube.com/watch?v=" + hit.key.removePrefix("yt:") else "")
                    if (s == null) msg = "Recognised ${hit.title}, but its saved chords are no longer available." else onSong(s)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: ApiError) { msg = e.msg
            } catch (e: Exception) { msg = "Couldn't listen (" + (e.message ?: e.javaClass.simpleName) + ")." }
            finally { try { rec?.release() } catch (_: Exception) {}; listening = false }
        }
    }
    val askMicId = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startRecognise() else msg = "Chord-A-Licious needs the microphone to recognise a song. You can allow it in the phone's settings."
    }
    var showLink by remember { mutableStateOf(false) }         // open a song from a YouTube link (only songs already analysed on the website)
    var showImport by remember { mutableStateOf(false) }       // the Import a Song chooser: audio file or YouTube link
    var analysing by remember { mutableStateOf(false) }        // the full-screen "listening" scene
    var analyseJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var linkText by remember { mutableStateOf("") }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }       // a file whose name matches a saved favourite
    var hit by remember { mutableStateOf<Fav?>(null) }
    var after by remember { mutableStateOf<Song?>(null) }           // an analysis whose title matches a saved favourite
    val msgErr = { e: Exception -> if (e is ApiError) e.msg else "That didn't work (" + e.javaClass.simpleName + ": " + (e.message ?: "no details") + "). Check your connection and try again." }
    fun analyse(uri: Uri, replace: String) {
        busy = true; msg = null; analysing = true
        analyseJob = scope.launch {
            try {
                val song = api.analyse(uri)
                if (replace.isNotEmpty()) { onSong(song.copy(favId = replace)) }
                else {
                    val h = try { matchFavourite(api.favourites(), song.title, song.artist) } catch (_: Exception) { null }
                    if (h != null) { after = song; hit = h } else onSong(song)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { msg = msgErr(e) }
            busy = false; analysing = false
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true; msg = null
            scope.launch {
                // before any analysis is run: is this file already one of the person's favourites?
                val h = try { matchFavourite(api.favourites(), api.displayName(uri), "") } catch (_: Exception) { null }
                busy = false
                if (h != null) { pendingUri = uri; hit = h } else analyse(uri, "")
            }
        }
    }
    if (showRec) { RecordScreen(api, onSong = { showRec = false; onSong(it) }, onBack = { showRec = false }); return }
    if (showFavs) { FavouritesScreen(api, onOpen = { onSong(it) }, onBack = { showFavs = false }); return }
    hit?.let { h ->
        AlertDialog(onDismissRequest = { hit = null; pendingUri = null; after = null }, containerColor = Cal.panel,
            title = { Text("Already in your Favourites") },
            text = { Text("“${h.title}”" + (if (h.artist.isNotBlank()) " by ${h.artist}" else "") + " is saved. Open the saved copy, or analyse the song again and replace it?") },
            confirmButton = { Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { hit = null; pendingUri = null; after = null; busy = true; scope.launch { try { onSong(api.openFavourite(h.id)) } catch (e: Exception) { msg = msgErr(e) }; busy = false } }) { Text("Open saved copy") }
                TextButton(onClick = { val u = pendingUri; val a = after; hit = null; pendingUri = null; after = null
                    if (a != null) onSong(a.copy(favId = h.id, favCat = h.category)) else if (u != null) analyse(u, h.id) }) { Text(if (after != null) "Use the new analysis" else "Analyse again & replace") }
                TextButton(onClick = { hit = null; pendingUri = null; after = null }) { Text("Cancel") }
            } })
    }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        var account by remember { mutableStateOf(false) }
        var plans by remember { mutableStateOf(false) }
        BrandPill()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(me.email, fontSize = 13.sp, color = Cal.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
            val orange = androidx.compose.ui.graphics.Color(0xFFFB923C)
            when (me.tier) {
                "pro" -> Pill("PRO", orange, Modifier.width(84.dp), fill = androidx.compose.ui.graphics.Color(0x55FB923C)) { plans = true }
                "trial" -> Pill("TRIAL · ${me.daysLeft}d", Cal.good, Modifier.width(112.dp)) { plans = true }
                else -> Pill("FREE", Cal.muted, Modifier.width(84.dp)) { plans = true }
            }
            OutlinedButton(onClick = { account = true }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.width(96.dp).height(32.dp)) { Text("Account", fontSize = 13.sp) }
        }
        if (account) AccountDialog(api, me) { account = false }
        if (showLink) AlertDialog(onDismissRequest = { showLink = false }, containerColor = Cal.panel,
            title = { Text("Open a song from a link") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste a YouTube link. If someone has already analysed that song on the website, its chords open here. New songs are analysed on a computer, in Chrome or Edge.", fontSize = 13.sp, color = Cal.muted)
                OutlinedTextField(value = linkText, onValueChange = { linkText = it }, singleLine = true, placeholder = { Text("https://youtu.be/…") }, modifier = Modifier.fillMaxWidth()) } },
            confirmButton = { TextButton(enabled = linkText.isNotBlank(), onClick = {
                showLink = false; busy = true; msg = null
                scope.launch {
                    try { val s = api.liveCache(linkText); if (s == null) msg = "That song hasn't been analysed by Chord-A-Licious yet and is not currently usable in our mobile app. Paste this song into chordalicious.co.nz in your desktop browser and it will become available to play in the app" else onSong(s) }
                    catch (e: Exception) { msg = msgErr(e) }
                    busy = false
                } }) { Text("Open song") } },
            dismissButton = { TextButton(onClick = { showLink = false }) { Text("Cancel") } })
        if (showImport) androidx.compose.ui.window.Dialog(onDismissRequest = { showImport = false }) {
            val shape = RoundedCornerShape(28.dp)
            Column(Modifier.fillMaxWidth().shadow(20.dp, shape, ambientColor = androidx.compose.ui.graphics.Color(0xFF19FF5C), spotColor = Cal.steel).clip(shape)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF17264A), androidx.compose.ui.graphics.Color(0xFF0A1222))))
                .border(2.5.dp, androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color(0xFF19FF5C), Cal.steel, androidx.compose.ui.graphics.Color(0xFFA78BFA))), shape).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                GlowText("Import a song", 26.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (kind in 0..1) {
                        val tint = if (kind == 0) androidx.compose.ui.graphics.Color(0xFF38BDF8) else androidx.compose.ui.graphics.Color(0xFFFF3B3B)
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(tint.copy(alpha = 0.12f))
                            .border(2.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0.35f))), RoundedCornerShape(22.dp))
                            .clickable { showImport = false; if (kind == 0) pick.launch(arrayOf("audio/*")) else { linkText = ""; msg = null; showLink = true } }.padding(vertical = 20.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.height(78.dp), Alignment.Center) { ImportChoiceIcon(kind, 1.6f) }
                            GlowText(if (kind == 0) "Audio file" else "YouTube link", 15.sp, align = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
                TextButton(onClick = { showImport = false }) { GlowText("Cancel", 16.sp, weight = FontWeight.SemiBold) }
            }
        }
        if (listening) ListeningOverlay("Listening to the room… $heardS s\nHold your phone close to the music") {
            idJob?.cancel(); idJob = null; listening = false
        }
        if (analysing) ListeningOverlay(quip) {
            api.cancelAnalysis(); analyseJob?.cancel(); analyseJob = null; analysing = false; busy = false; msg = "Cancelled."
        }
        if (plans) PlansDialog(me) { plans = false }
        Panel(Modifier.fillMaxWidth()) {
            Text("Song selection", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Cal.muted, letterSpacing = 1.sp)
            Tile3D({ msg = null; showImport = true }, Modifier.fillMaxWidth(), enabled = !busy) {
                Column(Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ImportTriptych(1.5f)
                    GlowText("Import a Song", 16.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile3D({ if (me.full) showRec = true else msg = "Recording yourself is part of Pro." }, Modifier.weight(1f).height(98.dp), enabled = !busy) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlowIcon { c, s -> MicGlyph(c, s) }; Text("Record yourself" + if (me.full) "" else " 🔒", fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFF1F5F9), fontWeight = FontWeight.SemiBold) } }
                Tile3D({ showFavs = true }, Modifier.weight(1f).height(98.dp), enabled = !busy) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlowIcon { c, s -> HeartGlyph(c, s) }; Text("Favourites", fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFF1F5F9), fontWeight = FontWeight.SemiBold) } }
            }
            Tile3D({
                if (!me.full) msg = "Recognising a song is part of Pro."
                else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startRecognise()
                else askMicId.launch(android.Manifest.permission.RECORD_AUDIO)
            }, Modifier.fillMaxWidth().height(66.dp), enabled = !busy && !listening) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlowIcon(30.dp) { c, s -> EarGlyph(c, false, s) }
                    Text("Recognise a Song" + if (me.full) "" else " 🔒", fontSize = 15.sp, color = androidx.compose.ui.graphics.Color(0xFFF1F5F9), fontWeight = FontWeight.SemiBold)
                }
            }
            msg?.let { Text(it, color = Cal.bad) }
        }
        if (recents.isNotEmpty()) Panel(Modifier.fillMaxWidth(), pad = 12.dp, space = 6.dp) {
            Text("Recent songs this session", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Cal.muted, letterSpacing = 1.sp)
            recents.forEach { r ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !busy) { onSong(r) }.padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("♪", color = Cal.steel, fontSize = 16.sp, modifier = Modifier.width(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (r.artist.isNotBlank()) Text(r.artist, color = Cal.muted, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
        ChordOfTheMoment(Modifier.fillMaxWidth())
        if (!me.full) {
            Panel(Modifier.fillMaxWidth()) {
                Text("Go Pro", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Unlimited songs and every feature.", color = Cal.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((plan, label) in listOf("monthly" to "Monthly", "annual" to "Annual")) Button(onClick = {
                        scope.launch { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(api.checkoutUrl(plan)))) } catch (e: ApiError) { msg = e.msg } catch (e: Exception) { msg = "Couldn't start the payment." } }
                    }) { Text(label) }
                }
                Text("Payment is taken securely by Stripe in your browser; you'll come back here when it's done.", fontSize = 12.sp, color = Cal.muted)
            }
        }
        TextButton(onClick = onLogout) { Text("Log out") }
    }
}

/** Plan, when it ends, and the APRA AMCOS tracks left (the website is where links are used and tracks bought). */
@Composable
fun AccountDialog(api: Api, shown: Me, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var me by remember { mutableStateOf(shown) }
    LaunchedEffect(Unit) { try { me = api.me() } catch (_: Exception) {} }
    val date = if (me.ends > 0) java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(me.ends * 1000)) else ""
    AlertDialog(onDismissRequest = onClose, containerColor = Cal.panel, title = { Text("Your account") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(me.email, color = Cal.muted)
            when (me.tier) {
                "pro" -> { Text("Plan: Pro", fontWeight = FontWeight.SemiBold); if (date.isNotEmpty()) Text("Your subscription runs until $date (${me.daysLeft} days).") }
                "trial" -> { Text("Plan: Pro free trial", fontWeight = FontWeight.SemiBold); Text("${me.daysLeft} days left" + if (date.isNotEmpty()) " — it ends on $date." else ".") }
                else -> { Text("Plan: Free", fontWeight = FontWeight.SemiBold); Text("Go Pro for every feature and unlimited songs.", color = Cal.muted) }
            }
            Text("APRA AMCOS tracks left: ${me.tracks}", fontWeight = FontWeight.SemiBold)
            Text("Streaming links are used on the website; tracks are bought there too.", color = Cal.muted, fontSize = 12.sp)
        } },
        confirmButton = { TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.SERVER}/account"))) }) { Text("Manage on the website") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } })
}


/** Free versus Pro, the same table as the website's Free and Pro section. */
@Composable
fun PlansDialog(me: Me, onClose: () -> Unit) {
    val rows = listOf(
        Triple("Analyse streaming links*", "Pay per track***", "Pay per track***"),
        Triple("Record yourself playing", "Not included", "Included"),
        Triple("Saved favourites", "Up to 50", "Unlimited"),
        Triple("Print or save as PDF", "10 a day", "Unlimited"),
        Triple("Instruments", "Guitar", "Guitar, ukulele, banjo, mandolin, bass, keys"),
        Triple("Key change", "Included**", "Included**"),
        Triple("Tempo control", "Included", "Included"),
        Triple("Left-handed chords", "Included", "Included"),
        Triple("Capo", "Not included", "Included"),
        Triple("Tunings", "Standard only", "10 different tunings"),
        Triple("Chord shapes", "Open and barre", "Open, barre, triad, power"),
        Triple("Simplify", "Always on", "Switch on or off"),
        Triple("Edit chords in the grid", "Unlimited, open and barre shapes only", "Unlimited"),
        Triple("Edit a chord's finger shape", "Unlimited, open and barre shapes only", "Unlimited, all shapes"),
        Triple("Slow a measure", "One per song", "Any measures"),
        Triple("Drag and loop", "Included", "Included"),
        Triple("Metronome", "Not included", "Included"),
        Triple("Lyric alignment", "Not included", "Included"),
        Triple("Busker mode", "Included", "Included"))
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Cal.bg).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Text("Free and Pro", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Cal.text)
            Text("Every new account starts with 30 free days of Pro. After that, keep Free for as long as you like, or subscribe to Pro for NZ$2 a month or NZ$20 a year.", color = Cal.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Spacer(Modifier.weight(1.1f)); Text("Free", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Cal.muted); Text("Pro", Modifier.weight(1.2f), fontWeight = FontWeight.Bold, color = Cal.good)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                rows.forEach { (a, f, p) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(a, Modifier.weight(1.1f).padding(end = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Cal.text)
                        Text(f, Modifier.weight(1f).padding(end = 6.dp), fontSize = 13.sp, color = Cal.muted)
                        Text(p, Modifier.weight(1.2f), fontSize = 13.sp, color = Cal.text)
                    }
                    androidx.compose.material3.HorizontalDivider(color = Cal.border)
                }
                Text("* Only YouTube and Spotify links work, on the website. ** Key changes aren't available for song links. *** Streaming links use APRA AMCOS tracks, on the website; uploading your own audio is always free.", fontSize = 11.sp, color = Cal.muted, modifier = Modifier.padding(top = 10.dp))
            }
            Text(when (me.tier) { "pro" -> "You're on Pro."; "trial" -> "You're on the Pro trial: ${me.daysLeft} days left."; else -> "You're on the Free version." }, color = Cal.steel, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Close") }
        }
    }
}
