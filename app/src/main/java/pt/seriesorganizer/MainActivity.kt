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
import androidx.compose.ui.res.stringResource
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
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                selectedTree = uri
                refreshToken++
            } catch (_: Exception) {
                statusMessage = getString(R.string.folder_permission_error)
            }
        }
    }
    private var refreshToken by mutableIntStateOf(0)
    private var statusMessage by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var plan by remember { mutableStateOf<List<RenameItem>>(emptyList()) }
                var folderFiles by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
                var message by remember { mutableStateOf(getString(R.string.select_folder_hint)) }
                var busy by remember { mutableStateOf(false) }
                var previewed by remember { mutableStateOf(false) }
                var selectedForRename by remember { mutableStateOf<DocumentFile?>(null) }
                var editedBaseName by remember { mutableStateOf("") }
                var renameError by remember { mutableStateOf<String?>(null) }
                val treeUri = selectedTree

                LaunchedEffect(refreshToken) {
                    if (treeUri != null) {
                        try {
                            val folder = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
                            folderFiles = folder?.listFiles()?.filter { it.isFile && isSupportedForManualRename(it.name) } ?: emptyList()
                            val result = scan(treeUri)
                            plan = result.first
                            message = statusMessage.ifBlank { result.second }
                            statusMessage = ""
                            previewed = true
                        } catch (_: Exception) {
                            message = getString(R.string.folder_read_error)
                        }
                    }
                }

                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.app_description))
                        Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                            Text(stringResource(R.string.select_folder))
                        }
                        Text(message, style = MaterialTheme.typography.bodyMedium)

                        if (previewed) {
                            if (folderFiles.isNotEmpty()) {
                                Text(stringResource(R.string.files_in_folder, folderFiles.size), style = MaterialTheme.typography.titleMedium)
                                LazyColumn(
                                    Modifier.weight(1f, fill = true),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    items(folderFiles, key = { it.uri.toString() }) { file ->
                                        val name = file.name.orEmpty()
                                        val extension = name.substringAfterLast('.', "")
                                        val baseName = if (extension.isNotEmpty() && name.contains('.')) name.substringBeforeLast('.') else name
                                        Card(Modifier.fillMaxWidth()) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(name, style = MaterialTheme.typography.bodySmall)
                                                }
                                                TextButton(
                                                    enabled = !busy,
                                                    onClick = {
                                                        selectedForRename = file
                                                        editedBaseName = baseName
                                                        renameError = null
                                                    }
                                                ) { Text(stringResource(R.string.rename)) }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(stringResource(R.string.no_supported_files))
                            }

                            if (plan.isNotEmpty()) {
                                Text(stringResource(R.string.auto_organization_preview, plan.size), style = MaterialTheme.typography.titleMedium)
                                LazyColumn(
                                    Modifier.weight(0.8f, fill = true),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    items(plan, key = { it.file.uri.toString() }) { item ->
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
                                        try {
                                            val outcome = applyRenames(plan)
                                            statusMessage = outcome
                                            refreshToken++
                                        } catch (_: Exception) {
                                            message = getString(R.string.rename_unexpected_error)
                                        } finally {
                                            busy = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (busy) stringResource(R.string.organizing) else stringResource(R.string.apply_rename))
                                }
                            }
                        }
                    }
                }

                if (selectedForRename != null) {
                    val file = selectedForRename!!
                    val originalName = file.name.orEmpty()
                    val extension = originalName.substringAfterLast('.', "")
                    AlertDialog(
                        onDismissRequest = { selectedForRename = null },
                        title = { Text(stringResource(R.string.rename_file_title)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = editedBaseName,
                                    onValueChange = { editedBaseName = it; renameError = null },
                                    label = { Text(stringResource(R.string.new_file_name)) },
                                    singleLine = true
                                )
                                if (extension.isNotEmpty()) {
                                    Text(stringResource(R.string.extension_kept, extension), style = MaterialTheme.typography.bodySmall)
                                }
                                if (renameError != null) {
                                    Text(renameError!!, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val base = editedBaseName.trim()
                                val targetName = if (extension.isNotEmpty()) "$base.$extension" else base
                                when {
                                    base.isBlank() -> renameError = getString(R.string.name_empty_error)
                                    base.contains('/') || base.contains(92.toChar()) -> renameError = getString(R.string.name_invalid_error)
                                    targetName.equals(originalName, ignoreCase = true) -> selectedForRename = null
                                    else -> {
                                        val parent = file.parentFile
                                        val duplicate = parent?.listFiles()?.any {
                                            it.name.equals(targetName, ignoreCase = true) && it.uri != file.uri
                                        } == true
                                        if (duplicate) {
                                            renameError = getString(R.string.name_exists_error)
                                        } else {
                                            try {
                                                val current = parent?.findFile(originalName)
                                                if (current != null && current.renameTo(targetName)) {
                                                    val verified = parent?.findFile(targetName)
                                                    if (verified != null) {
                                                        selectedForRename = null
                                                        statusMessage = getString(R.string.manual_rename_success, targetName)
                                                        refreshToken++
                                                    } else {
                                                        renameError = getString(R.string.rename_verify_error)
                                                        refreshToken++
                                                    }
                                                } else {
                                                    renameError = getString(R.string.rename_failed_error)
                                                }
                                            } catch (_: Exception) {
                                                renameError = getString(R.string.rename_failed_error)
                                            }
                                        }
                                    }
                                }
                            }) { Text(stringResource(R.string.confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { selectedForRename = null }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }
            }
        }
    }

    private fun isSupportedForManualRename(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in setOf("mkv", "mp4", "avi", "srt")
    }

    private fun scan(uri: Uri): Pair<List<RenameItem>, String> {
        val folder = DocumentFile.fromTreeUri(this, uri) ?: return emptyList<RenameItem>() to getString(R.string.folder_open_error)
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
        if (videos.isEmpty()) return emptyList<RenameItem>() to getString(R.string.no_episode_videos)
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
                val oldBase = old.substringBeforeLast('.', old)
                val languageSuffix = Regex("""(?i)S\d{1,2}[ ._-]*E\d{1,3}([._ -].*)$""")
                    .find(oldBase)?.groupValues?.getOrNull(1).orEmpty()
                plan += RenameItem(sub, old, "$base$languageSuffix.$suffix", "Subtitle")
            }
        }
        val collisions = plan.groupBy { it.newName.lowercase(Locale.ROOT) }.filterValues { it.size > 1 }.keys
        val safe = plan.filter { it.newName.lowercase(Locale.ROOT) !in collisions }
        val skipped = plan.size - safe.size
        return safe to getString(R.string.preview_status, safe.size, skipped)
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
        val first = plan.firstOrNull() ?: return getString(R.string.nothing_to_rename)
        val parent = first.file.parentFile ?: return getString(R.string.folder_open_error)
        val pending = plan.filter { it.oldName != it.newName }
        if (pending.isEmpty()) return getString(R.string.already_organized)

        val sourceNames = pending.map { it.oldName.lowercase(Locale.ROOT) }.toSet()
        val existingTargets = parent.listFiles().mapNotNull { it.name }
            .map { it.lowercase(Locale.ROOT) }.toSet() - sourceNames
        if (pending.any { it.newName.lowercase(Locale.ROOT) in existingTargets }) {
            return getString(R.string.auto_rename_conflict)
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
                return getString(R.string.auto_rename_prepare_failed, item.oldName)
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
                return getString(R.string.auto_rename_finish_failed, item.oldName)
            }
        }

        val missing = pending.filter { parent.findFile(it.newName) == null }
        return if (missing.isEmpty()) getString(R.string.auto_rename_success, pending.size)
        else getString(R.string.auto_rename_verify_failed, missing.joinToString { it.newName })
    }
}
