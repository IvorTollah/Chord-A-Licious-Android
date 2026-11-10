// Chord-A-Licious for Android - GPL-3.0-or-later
package nz.co.chordalicious.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val freeVsPro = listOf(
    Triple("Open your own audio files", "Yes", "Yes"),
    Triple("Detailed chords, tempo, left-handed", "Yes", "Yes"),
    Triple("Saved favourites", "Up to 50", "Unlimited"),
    Triple("Key change", "Yes", "Yes"),
    Triple("Capo", "–", "Yes"),
    Triple("Record yourself playing", "–", "Yes"),
    Triple("All instruments and tunings", "Guitar", "Yes"),
)

@Composable
fun WelcomeScreen(onLogin: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.badge), contentDescription = "Chord-A-Licious", modifier = Modifier.size(132.dp))
        Text("Chord-A-Licious", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Cal.text)
        Text("Play along to your favourite songs.", fontSize = 18.sp, color = Cal.steel, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)

        Panel(Modifier.fillMaxWidth()) {
            Text("Open a song from your phone and Chord-A-Licious works out its chords and lays them out bar by bar, so you can play along. It helps you learn chords and chord progressions, and helps with your timing. It's also a lot of fun.", color = Cal.text)
            Image(painterResource(R.drawable.welcome_grid), contentDescription = "Chord grid", contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
            Text("It won't teach you to solo, and it doesn't use sheet music or tablature. It's a tool for playing chords, the building block of great guitaring.", color = Cal.muted, fontSize = 13.sp)
        }

        Panel(Modifier.fillMaxWidth()) {
            Text("How it works", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("1.  Log in or create a free account", color = Cal.text)
            Text("2.  Choose an audio file on your phone", color = Cal.text)
            Text("3.  Your song is analysed on our server and the chords come back in a few seconds", color = Cal.text)
            Text("Your audio file is only used to work out the chords. Analysis needs an internet connection.", color = Cal.muted, fontSize = 13.sp)
        }

        Panel(Modifier.fillMaxWidth()) {
            Text("Free and Pro", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Every new account starts with 30 days of Pro. After that, keep Free for as long as you like, or go Pro for NZ$2 a month or NZ$20 a year.", color = Cal.muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(2.2f)); Text("Free", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Cal.muted, textAlign = TextAlign.Center)
                Text("Pro", Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Cal.steel, textAlign = TextAlign.Center)
            }
            HorizontalDivider(color = Cal.border)
            for ((what, f, p) in freeVsPro) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(what, Modifier.weight(2.2f), fontSize = 14.sp, color = Cal.text)
                    Text(f, Modifier.weight(1f), fontSize = 13.sp, color = Cal.muted, textAlign = TextAlign.Center)
                    Text(p, Modifier.weight(1f), fontSize = 13.sp, color = Cal.good, textAlign = TextAlign.Center)
                }
            }
            Text("Analysing YouTube and Spotify links is available on the website, not in this app.", color = Cal.muted, fontSize = 12.sp)
        }

        Button(modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.SERVER}/signup?src=android")))
        }) { Text("Try Pro free for 30 days", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        OutlinedButton(modifier = Modifier.fillMaxWidth().height(48.dp), onClick = onLogin) { Text("I already have an account — log in") }
        Text("Chord-A-Licious is free, open-source software (GPL-3.0). The chord analysis is provided by the Chord-A-Licious service at chordalicious.co.nz.", color = Cal.muted, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}
