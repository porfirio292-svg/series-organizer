package pt.seriesorganizer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.util.Locale
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds

data class RenameItem(val file: DocumentFile, val oldName: String, val newName: String, val kind: String)
data class Episode(val season: Int, val episode: Int) : Comparable<Episode> {
    override fun compareTo(other: Episode) = compareValuesBy(this, other, Episode::season, Episode::episode)
    fun label() = String.format(Locale.US, "S%02dE%02d", season, episode)
}

class MainActivity : ComponentActivity() {
    private var selectedTree: Uri? = null
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            selectedTree = uri
            refreshToken++
        }
    }
    private var refreshToken by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileAds.initialize(this)
        setContent {
            MaterialTheme {
                var items by remember { mutableStateOf<List<RenameItem>>(emptyList()) }
                var message by remember { mutableStateOf("Seleciona a pasta que contém os episódios.") }
                var busy by remember { mutableStateOf(false) }
                var previewed by remember { mutableStateOf(false) }
                val treeUri = selectedTree
                LaunchedEffect(refreshToken) {
                    if (treeUri != null) {
                        val result = scan(treeUri)
                        items = result.first
                        message = result.second
                        previewed = true
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Kodi Series File Organizer", style = MaterialTheme.typography.headlineMedium)
                        Text("Organiza vídeos e legendas numa única pasta, usando a temporada e o episódio indicados nos nomes originais.")
                        Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Selecionar pasta") }
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        if (previewed && items.isNotEmpty()) {
                            Text("${items.size} ficheiros identificados", style = MaterialTheme.typography.titleMedium)
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(items) { item ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text(item.oldName, style = MaterialTheme.typography.bodySmall)
                                            Text("→ ${item.newName}", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                            Button(
                                enabled = !busy && items.any { it.oldName != it.newName },
                                onClick = {
                                    busy = true
                                    val outcome = applyRenames(items)
                                    val uri = selectedTree
                                    if (uri != null) {
                                        val result = scan(uri)
                                        items = result.first
                                        message = outcome
                                        if (outcome.startsWith("Concluído:")) {
                                            message += "\nValidação concluída. Os ficheiros foram novamente lidos e a aplicação está pronta para a próxima pasta."
                                        } else {
                                            message += "\n" + result.second
                                        }
                                    } else {
                                        message = outcome
                                    }
                                    busy = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "A organizar..." else "Aplicar renomeação") }
                        }
                        AndroidView(
                            factory = { context ->
                                AdView(context).apply {
                                    setAdSize(AdSize.BANNER)
                                    adUnitId = "ca-app-pub-3940256099942544/9214589741"
                                    loadAd(AdRequest.Builder().build())
                                }
                            },
                            modifier = Modifier.fillMaxWidth().wrapContentHeight()
                        )
                    }
                }
            }
        }
    }

    private fun scan(uri: Uri): Pair<List<RenameItem>, String> {
        val folder = DocumentFile.fromTreeUri(this, uri) ?: return emptyList<RenameItem>() to "Não foi possível abrir a pasta."
        val files = folder.listFiles().filter { it.isFile && it.name != null }
        val videoExt = setOf("mkv", "mp4", "avi", "mov", "m4v", "wmv", "webm", "ts")
        val subtitleExtensions = setOf("srt", "ass", "ssa", "sub", "vtt")
        val videos = files.mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            val ext = name.substringAfterLast('.', "").lowercase()
            val ep = parseEpisode(name) ?: return@mapNotNull null
            if (ext !in videoExt) return@mapNotNull null
            Triple(f, ep, ext)
        }.sortedBy { it.second }
        if (videos.isEmpty()) return emptyList<RenameItem>() to "Não encontrei vídeos com temporada/episódio reconhecíveis (ex.: S01E01 ou 1x01)."
        val plan = mutableListOf<RenameItem>()
        videos.forEach { (video, ep, ext) ->
            val base = ep.label()
            plan += RenameItem(video, video.name!!, "$base.$ext", "Vídeo")
            val relatedSubs = files.filter { sub ->
                val subName = sub.name ?: return@filter false
                val subtitleExt = subName.substringAfterLast('.', "").lowercase()
                subtitleExt in subtitleExtensions && parseEpisode(subName) == ep
            }
            relatedSubs.forEach { sub ->
                val old = sub.name!!
                val suffix = old.substringAfterLast('.', "")
                val newName = "$base.$suffix"
                plan += RenameItem(sub, old, newName, "Legenda")
            }
        }
        val collisions = plan.groupBy { it.newName.lowercase() }.filterValues { it.size > 1 }.keys
        val safe = plan.filter { it.newName.lowercase() !in collisions }
        val skipped = plan.size - safe.size
        return safe to "Pré-visualização: ${safe.size} alterações possíveis. ${if (skipped > 0) "$skipped itens ignorados por nomes repetidos." else "Sem conflitos detetados."}"
    }

    private fun parseEpisode(name: String): Episode? {
        val patterns = listOf(
            Regex("""(?i)(?:^|[^A-Za-z0-9])S(\d{1,2})[ ._-]*E(\d{1,3})(?:[^0-9]|$)"""),
            Regex("""(?i)(?:^|[^0-9])(\d{1,2})x(\d{1,3})(?:[^0-9]|$)"""),
            Regex("""(?i)season[ ._-]*(\d{1,2}).{0,20}?episode[ ._-]*(\d{1,3})""")
        )
        for (pattern in patterns) {
            val match = pattern.find(name) ?: continue
            val season = match.groupValues[1].toIntOrNull() ?: continue
            val episode = match.groupValues[2].toIntOrNull() ?: continue
            if (season in 0..99 && episode in 0..999) return Episode(season, episode)
        }
        return null
    }

    private fun applyRenames(plan: List<RenameItem>): String {
        val first = plan.firstOrNull() ?: return "Não existem ficheiros para renomear."
        val parent = first.file.parentFile ?: return "Não foi possível aceder à pasta."
        val pending = plan.filter { it.oldName != it.newName }
        if (pending.isEmpty()) return "Os ficheiros já estão organizados."

        val sourceNames = pending.map { it.oldName.lowercase(Locale.ROOT) }.toSet()
        val existingTargets = parent.listFiles().mapNotNull { it.name }
            .map { it.lowercase(Locale.ROOT) }.toSet() - sourceNames
        if (pending.any { it.newName.lowercase(Locale.ROOT) in existingTargets }) {
            return "Operação cancelada: já existe um ficheiro com um dos nomes de destino. Não foi alterado nenhum ficheiro."
        }

        // DocumentFile pode conservar um URI antigo após renameTo. Reobter sempre o ficheiro pela pasta.
        val temporaryNames = pending.indices.map { index ->
            val ext = pending[index].oldName.substringAfterLast(".", "")
            if (ext.isNotEmpty()) ".__kodi_series_tmp_$index.$ext" else ".__kodi_series_tmp_$index"
        }
        val staged = mutableListOf<Pair<String, String>>()
        pending.forEachIndexed { index, item ->
            val current = parent.findFile(item.oldName)
            if (current == null || !current.renameTo(temporaryNames[index])) {
                staged.asReversed().forEach { (temp, original) -> parent.findFile(temp)?.renameTo(original) }
                return "Não foi possível preparar a renomeação de ${item.oldName}. Foi tentada a reposição dos nomes originais."
            }
            staged += temporaryNames[index] to item.oldName
        }

        pending.forEachIndexed { index, item ->
            val tempName = temporaryNames[index]
            val current = parent.findFile(tempName)
            if (current == null || !current.renameTo(item.newName)) {
                staged.asReversed().forEach { (temp, original) ->
                    val stagedFile = parent.findFile(temp)
                    if (stagedFile != null) stagedFile.renameTo(original)
                    else parent.findFile(pending.first { it.oldName == original }.newName)?.renameTo(original)
                }
                return "Não foi possível concluir a renomeação de ${item.oldName}. Foi tentada a reposição dos nomes originais."
            }
        }

        val missing = pending.filter { parent.findFile(it.newName) == null }
        return if (missing.isEmpty()) "Concluído: ${pending.size} ficheiros renomeados e verificados com sucesso."
        else "Atenção: a operação terminou, mas não foi possível validar: ${missing.joinToString { it.newName }}."
    }
}
