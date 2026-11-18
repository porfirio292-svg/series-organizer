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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class RenameItem(val file: DocumentFile, val oldName: String, val newName: String, val kind: String)
data class Episode(val season: Int, val episode: Int) : Comparable<Episode> {
    override fun compareTo(other: Episode) = compareValuesBy(this, other, Episode::season, Episode::episode)
    fun label() = String.format(Locale.US, "S%02dE%02d", season, episode)
}

class MainActivity : ComponentActivity() {
    private var selectedTree: Uri? = null
    private var refreshToken by mutableIntStateOf(0)
    private val isPortuguesePortugal: Boolean
        get() {
            val locale = Locale.getDefault()
            return locale.language.equals("pt", true) && locale.country.equals("PT", true)
        }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            selectedTree = uri
            refreshToken++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var plan by remember { mutableStateOf<List<RenameItem>>(emptyList()) }
                var manualFiles by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
                var message by remember { mutableStateOf(if (isPortuguesePortugal) "Seleciona a pasta que contém os episódios." else "Select the folder containing your episodes.") }
                var busy by remember { mutableStateOf(false) }
                var previewed by remember { mutableStateOf(false) }
                var editingFile by remember { mutableStateOf<DocumentFile?>(null) }
                var editedBaseName by remember { mutableStateOf("") }
                val pt = isPortuguesePortugal
                val treeUri = selectedTree

                LaunchedEffect(refreshToken) {
                    if (treeUri != null) {
                        busy = true
                        val result = withContext(Dispatchers.IO) {
                            scan(treeUri) to listManualFiles(treeUri)
                        }
                        plan = result.first.first
                        message = result.first.second
                        manualFiles = result.second
                        previewed = true
                        busy = false
                    }
                }

                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Kodi Series File Organizer", style = MaterialTheme.typography.headlineSmall)
                        Text(if (pt) "Organiza automaticamente os vídeos e as legendas por temporada e episódio. Também podes alterar manualmente os nomes dos ficheiros." else "Automatically organize videos and subtitles by season and episode. You can also rename files manually.")
                        Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                            Text(if (pt) "Selecionar pasta" else "Select folder")
                        }
                        Text(message, style = MaterialTheme.typography.bodyMedium)

                        if (previewed) {
                            if (plan.isNotEmpty()) {
                                Text(if (pt) "${plan.size} ficheiros identificados para organização" else "${plan.size} files identified for organization", style = MaterialTheme.typography.titleMedium)
                                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    items(plan) { item ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(9.dp)) {
                                                Text(item.oldName, style = MaterialTheme.typography.bodySmall)
                                                Text("→ ${item.newName}", style = MaterialTheme.typography.bodyMedium)
                                            }
                                        }
                                    }
                                }
                                Button(
                                    enabled = !busy && plan.any { it.oldName != it.newName },
                                    onClick = {
                                        busy = true
                                        lifecycleScope.launch {
                                            val outcome = withContext(Dispatchers.IO) { applyRenames(plan) }
                                            val uri = selectedTree
                                            val refreshed = if (uri != null) withContext(Dispatchers.IO) { scan(uri) to listManualFiles(uri) } else null
                                            if (refreshed != null) {
                                                plan = refreshed.first.first
                                                manualFiles = refreshed.second
                                            }
                                            message = outcome + if (outcome.startsWith(if (pt) "Concluído:" else "Completed:")) {
                                                if (pt) "\nValidação concluída. A aplicação está pronta para continuar." else "\nValidation complete. The app is ready to continue."
                                            } else ""
                                            busy = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (busy) (if (pt) "A organizar..." else "Organizing...") else (if (pt) "Aplicar renomeação" else "Apply renaming")) }
                            } else {
                                Text(if (pt) "Não existem alterações automáticas a aplicar." else "There are no automatic changes to apply.")
                            }

                            Text(if (pt) "Renomeação manual" else "Manual renaming", style = MaterialTheme.typography.titleMedium)
                            Text(if (pt) "Vídeos MKV, MP4 e AVI e legendas SRT. A extensão é sempre preservada." else "MKV, MP4 and AVI videos and SRT subtitles. The file extension is always preserved.")
                            if (manualFiles.isEmpty()) {
                                Text(if (pt) "Não foram encontrados ficheiros compatíveis nesta pasta." else "No supported files were found in this folder.")
                            } else {
                                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    items(manualFiles, key = { it.uri.toString() }) { file ->
                                        val name = file.name ?: ""
                                        Card(Modifier.fillMaxWidth()) {
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                                TextButton(onClick = {
                                                    val ext = name.substringAfterLast('.', "")
                                                    editingFile = file
                                                    editedBaseName = if (ext.isNotEmpty()) name.removeSuffix(".$ext") else name
                                                }, enabled = !busy) { Text(if (pt) "Alterar" else "Rename") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (editingFile != null) {
                    val file = editingFile!!
                    val oldName = file.name ?: ""
                    val extension = oldName.substringAfterLast('.', "")
                    val hasExtension = oldName.contains('.') && extension.isNotEmpty()
                    AlertDialog(
                        onDismissRequest = { editingFile = null },
                        title = { Text(if (pt) "Renomear ficheiro" else "Rename file") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = editedBaseName, onValueChange = { editedBaseName = it }, singleLine = true, label = { Text(if (pt) "Novo nome" else "New name") })
                                if (hasExtension) Text(if (pt) "Extensão preservada: .$extension" else "Extension will be preserved: .$extension")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val cleanBase = editedBaseName.trim()
                                val newName = if (hasExtension) "$cleanBase.$extension" else cleanBase
                                if (cleanBase.isEmpty()) {
                                    message = if (pt) "O nome não pode ficar vazio." else "The name cannot be empty."
                                } else if (newName.equals(oldName, true)) {
                                    editingFile = null
                                } else {
                                    busy = true
                                    lifecycleScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            val parent = file.parentFile
                                            val duplicate = parent?.listFiles()?.any { it.uri != file.uri && it.name.equals(newName, true) } == true
                                            when {
                                                duplicate -> if (pt) "Já existe um ficheiro com esse nome." else "A file with that name already exists."
                                                parent == null -> if (pt) "Não foi possível aceder à pasta." else "Could not access the folder."
                                                file.renameTo(newName) -> if (pt) "Ficheiro renomeado com sucesso." else "File renamed successfully."
                                                else -> if (pt) "Não foi possível renomear o ficheiro." else "Could not rename the file."
                                            }
                                        }
                                        message = result
                                        val uri = selectedTree
                                        if (uri != null) manualFiles = withContext(Dispatchers.IO) { listManualFiles(uri) }
                                        editingFile = null
                                        busy = false
                                    }
                                }
                            }) { Text(if (pt) "Guardar" else "Save") }
                        },
                        dismissButton = { TextButton(onClick = { editingFile = null }) { Text(if (pt) "Cancelar" else "Cancel") } }
                    )
                }
            }
        }
    }

    private fun listManualFiles(uri: Uri): List<DocumentFile> {
        val folder = DocumentFile.fromTreeUri(this, uri) ?: return emptyList()
        val supported = setOf("mkv", "mp4", "avi", "srt")
        return folder.listFiles().filter { file ->
            file.isFile && file.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) in supported
        }.sortedBy { it.name?.lowercase(Locale.ROOT) }
    }

    private fun scan(uri: Uri): Pair<List<RenameItem>, String> {
        val folder = DocumentFile.fromTreeUri(this, uri) ?: return emptyList<RenameItem>() to msg("Could not open the folder.", "Não foi possível abrir a pasta.")
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
        if (videos.isEmpty()) return emptyList<RenameItem>() to msg("No videos with a recognizable season/episode pattern were found (e.g. S01E01 or 1x01).", "Não encontrei vídeos com temporada/episódio reconhecíveis (ex.: S01E01 ou 1x01).")
        val plan = mutableListOf<RenameItem>()
        videos.forEach { (video, ep, ext) ->
            val base = ep.label()
            plan += RenameItem(video, video.name!!, "$base.$ext", "Video")
            val relatedSubs = files.filter { sub ->
                val subName = sub.name ?: return@filter false
                val subtitleExt = subName.substringAfterLast('.', "").lowercase()
                subtitleExt in subtitleExtensions && parseEpisode(subName) == ep
            }
            relatedSubs.forEach { sub ->
                val old = sub.name!!
                val suffix = old.substringAfterLast('.', "")
                plan += RenameItem(sub, old, "$base.$suffix", "Subtitle")
            }
        }
        val collisions = plan.groupBy { it.newName.lowercase(Locale.ROOT) }.filterValues { it.size > 1 }.keys
        val safe = plan.filter { it.newName.lowercase(Locale.ROOT) !in collisions }
        val skipped = plan.size - safe.size
        val status = if (isPortuguesePortugal) {
            "Pré-visualização: ${safe.size} alterações possíveis. ${if (skipped > 0) "$skipped itens ignorados por nomes repetidos." else "Sem conflitos detetados."}"
        } else {
            "Preview: ${safe.size} possible changes. ${if (skipped > 0) "$skipped items skipped due to duplicate target names." else "No conflicts detected."}"
        }
        return safe to status
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
        val pending = plan.filter { it.oldName != it.newName }
        if (pending.isEmpty()) return msg("There are no files to rename.", "Não existem ficheiros para renomear.")
        val parent = pending.first().file.parentFile ?: return msg("Could not access the folder.", "Não foi possível aceder à pasta.")
        val sourceNames = pending.map { it.oldName.lowercase(Locale.ROOT) }.toSet()
        val existingTargets = parent.listFiles().mapNotNull { it.name }.map { it.lowercase(Locale.ROOT) }.toSet() - sourceNames
        if (pending.any { it.newName.lowercase(Locale.ROOT) in existingTargets }) {
            return msg("Operation cancelled: a destination filename already exists. No files were changed.", "Operação cancelada: já existe um ficheiro com um dos nomes de destino. Não foi alterado nenhum ficheiro.")
        }
        val temporaryNames = pending.indices.map { index ->
            val ext = pending[index].oldName.substringAfterLast(".", "")
            if (ext.isNotEmpty()) ".__kodi_series_tmp_$index.$ext" else ".__kodi_series_tmp_$index"
        }
        val staged = mutableListOf<Pair<String, String>>()
        pending.forEachIndexed { index, item ->
            val current = parent.findFile(item.oldName)
            if (current == null || !current.renameTo(temporaryNames[index])) {
                staged.asReversed().forEach { (temp, original) -> parent.findFile(temp)?.renameTo(original) }
                return msg("Could not prepare the rename of ${item.oldName}. Original names were restored where possible.", "Não foi possível preparar a renomeação de ${item.oldName}. Foi tentada a reposição dos nomes originais.")
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
                return msg("Could not complete the rename of ${item.oldName}. Original names were restored where possible.", "Não foi possível concluir a renomeação de ${item.oldName}. Foi tentada a reposição dos nomes originais.")
            }
        }
        val missing = pending.filter { parent.findFile(it.newName) == null }
        return if (missing.isEmpty()) {
            if (isPortuguesePortugal) "Concluído: ${pending.size} ficheiros renomeados e verificados com sucesso."
            else "Completed: ${pending.size} files renamed and verified successfully."
        } else {
            msg("Warning: could not validate: ${missing.joinToString { it.newName }}.", "Atenção: não foi possível validar: ${missing.joinToString { it.newName }}.")
        }
    }

    private fun msg(en: String, pt: String) = if (isPortuguesePortugal) pt else en
}
