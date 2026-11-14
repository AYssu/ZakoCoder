package dev.zakocoder.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import top.yukonga.miuix.kmp.anim.folmeSpring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zakocoder.app.R
import dev.zakocoder.app.Workbench
import dev.zakocoder.app.WorkbenchState
import dev.zakocoder.app.ai.AiBuiltinSkills
import dev.zakocoder.app.runtime.BundleStatus
import dev.zakocoder.app.runtime.ToolBundles
import dev.zakocoder.app.screentest.ScreenTestAccess
import dev.zakocoder.app.settings.AptSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Optional setup. All work goes through existing controllers; grants are always explicit. */
@Composable
internal fun OnboardingPage(workbench: Workbench, state: WorkbenchState, onComplete: () -> Unit,
    onExit: () -> Unit = {}, startFromBeginning: Boolean = false) {
    val purposeKeys = listOf("web", "scripts", "android", "native", "assistant")
    var step by rememberSaveable { mutableIntStateOf(if (startFromBeginning) 0 else state.onboarding.step) }
    var purpose by rememberSaveable { mutableIntStateOf(purposeKeys.indexOf(state.onboarding.purpose).coerceAtLeast(0)) }
    var selectedPackages by rememberSaveable { mutableStateOf(
        if (state.onboarding.purpose.isBlank()) "zsh,git,node" else state.onboarding.selectedBundleIds.joinToString(",")) }
    var detail by rememberSaveable { mutableStateOf("") }
    var entering by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exitProgress = remember { Animatable(0f) }
    val scrollStates = List(7) { rememberScrollState() }
    val models by workbench.ai.models.state.collectAsStateWithLifecycle()
    val globalSkills by workbench.ai.globalSkills.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { workbench.ai.refreshGlobalSkills() }
    val screen by workbench.screenTests.state.collectAsStateWithLifecycle()
    var rootBusy by remember { mutableStateOf(false) }
    var rootError by remember { mutableStateOf("") }
    val rootDenied = stringResource(R.string.screen_test_root_denied)
    val rootFailed = stringResource(R.string.screen_test_operation_failed)
    val titles = listOf(R.string.setup_purpose_title, R.string.setup_environment_title,
        R.string.setup_model_title, R.string.setup_skills_title, R.string.setup_packages_title,
        R.string.setup_permissions_title, R.string.setup_ready_title)
    val hints = listOf(R.string.setup_purpose_hint, R.string.setup_environment_hint,
        R.string.setup_model_hint, R.string.setup_skills_hint, R.string.setup_packages_hint,
        R.string.setup_permissions_hint, R.string.setup_ready_hint)
    val progress by animateFloatAsState((step + 1) / 7f, animationSpec = tween(360, easing = FastOutSlowInEasing), label = "setup-progress")
    LaunchedEffect(step) { workbench.saveOnboardingStep(step) }
    LaunchedEffect(purpose, selectedPackages, globalSkills.enabledSkills, globalSkills.skillsInitialized, globalSkills.skillsLoading, globalSkills.skillError) {
        if (!globalSkills.skillsInitialized || globalSkills.skillsLoading || globalSkills.skillError) return@LaunchedEffect
        workbench.saveOnboardingRecommendations(purposeKeys[purpose],
            selectedPackages.split(',').filter(String::isNotBlank).toSet(),
            globalSkills.enabledSkills.filter(AiBuiltinSkills::contains).toSet())
    }
    LaunchedEffect(entering) {
        if (entering) {
            exitProgress.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
            onComplete()
            // Persistence is owned by Workbench. Its success callback closes this page;
            // a failed save must leave a usable retry button beside the actual error.
            entering = false
        } else exitProgress.animateTo(0f, tween(180))
    }
    val detailTransition = updateTransition(if (detail == "tools") "permissions" else detail, label = "setup-detail")
    val detailVisible = detailTransition.currentState.isNotEmpty() || detailTransition.targetState.isNotEmpty()
    BackHandler(enabled = state.busy || entering || (detailVisible && detail.isEmpty())) {
        if (!state.busy && entering) entering = false
    }
    val stepBack = rememberWorkbenchBackMotion(
        enabled = !detailVisible && !state.busy && !entering && (step > 0 || state.onboarding.completed),
        resetKey = step,
    ) {
        if (step > 0) step-- else onExit()
    }
    // Keep the setup page and its scroll positions alive while a configuration page covers it.
    // The previous step is presentation only; all persistence stays in the effects above.
    val renderStep: @Composable (Int, Boolean) -> Unit = { displayedStep, interactive ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().graphicsLayer {
            alpha = 1f - exitProgress.value * 0.45f
            scaleX = 1f - exitProgress.value * 0.025f
            scaleY = scaleX
        }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (displayedStep > 0) IconButton(onClick = { if (interactive) stepBack.close() }, enabled = !entering && !state.busy) {
                    WorkbenchIcon(Glyph.Back, description = stringResource(R.string.setup_previous))
                } else Spacer(Modifier.size(48.dp))
                Text(stringResource(R.string.setup_brand), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (state.onboarding.completed) TextButton(onClick = { if (interactive) onExit() }, enabled = !state.busy && !entering) {
                    Text(stringResource(R.string.setup_exit))
                }
            }
            if (state.messageIsError && state.message.isNotBlank()) Text(state.message,
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LinearProgressIndicator(progress = { if (interactive) progress else (displayedStep + 1) / 7f }, modifier = Modifier.weight(1f).height(3.dp),
                    drawStopIndicator = {})
                Text(stringResource(R.string.setup_step, displayedStep + 1, 7), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(scrollStates[displayedStep]).padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(titles[displayedStep]), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(hints[displayedStep]), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (displayedStep) {
                    0 -> {
                        val names = listOf(R.string.setup_use_web, R.string.setup_use_scripts, R.string.setup_use_android,
                            R.string.setup_use_native, R.string.setup_use_general)
                        val descriptions = listOf(R.string.setup_use_web_hint, R.string.setup_use_scripts_hint,
                            R.string.setup_use_android_hint, R.string.setup_use_native_hint, R.string.setup_use_general_hint)
                        names.forEachIndexed { index, title ->
                            SetupChoice(stringResource(title), stringResource(descriptions[index]), purpose == index) {
                                purpose = index
                                selectedPackages = when (index) {
                                    0 -> "zsh,git,node"; 1 -> "zsh,git,python"; 2 -> "zsh,git,jdk,android"
                                    3 -> "zsh,git,cpp"; else -> "zsh,git"
                                }
                            }
                        }
                        Text(stringResource(R.string.setup_purpose_no_install), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    1 -> {
                        Text(stringResource(R.string.apt_source), style = MaterialTheme.typography.titleSmall)
                        AptSource.entries.forEach { source ->
                            SetupChoice(stringResource(if (source == AptSource.Tsinghua) R.string.apt_tsinghua else R.string.apt_official),
                                stringResource(if (source == AptSource.Tsinghua) R.string.setup_source_tsinghua else R.string.setup_source_official),
                                source == state.settings.aptSource, enabled = !state.busy) { workbench.saveSettings(state.settings.copy(aptSource = source)) }
                        }
                        Text(stringResource(R.string.setup_source_scope), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SetupSummary(stringResource(R.string.ubuntu_version), stringResource(if (state.installed) R.string.setup_installed else R.string.setup_not_installed))
                        if (!state.installed) WorkbenchSecondaryButton(onClick = {
                            if (workbench.runtime.hasBundledImage && !state.onboarding.environmentConfirmed) workbench.confirmOnboardingEnvironment()
                            else workbench.installRuntime()
                        }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(if (workbench.runtime.hasBundledImage) R.string.bundled_runtime_initialize else R.string.install_runtime))
                        }
                        SetupOperation(state, onCancel = workbench::cancel)
                    }
                    2 -> {
                        SetupSummary(stringResource(R.string.setup_model_saved), stringResource(R.string.setup_model_count, models.catalog.profiles.size))
                        if (models.error.isNotBlank()) Text(models.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        WorkbenchSecondaryButton(onClick = { detail = "models" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_configure_models)) }
                        Text(stringResource(R.string.setup_model_private), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    3 -> {
                        val skills = globalSkills.skills.filter { it.builtIn }
                        val skillsEditable = !globalSkills.active && !globalSkills.skillsLoading && !globalSkills.skillError
                        if (globalSkills.skillsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (globalSkills.skillError) Text(stringResource(R.string.ai_skills_global_error),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        skills.forEach { skill -> SetupToggle(skill.name, skill.description,
                            checked = skill.path in globalSkills.enabledSkills, enabled = skillsEditable) {
                            workbench.ai.enableGlobalSkill(skill.path, skill.path !in globalSkills.enabledSkills)
                        } }
                        Text(stringResource(R.string.setup_skills_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        WorkbenchSecondaryButton(onClick = { detail = "skills" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_manage_skills)) }
                    }
                    4 -> {
                        val selected = selectedPackages.split(',').filter { it.isNotBlank() }
                        ToolBundles.catalog.filter { !it.id.startsWith("lsp-") }.forEach { bundle ->
                            val ready = ToolBundles.status(bundle, state.bundleTools) == BundleStatus.Ready
                            SetupToggle(stringResource(bundle.title), stringResource(if (ready) R.string.bundle_ready else bundle.description),
                                bundle.id in selected, enabled = !state.busy && !ready) {
                                selectedPackages = (if (bundle.id in selected) selected - bundle.id else selected + bundle.id).joinToString(",")
                            }
                        }
                        SetupOperation(state, onCancel = workbench::cancel)
                    }
                    5 -> {
                        SetupSummary(stringResource(R.string.setup_permissions_system), stringResource(R.string.setup_permissions_system_hint))
                        SetupSummary(stringResource(R.string.setup_permissions_optional), stringResource(R.string.setup_permissions_optional_hint))
                        WorkbenchSecondaryButton(onClick = { detail = "permissions" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_manage_permissions)) }
                        Text(stringResource(R.string.setup_permissions_no_force), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        SetupSummary(stringResource(R.string.setup_summary_environment), stringResource(if (state.installed) R.string.setup_installed else R.string.setup_later))
                        SetupSummary(stringResource(R.string.setup_summary_models), stringResource(R.string.setup_model_count, models.catalog.profiles.size))
                        Text(stringResource(R.string.setup_finish_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AnimatedContent(entering, label = "setup-enter") { active -> if (active) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.setup_entering), style = MaterialTheme.typography.bodyMedium)
                            }
                        } else Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
            }
            if (displayedStep == 4) Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                val selected = selectedPackages.split(',').filter(String::isNotBlank)
                val hasActionable = selected.any { id -> ToolBundles.catalog.any { bundle ->
                    bundle.id == id && !bundle.manual && ToolBundles.status(bundle, state.bundleTools) != BundleStatus.Ready
                } }
                val unavailableReason = when {
                    state.busy -> R.string.setup_packages_busy
                    !state.installed -> R.string.setup_packages_need_environment
                    state.checkingTools -> R.string.setup_packages_checking
                    selected.isEmpty() -> R.string.setup_packages_none_selected
                    !hasActionable -> R.string.setup_packages_no_pending
                    else -> null
                }
                unavailableReason?.let { reason ->
                    Text(stringResource(reason), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ToolBundleInstallAction(workbench, state, selected) { }
            }
            if (state.busy) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(state.operation, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = workbench::cancel) { Text(stringResource(R.string.cancel)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (displayedStep in 1..5) TextButton(onClick = { if (interactive) step++ }, enabled = !entering && !state.busy) { Text(stringResource(R.string.setup_later)) }
                WorkbenchPrimaryButton(onClick = { if (interactive) { if (step < 6) step++ else entering = true } }, enabled = !entering && !state.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(if (displayedStep == 6) R.string.setup_enter else R.string.setup_continue))
                }
            }
        }
        }
    }
    val stepTransition = updateTransition(step, label = "setup-step")
    val blockPreview = Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Compose the actual previous step before the gesture starts. It has no access to input,
        // and installing tools or changing permissions still requires foreground user actions.
        if (step > 0 && stepTransition.currentState == stepTransition.targetState) {
            Box(Modifier.fillMaxSize().then(blockPreview)) {
                CompositionLocalProvider(LocalWorkbenchPageInteractive provides false) { renderStep(step - 1, false) }
            }
        }
        Box(Modifier.fillMaxSize().then(stepBack.modifier).then(if (detailVisible) blockPreview else Modifier)) {
            stepTransition.AnimatedContent(modifier = Modifier.fillMaxSize(), transitionSpec = {
                if (targetState < initialState) {
                    // The back surface has already completed its gesture/exit animation.
                    // Retaining the old step here would paint it again after resetting motion.
                    androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                } else {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally(folmeSpring(damping = .95f, response = .35f)) { it / 7 * direction } +
                    fadeIn(tween(260, delayMillis = 60))) togetherWith
                    (slideOutHorizontally(folmeSpring(damping = .95f, response = .35f)) { -it / 10 * direction } +
                        fadeOut(tween(160))) using SizeTransform(clip = true)
                }
            }) { shownStep ->
                val interactive = shownStep == step && !detailVisible
                Box(Modifier.fillMaxSize().then(if (interactive) Modifier else blockPreview)) {
                    renderStep(shownStep, interactive)
                }
            }
        }
        detailTransition.AnimatedContent(modifier = Modifier.fillMaxSize(), transitionSpec = {
            if (targetState.isEmpty()) {
                // The detail's own back motion already faded it out. Do not retain an
                // invisible, input-blocking old detail for a second spring transition.
                androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            } else {
            val direction = if (targetState.isEmpty()) -1 else 1
            (slideInHorizontally(folmeSpring(damping = .95f, response = .35f)) { it / 10 * direction } + fadeIn(tween(240))) togetherWith
                (slideOutHorizontally(folmeSpring(damping = .95f, response = .35f)) { -it / 14 * direction } + fadeOut(tween(160)))
            }
        }) { shownDetail ->
            val activeDetail = shownDetail == (if (detail == "tools") "permissions" else detail)
            Box(Modifier.fillMaxSize().then(if (activeDetail) Modifier else blockPreview)) {
            when (shownDetail) {
                "models" -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().systemBarsPadding()) { AiModelsPage(workbench.ai, close = { detail = "" }) }
                }
                "skills" -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().systemBarsPadding()) { AiExtensionsPage(workbench, state) { detail = "" } }
                }
                "permissions" -> {
                    AppPermissionsPage(rootGranted = screen.rootGranted, rootBusy = rootBusy, rootError = rootError,
                        onRequestRoot = {
                            scope.launch {
                                rootBusy = true; rootError = ""
                                try {
                                    workbench.screenTests.setAccess(ScreenTestAccess.Root)
                                    if (!workbench.screenTests.requestRootAccess()) rootError = rootDenied
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { rootError = rootFailed }
                                finally { rootBusy = false }
                            }
                        }, onToolPermissions = { workbench.ai.select(state.selectedId); detail = "tools" },
                        onBack = { detail = "" }, toolsEnabled = state.selectedId != null,
                        backgroundServiceRunning = state.backgroundServiceRunning, backgroundServiceError = state.backgroundServiceError,
                        embedded = true, backEnabled = detail == "permissions" && !state.busy && !entering)
                    if (detail == "tools") AiPermissionsSheet(workbench, state.project?.name.orEmpty()) { detail = "permissions" }
                }
                else -> Unit
            }
            }
        }
    }
}

@Composable
private fun SetupChoice(title: String, description: String, selected: Boolean, enabled: Boolean = true, action: () -> Unit) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = tween(220), label = "setup-choice-color")
    WorkbenchCard(color = background) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            top.yukonga.miuix.kmp.basic.RadioButton(selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SetupToggle(title: String, description: String, checked: Boolean, enabled: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WorkbenchCheckbox(checked = checked, onCheckedChange = { action() }, enabled = enabled)
    }
}

@Composable
private fun SetupSummary(title: String, value: String) {
    WorkbenchCard(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupOperation(state: WorkbenchState, onCancel: () -> Unit) {
    if (state.busy || state.log.isNotBlank() || state.message.isNotBlank()) {
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
        if (state.operation.isNotBlank()) Text(state.operation, style = MaterialTheme.typography.bodySmall)
        if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodySmall,
            color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.log.isNotBlank()) OperationLogPanel(log = state.log, running = state.busy, title = state.operation,
            error = state.message.takeIf { state.messageIsError })
    }
}
