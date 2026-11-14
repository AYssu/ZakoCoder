package dev.zakocoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.ai.AiController
import dev.zakocoder.app.ai.AiOnlineSkillController
import dev.zakocoder.app.ai.AiOnlineSkills
import dev.zakocoder.app.ai.AiSkillArchive
import dev.zakocoder.app.ai.OnlineSkillCatalog
import dev.zakocoder.app.ai.OnlineSkillProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class OnlineSkillDocumentPreview(val name: String, val path: String, val text: String)
private data class OnlineSkillReview(val documents: List<OnlineSkillDocumentPreview>, val licenseText: String? = null, val failed: Boolean = false)
private data class OnlineSkillTextReview(val title: String, val path: String, val text: String)

/** No networking in composition: all work is owned by the short-lived import controller. */
@Composable
internal fun AiOnlineSkillImport(ai: AiController, providedController: AiOnlineSkillController? = null, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val defaultController = remember(ai, scope) { AiOnlineSkillController(AiOnlineSkills(), scope) { archive, id ->
        ai.saveGlobalSkill("", "", "", imported = true, operationId = id, archive = archive)
    } }
    val controller = providedController ?: defaultController
    val state by controller.state.collectAsStateWithLifecycle()
    val global by ai.globalSkills.collectAsStateWithLifecycle()
    val task by ai.state.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var ref by rememberSaveable { mutableStateOf("") }
    var understood by remember(state.prepared) { mutableStateOf(false) }
    var reading by remember(state.prepared) { mutableStateOf<OnlineSkillTextReview?>(null) }
    // Reuse only the pinned, verified archive. Decoding never blocks composition or fetches more data.
    val review by produceState<OnlineSkillReview?>(null, state.prepared) {
        value = null
        val prepared = state.prepared ?: return@produceState
        value = try {
            withContext(Dispatchers.Default) {
                val documents = AiSkillArchive.parse(prepared.bytes).mapIndexed { index, skill ->
                    val origin = JSONObject(AiSkillArchive.decode(skill.files.getValue("ZAKOCODER_ORIGIN.json")))
                    val directory = origin.getString("directory")
                    val file = skill.files.keys.single { it.substringAfterLast('/').equals("SKILL.md", true) }
                    OnlineSkillDocumentPreview(prepared.names[index], listOf(directory, file).filter(String::isNotEmpty).joinToString("/"), skill.document)
                }
                OnlineSkillReview(documents, prepared.source.licenseBytes?.toString(Charsets.UTF_8))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { OnlineSkillReview(emptyList(), failed = true) }
    }
    val editable = !task.active && !task.restoring && !global.active && !global.skillsLoading
    DisposableEffect(controller) { onDispose { controller.cancel() } }
    AiFullScreen(stringResource(R.string.online_skills_title), { if (!state.installing) { controller.cancel(); close() } },
        animateExit = !state.installing) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("intro") { Text(stringResource(R.string.online_skills_intro), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item("source") {
                WorkbenchCard {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        WorkbenchTextField(url, { url = it.take(1200); controller.reset() }, Modifier.fillMaxWidth(), enabled = !state.busy,
                            label = stringResource(R.string.online_skills_url), singleLine = true,
                            placeholder = { Text(stringResource(R.string.online_skills_url_example)) })
                        WorkbenchTextField(ref, { ref = it.take(240); controller.reset() }, Modifier.fillMaxWidth(), enabled = !state.busy,
                            label = stringResource(R.string.online_skills_ref), singleLine = true)
                        Text(stringResource(R.string.online_skills_ref_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { controller.discover(url, ref) }, enabled = url.isNotBlank() && !state.busy && editable,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.online_skills_discover)) }
                    }
                }
            }
            if (state.loading || state.preparing) item("progress") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(if (state.loading) R.string.online_skills_loading else R.string.online_skills_downloading,
                        state.completed, state.total), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = controller::cancel) { Text(stringResource(R.string.online_skills_cancel)) }
                }
            }
            state.problem?.let { problem -> item("problem") {
                WorkbenchCard(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(stringResource(onlineSkillProblemLabel(problem)), Modifier.fillMaxWidth().padding(16.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } }
            state.catalog?.let { catalog ->
                item("origin") { OnlineSkillOrigin(catalog) }
                item("list-heading") { Text(stringResource(R.string.online_skills_candidates, catalog.candidates.size, state.selected.size),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.prepared == null) items(catalog.candidates, key = { it.document }) { candidate ->
                    WorkbenchCard(onClick = { controller.select(candidate.directory, candidate.directory !in state.selected) }, enabled = !state.busy && editable) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val selectionLabel = stringResource(R.string.online_skills_select, candidate.label)
                            WorkbenchCheckbox(candidate.directory in state.selected, { controller.select(candidate.directory, it) },
                                enabled = !state.busy && editable, modifier = Modifier.semantics { contentDescription = selectionLabel })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(candidate.label, style = MaterialTheme.typography.titleSmall)
                                Text(candidate.document, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.online_skills_file_count, candidate.fileCount, (candidate.bytes + 1023) / 1024),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            state.prepared?.let { prepared ->
                item("confirmation") {
                    WorkbenchCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.online_skills_review_title), style = MaterialTheme.typography.titleSmall)
                            if (review == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (review?.failed == true) Text(stringResource(R.string.online_skills_preview_error),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            review?.documents?.forEach { document ->
                                TextButton(onClick = { reading = OnlineSkillTextReview(document.name, document.path, document.text) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.online_skills_preview_document, document.name))
                                }
                            }
                            Text(stringResource(R.string.online_skills_preview_plaintext), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            review?.licenseText?.let { license ->
                                TextButton(onClick = { reading = OnlineSkillTextReview("", prepared.source.licenseFile.orEmpty(), license) },
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.online_skills_preview_license)) }
                            }
                            Text(stringResource(R.string.online_skills_review_hint, prepared.names.size, (prepared.bytes.size + 1023) / 1024),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WorkbenchCheckbox(understood, { understood = it }, enabled = !state.busy && review?.failed == false)
                                Text(stringResource(R.string.online_skills_acknowledge), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (state.installed) item("installed") { Text(stringResource(R.string.online_skills_installed),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        }
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            WorkbenchPrimaryButton(onClick = {
                when { state.installed -> close(); state.prepared != null -> controller.install(); else -> controller.prepare() }
            }, enabled = !state.busy && editable && (state.installed || if (state.prepared != null) understood && review?.failed == false else state.selected.isNotEmpty() && state.selected.size <= 32),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(when { state.installed -> R.string.online_skills_done; state.installing -> R.string.online_skills_installing
                    state.prepared != null -> R.string.online_skills_install; else -> R.string.online_skills_prepare }))
            }
        }
    }
    reading?.let { document -> state.prepared?.source?.let { source ->
        OnlineSkillTextPreview(document, source) { reading = null }
    } }
}

/** Plain text deliberately does not render remote Markdown assets or execute links/scripts. */
@Composable
private fun OnlineSkillTextPreview(document: OnlineSkillTextReview, source: OnlineSkillCatalog, close: () -> Unit) {
    val chunks = remember(document.text) { buildList {
        var start = 0
        while (start < document.text.length) {
            var end = minOf(start + 2000, document.text.length)
            if (end < document.text.length) {
                val newline = document.text.lastIndexOf('\n', end - 1)
                if (newline >= start) end = newline + 1
                else if (document.text[end - 1].isHighSurrogate() && document.text[end].isLowSurrogate()) end--
            }
            add(document.text.substring(start, end))
            start = end
        }
    } }
    AiFullScreen(stringResource(if (document.title.isBlank()) R.string.online_skills_preview_license else R.string.online_skills_preview_title), close) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("origin") {
                SelectionContainer {
                    WorkbenchCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (document.title.isNotBlank()) Text(document.title, style = MaterialTheme.typography.titleSmall)
                            Text(document.path, style = MaterialTheme.typography.bodySmall)
                            Text(source.source.repositoryUrl, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.online_skills_commit, source.commit), style = MaterialTheme.typography.bodySmall)
                            Text(if (source.license == null) stringResource(R.string.online_skills_license_unknown)
                                else stringResource(R.string.online_skills_license, source.license), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item("notice") { Text(stringResource(R.string.online_skills_preview_notice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(chunks.size, key = { "text-$it" }) { index ->
                SelectionContainer { Text(chunks[index], style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun OnlineSkillOrigin(catalog: OnlineSkillCatalog) {
    WorkbenchCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(catalog.source.repositoryUrl, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.online_skills_commit, catalog.commit.take(12)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (catalog.license == null) stringResource(R.string.online_skills_license_unknown)
                else stringResource(R.string.online_skills_license, catalog.license),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun onlineSkillProblemLabel(problem: OnlineSkillProblem) = when (problem) {
    OnlineSkillProblem.InvalidUrl -> R.string.online_skills_error_url
    OnlineSkillProblem.NotFound -> R.string.online_skills_error_not_found
    OnlineSkillProblem.RateLimited -> R.string.online_skills_error_rate_limit
    OnlineSkillProblem.Network -> R.string.online_skills_error_network
    OnlineSkillProblem.TooLarge -> R.string.online_skills_error_large
    OnlineSkillProblem.InvalidRepository -> R.string.online_skills_error_repository
    OnlineSkillProblem.UnsafeResource -> R.string.online_skills_error_unsafe
    OnlineSkillProblem.InvalidSkill -> R.string.online_skills_error_skill
    OnlineSkillProblem.Selection -> R.string.online_skills_error_selection
    OnlineSkillProblem.Install -> R.string.online_skills_error_install
}
