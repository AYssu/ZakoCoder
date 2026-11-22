package dev.zakocoder.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.diagnostics.DoctorController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

private enum class DoctorExportStage { Idle, Preparing, Choosing, Saving, Discarding }

/** Explicit local SAF export. Only an opaque prepared-file token survives configuration changes. */
@Composable
internal fun AiDoctorSheet(doctor: DoctorController, onDismiss: () -> Unit) {
    val state by doctor.state.collectAsStateWithLifecycle()
    var stage by rememberSaveable { mutableStateOf(DoctorExportStage.Idle) }
    var token by rememberSaveable { mutableStateOf<String?>(null) }
    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    var feedback by rememberSaveable { mutableStateOf<Int?>(null) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    val busy = stage != DoctorExportStage.Idle || state.busy || closing
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (stage == DoctorExportStage.Choosing && token != null) {
            destination = uri?.toString()
            stage = if (uri == null) DoctorExportStage.Discarding else DoctorExportStage.Saving
        }
    }

    // A pending URI and token remain available after rotation; cancellation is never presented as a failure.
    LaunchedEffect(stage) {
        operationJob = currentCoroutineContext().job
        try {
            when (stage) {
                DoctorExportStage.Preparing -> {
                    token = doctor.prepare()
                    stage = DoctorExportStage.Choosing
                    save.launch("ZakoCoder-diagnostics.zip")
                }
                DoctorExportStage.Saving -> {
                    doctor.writePrepared(requireNotNull(destination).toUri(), requireNotNull(token))
                    doctor.discard(requireNotNull(token))
                    token = null
                    destination = null
                    feedback = R.string.doctor_saved
                    stage = DoctorExportStage.Idle
                }
                DoctorExportStage.Discarding -> {
                    token?.let { doctor.discard(it) }
                    token = null
                    destination = null
                    feedback = R.string.doctor_cancelled
                    stage = DoctorExportStage.Idle
                }
                else -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            token?.let { discardDoctorExport(doctor, it) }
            token = null
            destination = null
            feedback = R.string.doctor_failed
            stage = DoctorExportStage.Idle
        }
    }

    WorkbenchSheet(stringResource(R.string.doctor_title), onDismiss = {
        if (!closing) {
            closing = true
            operationJob?.cancel()
            token?.let(doctor::discardLater)
            onDismiss()
        }
    }) {
        Text(stringResource(R.string.doctor_intro), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.doctor_included), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.doctor_privacy), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.doctor_scope), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) {
            WorkbenchLinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(when (stage) {
                DoctorExportStage.Choosing -> R.string.doctor_choose_destination
                DoctorExportStage.Saving -> R.string.doctor_saving
                else -> R.string.doctor_preparing
            }), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.warningCount > 0 && state.ready) {
            Text(pluralStringResource(R.plurals.doctor_warning_count, state.warningCount, state.warningCount),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val message = feedback ?: R.string.doctor_failed.takeIf { state.error && !busy }
        message?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                color = if (it == R.string.doctor_failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WorkbenchPrimaryButton(onClick = {
            if (!busy) {
                feedback = null
                stage = DoctorExportStage.Preparing
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.doctor_export))
        }
    }
}

/** Cleanup must not trap the sheet in a busy state if a provider or temporary file is unavailable. */
private suspend fun discardDoctorExport(doctor: DoctorController, token: String) {
    try { doctor.discard(token) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { /* Remaining private staging files are reclaimed by the controller's retention policy. */ }
}
