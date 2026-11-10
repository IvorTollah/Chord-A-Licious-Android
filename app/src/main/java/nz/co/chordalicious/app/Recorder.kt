// Chord-A-Licious for Android - GPL-3.0-or-later
package nz.co.chordalicious.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Record yourself playing (one chord at a time), then have it analysed like any other song. Pro. */
@Composable
fun RecordScreen(api: Api, onSong: (Song) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("idle") }          // idle, recording, recorded, working
    var secs by remember { mutableIntStateOf(0) }
    var level by remember { mutableFloatStateOf(0f) }
    var msg by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("My recording") }
    var playing by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    val file = remember { File(ctx.cacheDir, "my_recording.m4a") }

    fun stopPlayer() { try { player?.release() } catch (_: Exception) {}; player = null; playing = false }
    fun stopRecorder(keep: Boolean) {
        try { recorder?.stop() } catch (_: Exception) { if (keep) msg = "That recording was too short." }
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
    }
    DisposableEffect(Unit) { onDispose { stopPlayer(); try { recorder?.release() } catch (_: Exception) {} } }
    androidx.activity.compose.BackHandler { if (state == "recording") { stopRecorder(false); state = "idle" } else onBack() }

    fun start() {
        msg = null; stopPlayer()
        try {
            @Suppress("DEPRECATION")
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1); r.setAudioSamplingRate(44100); r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(file.absolutePath)
            r.prepare(); r.start()
            recorder = r; secs = 0; state = "recording"
        } catch (e: Exception) { msg = "Couldn't start recording (" + (e.message ?: e.javaClass.simpleName) + ")."; state = "idle" }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) start() else msg = "Chord-A-Licious needs the microphone to record you. You can allow it in the phone's app settings." }
    LaunchedEffect(state) {
        while (state == "recording") {
            delay(100)
            val amp = try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
            level = (amp / 20000f).coerceIn(0f, 1f)
            if (++tick % 10 == 0) secs++
            if (secs >= 600) { stopRecorder(true); state = "recorded" }      // ten minutes is plenty
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (state == "recording") { stopRecorder(false); state = "idle" }; stopPlayer(); onBack() }) { Text("‹ Back") }
            Text("Record yourself", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Panel(Modifier.fillMaxWidth()) {
            Text("Play one chord at a time and let each ring for a moment. A quiet room, a steady tempo and a clean (unprocessed) sound give the best results.", color = Cal.muted, fontSize = 13.sp)
            val clock = "%d:%02d".format(secs / 60, secs % 60)
            Text(when (state) { "recording" -> "Recording…  $clock"; "recorded" -> "Recorded  $clock"; "working" -> "Listening to your playing…"; else -> "Ready" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (state == "recording") LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth(), color = Cal.good, trackColor = Cal.cardActive)
            if (state == "working") LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cal.steel, trackColor = Cal.cardActive)
            msg?.let { Text(it, color = Cal.bad) }
            when (state) {
                "idle" -> Button(modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start() else askMic.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("● Start recording") }
                "recording" -> Button(modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Cal.bad), onClick = {
                    stopRecorder(true); state = if (file.exists() && file.length() > 2000) "recorded" else "idle"
                }) { Text("■ Stop") }
                "recorded" -> {
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        if (playing) stopPlayer() else try {
                            val mp = MediaPlayer(); mp.setDataSource(file.absolutePath); mp.prepare(); mp.setOnCompletionListener { playing = false }; mp.start(); player = mp; playing = true
                        } catch (e: Exception) { msg = "Couldn't play that back." }
                    }) { Text(if (playing) "■ Stop playback" else "▶ Listen back") }
                    OutlinedTextField(title, { title = it }, label = { Text("Name for this recording") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                        stopPlayer(); state = "working"; msg = null
                        scope.launch {
                            try { onSong(api.analyseRecording(file, title)) }
                            catch (e: ApiError) { msg = e.msg; state = "recorded" }
                            catch (e: Exception) { msg = "That didn't work (" + (e.message ?: e.javaClass.simpleName) + "). Check your connection and try again."; state = "recorded" }
                        }
                    }) { Text("Analyse recording") }
                    TextButton(onClick = { stopPlayer(); secs = 0; state = "idle" }) { Text("Record again") }
                }
            }
        }
    }
}

private var tick = 0
