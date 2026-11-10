// Chord-A-Licious for Android - GPL-3.0-or-later
package nz.co.chordalicious.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

fun normName(x: String) = x.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

/** A saved song whose name matches the file (or the analysed title and artist), or null. */
fun matchFavourite(favs: List<Fav>, title: String, artist: String): Fav? {
    val nf = normName(title.substringBeforeLast('.', title)); if (nf.isBlank()) return null
    val na = normName(artist)
    return favs.firstOrNull { f ->
        val nt = normName(f.title); val fa = normName(f.artist)
        nt == nf || (fa.isNotBlank() && (nf == "$fa $nt" || nf == "$nt $fa")) ||
            (nt.length >= 6 && nf.contains(nt) && (fa.isBlank() || nf.contains(fa) || (na.isNotBlank() && na == fa)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavouritesScreen(api: Api, onOpen: (Song) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var favs by remember { mutableStateOf<List<Fav>?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Fav?>(null) }
    val expanded = remember { mutableStateListOf<String>() }
    suspend fun load() { try { favs = api.favourites() } catch (e: ApiError) { msg = e.msg; favs = emptyList() } catch (e: Exception) { msg = "Couldn't reach the server."; favs = emptyList() } }
    LaunchedEffect(Unit) { load() }
    androidx.activity.compose.BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Favourites", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        msg?.let { Text(it, color = Cal.bad) }
        when {
            favs == null || busy -> Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { CircularProgressIndicator() }
            favs!!.isEmpty() -> Text("Nothing saved yet. Open a song, then Options → Song Info → Save to Favourites.", color = Cal.muted)
            else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tap a category to open it. Long-press a song to delete it.", color = Cal.muted, fontSize = 12.sp)
                favs!!.groupBy { it.category.ifBlank { "Uncategorised" } }.toSortedMap(compareBy<String> { it.lowercase() }).forEach { (cat, list) ->
                    val open = cat in expanded
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { if (open) expanded.remove(cat) else expanded.add(cat) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(if (open) "▾" else "▸", fontSize = 18.sp, color = Cal.steel, modifier = Modifier.width(24.dp))
                        Text(cat, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${list.size}", color = Cal.muted, fontSize = 14.sp)
                    }
                    if (open) list.sortedBy { it.title.lowercase() }.forEach { f ->
                        Panel(Modifier.fillMaxWidth().combinedClickable(onClick = {
                            busy = true; msg = null
                            scope.launch { try { onOpen(api.openFavourite(f.id)) } catch (e: ApiError) { msg = e.msg } catch (e: Exception) { msg = "Couldn't open that song." }; busy = false }
                        }, onLongClick = { delete = f })) {
                            Text(f.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            if (f.artist.isNotBlank()) Text(f.artist, color = Cal.muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
    delete?.let { f ->
        AlertDialog(onDismissRequest = { delete = null }, containerColor = Cal.panel,
            title = { Text("Delete “${f.title}”?") }, text = { Text("This removes the saved chords and audio copy.") },
            confirmButton = { TextButton(onClick = { delete = null; scope.launch { try { api.deleteFavourite(f.id); load() } catch (e: ApiError) { msg = e.msg } catch (e: Exception) { msg = "Couldn't delete it." } } }) { Text("Delete", color = Cal.bad) } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
}
