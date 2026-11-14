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
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

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
                        Text("Series Organizer", style = MaterialTheme.typography.headlineMedium)
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
                                    message = outcome
                                    busy = false
                                    val uri = selectedTree
                                    if (uri != null) {
                                        val result = scan(uri)
                                        items = result.first
                                        message += "\n" + result.second
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (busy) "A organizar..." else "Aplicar renomeação") }
                        }
                    }
                }
            }
        }
    }

    private fun scan(uri: Uri): Pair<List<RenameItem>, String> {
        val folder = DocumentFile.fromTreeUri(this, uri) ?: return emptyList<RenameItem>() to "Não foi possível abrir a pasta."
        val files = folder.listFiles().filter { it.isFile && it.name != null }
        val videoExt = setOf("mkv", "mp4", "avi", "mov", "m4v", "wmv", "webm", "ts")
        val subExt = setOf("srt", "ass", "ssa", "sub", "vtt")
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
                val subExt = subName.substringAfterLast('.', "").lowercase()
                subExt in subExts && parseEpisode(subName) == ep
            }
            relatedSubs.forEach { sub ->
                val old = sub.name!!
                val suffix = old.substringAfterLast('.', "")
                val stem = old.substringBeforeLast('.', old)
                val lang = Regex("""(?i)(?:^|[._ -])(pt(?:-pt)?|en|eng|es|spa|fr|fre|de|ger|it|nl|pl|ru|ja|zh)(?:$|[._ -])""")
                    .find(stem)?.groupValues?.get(1)
                val newName = if (lang != null) "$base.${lang.lowercase()}.$suffix" else "$base.$suffix"
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
        val folder = plan.firstOrNull()?.file?.parentFile ?: return "Não existem ficheiros para renomear."
        val existing = folder.listFiles().mapNotNull { it.name }.map { it.lowercase() }.toMutableSet()
        val changed = mutableListOf<Pair<DocumentFile, String>>()
        val pending = plan.filter { it.oldName != it.newName }
        if (pending.any { it.newName.lowercase() in existing && it.oldName != it.newName }) {
            return "Operação cancelada: já existem ficheiros com um dos nomes de destino. Não foi alterado nenhum ficheiro."
        }
        // Two-phase rename avoids collisions when source names already resemble destination names.
        pending.forEachIndexed { index, item ->
            val temp = ".__seriesorganizer_tmp_$index"
            if (!item.file.renameTo(temp)) {
                changed.asReversed().forEach { (file, original) -> file.renameTo(original) }
                return "Falha ao preparar a renomeação. Foi tentada a reposição dos nomes originais."
            }
            changed += item.file to item.oldName
        }
        pending.forEachIndexed { index, item ->
            if (!changed[index].first.renameTo(item.newName)) {
                changed.forEach { (file, original) -> file.renameTo(original) }
                return "Falha ao concluir a renomeação. Foi tentada a reposição dos nomes originais."
            }
        }
        return "${pending.size} ficheiros renomeados com sucesso."
    }
}
