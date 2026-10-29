package dev.zakocoder.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import dev.zakocoder.app.projects.*
import dev.zakocoder.app.runtime.*
import dev.zakocoder.app.settings.*
import dev.zakocoder.app.terminal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

 data class WorkbenchState(
    val loading: Boolean = true,
    val projects: List<Project> = emptyList(), val selectedId: String? = null,
    val installed: Boolean = false, val usedBytes: Long? = null, val downloadCacheBytes: Long? = null, val fileRevision: Int = 0,
    val busy: Boolean = false, val showProgress: Boolean = true, val operation: String = "", val log: String = "", val message: String = "", val messageIsError: Boolean = false,
    val checkingTools: Boolean = false, val toolCheckError: String = "", val toolCheckRevision: Int = 0,
    val tools: List<ToolCheck> = emptyList(), val bundleTools: List<ToolCheck> = emptyList(), val plans: List<DependencyPlan> = emptyList(),
    val plansProjectId: String? = null, val checkingPlans: Boolean = false, val plansError: Boolean = false,
    val settings: AppSettings = AppSettings(), val sessions: List<TerminalItem> = emptyList(), val terminalRevision: Int = 0,
 ) { val project get() = projects.singleOrNull { it.id == selectedId } }

/** Application owns work and sessions; composition only observes state and dispatches explicit actions. */
class Workbench(private val context: Context, aiTransport: dev.zakocoder.app.ai.ChatTransport = dev.zakocoder.app.ai.ChatTransport(), aiConfig: dev.zakocoder.app.ai.AiConfigStore = dev.zakocoder.app.ai.AiConfigStore(context), aiPermissions: dev.zakocoder.app.ai.AiPermissionStore = dev.zakocoder.app.ai.AiPermissionStore(context), aiHistory: dev.zakocoder.app.ai.AiHistoryStore = dev.zakocoder.app.ai.AiHistoryStore(context)) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val runtime = LinuxRuntime(context)
    val projects = ProjectRepository(context, runtime.workspaces)
    val editor = dev.zakocoder.app.editor.EditorController(context, projects, savedFile = {
        mutable.update { it.copy(fileRevision = it.fileRevision + 1) }
        refreshDependencies()
    })
    private val settingsRepository = SettingsRepository(context)
    private val mutable = MutableStateFlow(WorkbenchState())
    val state = mutable.asStateFlow()
    val git: dev.zakocoder.app.git.ProjectGit = dev.zakocoder.app.git.ProjectGit(context,
        { id, script -> executeProjectCommand(id, script, 120000, {}) }, { !mutable.value.busy && !aiActive() && !aiRestoring() })
    fun cloneProject(name: String, url: String) = run(R.string.git_clone, foreground = true) {
        val remote = dev.zakocoder.app.git.publicCloneUrl(url)
        check(mutable.value.installed)
        val project = withContext(Dispatchers.IO) { projects.create(name) }
        var registered = false
        try {
            val script = dev.zakocoder.app.git.GIT_PREFIX + " clone -- " + dev.zakocoder.app.workflow.shellArgument(remote) + " ."
            val result = runtime.execute(script, project.id, mutable.value.settings, timeoutMillis = 30 * 60 * 1000L, output = ::append)
            check(result.exitCode == 0) { context.getString(R.string.git_operation_error) + "\n" + dev.zakocoder.app.ai.AiLogRedactor("").clean(result.output).takeLast(3000) }
            val kinds = withContext(Dispatchers.IO) { ProjectDetector.detect(projects.directory(project.id)) }
            withContext(NonCancellable) { persist(mutable.value.projects + project.copy(kinds = kinds), project.id); registered = true }
        } finally { if (!registered) withContext(NonCancellable + Dispatchers.IO) { PrivateFiles.deleteTree(projects.directory(project.id)) } }
    }
    val projectTasks: dev.zakocoder.app.workflow.ProjectTasks = dev.zakocoder.app.workflow.ProjectTasks(context, projects::directory, ::executeProjectCommand,
        { !mutable.value.busy && !aiActive() && !aiRestoring() }, { if (projectTasksActive()) service() else releaseService() })
    private fun projectTasksActive() = projectTasks.state.value.busy
    private suspend fun executeProjectCommand(id: String, script: String, timeout: Long, output: (String) -> Unit): CommandResult {
        check(!mutable.value.busy && mutable.value.installed)
        mutable.update { it.copy(busy = true, showProgress = false) }; service()
        return try { runtime.execute(script, id, mutable.value.settings, timeoutMillis = timeout, output = output) }
        finally { mutable.update { it.copy(busy = false) }; releaseService() }
    }
    val mcp = dev.zakocoder.app.mcp.McpController(context, scope,
        startProcess = { projectId, server ->
            withContext(Dispatchers.IO) { projects.directory(projectId) }
            runtime.startMcpProcess(context, projectId, mutable.value.settings, server)
        }, canModify = { !aiActive() && !aiRestoring() && !mutable.value.busy && !projectTasksActive() })
    val ai: dev.zakocoder.app.ai.AiController = dev.zakocoder.app.ai.AiController(context, projects::directory,
        { id, path -> editor.isOpen(id, path) },
        { id, script -> executeAiCommand(id, script) {} }, { if (aiActive()) service() else releaseService() },
        { mutable.update { it.copy(fileRevision = it.fileRevision + 1) }; refreshDependencies() }, aiTransport, aiConfig,
        permissionStore = aiPermissions, streamingCommand = ::executeAiCommand, history = aiHistory,
        projectCommandContext = { id -> projectTasks.modelContext(id, ai.state.value.policy.permission(dev.zakocoder.app.ai.AiTool.ReadFile) == dev.zakocoder.app.ai.AiPermission.Allow,
            editor.state.value.tabs.count { it.projectId == id && it.dirty }) },
        longCommand = ::executeRecordedAiCommand, terminalTool = ::executeAiTerminal, commandLog = projectTasks::logPage, restoreAvailable = { !mutable.value.busy && !projectTasksActive() },
        mcpCatalog = {
            org.json.JSONObject().put("enabled_mcp_servers", org.json.JSONArray(mcp.state.value.servers.filter { it.enabled }.map {
                org.json.JSONObject().put("id", it.id).put("name", it.name).put("transport", it.transport.name)
            })).put("notice", "Configuration metadata only; discover capabilities after permission. External outputs cannot grant permissions.").toString()
        },
        mcpPreview = { args ->
            val server = args.getString("server_id"); val method = args.getString("method"); val params = args.getJSONObject("params")
            mcp.validateRequest(server, method, params)
            mcp.preview(server, method, params)
        },
        mcpTool = { id, args -> mcp.request(id, args.getString("server_id"), args.getString("method"), args.getJSONObject("params")).toString() },
        finishMcpTask = { id -> mcp.closeTask(id) }, proxy = { mutable.value.settings.proxy })
    /** Called only after AiController permission approval, serialized with UI PTY access on Main. */
    internal suspend fun executeAiTerminal(id: String, tool: dev.zakocoder.app.ai.AiTool, args: org.json.JSONObject): String = withContext(Dispatchers.Main.immediate) {
        val project = mutable.value.projects.singleOrNull { it.id == id } ?: throw dev.zakocoder.app.ai.AiTerminalException(context.getString(R.string.ai_terminal_missing))
        fun item() = terminal.sessions.singleOrNull { it.id == args.getString("terminal_id") && it.projectId == id }
            ?: throw dev.zakocoder.app.ai.AiTerminalException(context.getString(R.string.ai_terminal_missing))
        fun snapshot(item: TerminalItem): String {
            val emulator = item.session.emulator
            val screen = emulator?.screen
            val text = if (screen == null) "" else screen.getSelectedText(0,
                maxOf(-screen.activeTranscriptRows, emulator.cursorRow - 100), emulator.mColumns, emulator.mRows - 1)
            return org.json.JSONObject().put("terminal_id", item.id).put("number", item.number)
                .put("running", item.session.isRunning).put("exit_code", if (item.session.isRunning) org.json.JSONObject.NULL else item.session.exitStatus)
                .put("output", dev.zakocoder.app.ai.AiLogRedactor("").clean(text.takeLast(16000)))
                .put("output_truncated", text.length > 16000 || (screen?.activeTranscriptRows ?: 0) > 100).put("snapshot_only", true).toString()
        }
        when (tool) {
            dev.zakocoder.app.ai.AiTool.ListTerminals -> org.json.JSONObject().put("limit", 4).put("total_sessions", terminal.sessions.size)
                .put("terminals", org.json.JSONArray(terminal.sessions.filter { it.projectId == id }.map {
                    org.json.JSONObject().put("terminal_id", it.id).put("number", it.number).put("running", it.session.isRunning)
                })).toString()
            dev.zakocoder.app.ai.AiTool.ReadTerminal -> snapshot(item())
            dev.zakocoder.app.ai.AiTool.StartTerminal -> {
                check(mutable.value.installed && !mutable.value.busy)
                if (terminal.sessions.size >= 4) throw dev.zakocoder.app.ai.AiTerminalException(context.getString(R.string.terminal_limit))
                val command = args.getString("command")
                require(command.length in 1..18000 && !command.contains('\u0000'))
                val settings = mutable.value.settings
                val prepared = withContext(Dispatchers.IO) {
                    projects.directory(id); runtime.prepareTerminal(settings)
                    runtime.command(id, settings, command, projectName = project.name)
                }
                // Capacity/project may change while preparing the runtime.
                if (terminal.sessions.size >= 4) throw dev.zakocoder.app.ai.AiTerminalException(context.getString(R.string.terminal_limit))
                check(mutable.value.projects.any { it.id == id } && !mutable.value.busy)
                service()
                // Launch Bash directly inside the managed guest PTY; do not type a command into a user shell.
                val created = terminal.create(id, project.name, prepared)
                delay(500)
                snapshot(created)
            }
            dev.zakocoder.app.ai.AiTool.SendTerminalInput -> {
                val target = item(); check(target.session.isRunning)
                val text = args.optString("text"); val key = args.optString("key")
                require(text.length <= 18000 && text.all { it >= ' ' || it == '\n' || it == '\t' })
                require(key in setOf("", "enter", "ctrl_c", "ctrl_d") && (text.isNotEmpty() || key.isNotEmpty()))
                target.session.write(text + when (key) { "enter" -> "\r"; "ctrl_c" -> "\u0003"; "ctrl_d" -> "\u0004"; else -> "" })
                delay(200)
                snapshot(target)
            }
            dev.zakocoder.app.ai.AiTool.CloseTerminal -> { val target = item(); terminal.close(target.id); releaseService(); org.json.JSONObject().put("closed", target.id).toString() }
            else -> error("Unsupported terminal tool")
        }
    }
    private suspend fun executeRecordedAiCommand(id: String, script: String, timeout: Long, output: (String) -> Unit): CommandResult =
        projectTasks.observed(id, script, timeout, output, ai.state.value.conversationId, ai.state.value.turn, "ai",
            inspectSource = { ai.state.value.policy.permission(dev.zakocoder.app.ai.AiTool.ReadFile) == dev.zakocoder.app.ai.AiPermission.Allow })
    private suspend fun executeAiCommand(id: String, script: String, output: (String) -> Unit): CommandResult {
        check(!mutable.value.busy && mutable.value.installed)
        mutable.update { it.copy(busy = true, showProgress = false) }
        return try { runtime.execute(script, id, mutable.value.settings, timeoutMillis = 120000, output = output) }
        finally { mutable.update { it.copy(busy = false) } }
    }
    private fun aiRestoring() = ai.state.value.restoring
    private fun aiActive(): Boolean = ai.state.value.active
    private var operation: Job? = null
    val terminal: TerminalManager = TerminalManager(context) {
        // Callbacks originate on Android's main looper.
        mutable.update { it.copy(sessions = terminalItems(), terminalRevision = it.terminalRevision + 1) }
        releaseService()
    }
    private fun terminalItems(): List<TerminalItem> = terminal.sessions.toList()
    init {
        scope.launch { mcp.state.collect { if (it.running) service() else releaseService() } }
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { runtime.workspaces.mkdirs(); terminal.prepareTypeface(); Triple(projects.load(), settingsRepository.load(), runtime.isInstalled()) }
                val selected = withContext(Dispatchers.IO) { projects.selected() }
                mutable.update { it.copy(loading = false, projects = data.first, settings = data.second, installed = data.third,
                    selectedId = selected?.takeIf { id -> data.first.any { it.id == id } } ?: data.first.firstOrNull()?.id) }
                refreshPlans()
                doctor()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(loading = false, message = error.message.orEmpty(), messageIsError = true) }
            }
        }
    }
    private var storageJob: Job? = null
    fun validateSettings(settings: AppSettings) = settingsRepository.validate(settings)
    fun refreshStorage() {
        if (storageJob?.isActive == true) return
        storageJob = scope.launch {
            val used = try {
                withContext(Dispatchers.IO) { StorageUsage.measure(context.filesDir, context.cacheDir, context.noBackupFilesDir) to runtime.downloadCacheBytes() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: java.io.IOException) { null }
            mutable.update { it.copy(usedBytes = used?.first, downloadCacheBytes = used?.second) }
        }
    }
    private var foregroundService: DevelopmentService? = null
    private var serviceRequested = false
    private fun service() {
        if (foregroundService != null || serviceRequested) return
        serviceRequested = true
        try { ContextCompat.startForegroundService(context, Intent(context, DevelopmentService::class.java)) }
        catch (error: Exception) { serviceRequested = false; throw error }
    }
    fun serviceReady(service: DevelopmentService) {
        serviceRequested = false
        foregroundService = service
        releaseService()
    }
    fun serviceDestroyed(service: DevelopmentService) {
        if (foregroundService === service) {
            foregroundService = null
            if (mutable.value.busy || mcp.state.value.running || projectTasksActive() || aiActive() || terminal.sessions.any { it.session.isRunning }) stopAll()
        }
    }
    private fun releaseService() {
        if (!mutable.value.busy && !mcp.state.value.running && !projectTasksActive() && !aiActive() && terminal.sessions.none { it.session.isRunning }) {
            // A service may be stopped only after it has acknowledged startForeground().
            val ready = foregroundService ?: return
            foregroundService = null
            ready.stopSelf()
        }
    }
    private fun append(text: String) { mutable.update { it.copy(log = (it.log + text).takeLast(80000)) } }
    private fun run(@androidx.annotation.StringRes title: Int, foreground: Boolean = false, showProgress: Boolean = true, recheckTools: Boolean = false, block: suspend () -> Unit) {
        if (mutable.value.busy || mutable.value.loading || projectTasksActive() || git.state.value.busy) return
        mutable.update { it.copy(busy = true, showProgress = showProgress, operation = context.getString(title), message = "", messageIsError = false, log = "") }
        operation = scope.launch {
            try {
                if (recheckTools) { toolCheckJob?.cancelAndJoin(); toolCheckPending = true }
                if (foreground) service()
                block()
            }
            catch (cancelled: CancellationException) { mutable.update { it.copy(message = context.getString(R.string.operation_cancelled)) }; throw cancelled }
            catch (error: Exception) { android.util.Log.e("Workbench", "Operation failed: $title", error); mutable.update { it.copy(message = context.getString(R.string.operation_failed, error.message ?: error.javaClass.simpleName), messageIsError = true) } }
            finally {
                mutable.update { it.copy(busy = false) }; releaseService(); launchToolCheck()
            }
        }
    }
    private suspend fun persist(list: List<Project>, selected: String?) {
        withContext(Dispatchers.IO) { projects.save(list); projects.select(selected) }
        mutable.update { it.copy(projects = list, selectedId = selected, tools = it.bundleTools.filter { check ->
            DevelopmentTools.required(list.singleOrNull { project -> project.id == selected }?.kinds.orEmpty()).any { tool -> tool.id == check.id }
        }) }
        refreshPlans()
    }
    private var plansGeneration = 0L
    fun refreshDependencies() { scope.launch { refreshPlans() } }
    private suspend fun refreshPlans() {
        val id = mutable.value.selectedId
        val generation = ++plansGeneration
        mutable.update { it.copy(plans = emptyList(), plansProjectId = id, checkingPlans = true, plansError = false) }
        try {
            val plans = withContext(Dispatchers.IO) { if (id == null) emptyList() else DevelopmentTools.dependencies(projects.directory(id)) }
            if (generation == plansGeneration && mutable.value.selectedId == id)
                mutable.update { it.copy(plans = plans, checkingPlans = false) }
        } catch (cancelled: CancellationException) {
            if (generation == plansGeneration) mutable.update { it.copy(checkingPlans = false) }
            throw cancelled
        } catch (_: Exception) {
            if (generation == plansGeneration && mutable.value.selectedId == id)
                mutable.update { it.copy(plans = emptyList(), checkingPlans = false, plansError = true) }
        }
    }
    fun create(name: String, template: ProjectTemplate = ProjectTemplate.Other) = run(R.string.create_project) {
        // The repository stores the trimmed name; generate template content from that same value so a
        // README or HTML title never keeps the surrounding whitespace the project list does not show.
        val projectName = name.trim()
        val project = withContext(Dispatchers.IO) { projects.create(projectName, ProjectTemplates.files(template, projectName)) }
        val kinds = try {
            withContext(Dispatchers.IO) { ProjectDetector.detect(projects.directory(project.id)) }
        } catch (error: Exception) {
            // Detection is best effort; never leave an unregistered working copy behind.
            withContext(NonCancellable + Dispatchers.IO) { runCatching { PrivateFiles.deleteTree(projects.directory(project.id)) } }
            throw error
        }
        persist(mutable.value.projects + project.copy(kinds = kinds), project.id)
    }
    fun import(tree: Uri) = run(R.string.import_project, foreground = true) {
        val project = withContext(Dispatchers.IO) { projects.import(tree) { count -> mutable.update { it.copy(operation = context.getString(R.string.import_progress, count)) } } }
        persist(mutable.value.projects + project, project.id)
    }
    fun select(id: String) = run(R.string.select_project) { persist(mutable.value.projects, id) }
    fun removeProject(id: String) = run(R.string.remove_project) {
        check((!aiActive() && !aiRestoring() && !ai.state.value.permissionsLoading) || ai.state.value.projectId != id) { context.getString(R.string.ai_project_busy) }
        check(editor.state.value.tabs.none { it.projectId == id }) { context.getString(R.string.editor_open_blocks_action) }
        val project = mutable.value.projects.single { it.id == id }
        check(terminal.sessions.none { it.projectId == id && it.session.isRunning }) { context.getString(R.string.close_project_terminals) }
        terminal.sessions.filter { it.projectId == id }.toList().forEach { terminal.close(it.id) }
        withContext(Dispatchers.IO) { PrivateFiles.deleteTree(projects.directory(project.id)) }
        ai.deleteProjectHistory(id)
        withContext(Dispatchers.IO) { projectTasks.deleteProject(id) }
        if (ai.state.value.projectId == id) ai.select(null)
        val remaining = mutable.value.projects.filterNot { it.id == id }
        persist(remaining, remaining.firstOrNull()?.id)
    }
    fun redetect() = run(R.string.detect_project, recheckTools = true) {
        val project = mutable.value.project ?: return@run
        val kinds = withContext(Dispatchers.IO) { ProjectDetector.detect(projects.directory(project.id)) }
        persist(mutable.value.projects.map { if (it.id == project.id) it.copy(kinds = kinds) else it }, project.id)
    }
    fun kinds(kinds: Set<ProjectKind>) = run(R.string.configure_project, recheckTools = true) {
        val id = mutable.value.selectedId ?: return@run
        persist(mutable.value.projects.map { if (it.id == id) it.copy(kinds = kinds) else it }, id)
    }
    fun createFile(projectId: String, directory: String, name: String) = run(R.string.create_file) {
        check(!aiRestoring() || ai.state.value.projectId != projectId) { context.getString(R.string.ai_project_busy) }
        withContext(Dispatchers.IO) { projects.createFile(projectId, directory, name) }
        mutable.update { it.copy(fileRevision = it.fileRevision + 1) }
        if (projectId == mutable.value.selectedId) refreshPlans()
    }
    fun fileAction(id: String, relative: String, action: String, name: String = "") = run(R.string.file_actions) {
        check((!aiActive() && !aiRestoring()) || ai.state.value.projectId != id) { context.getString(R.string.ai_project_busy) }
        check(editor.state.value.tabs.none { it.projectId == id }) { context.getString(R.string.editor_open_blocks_action) }
        withContext(Dispatchers.IO) {
            when (action) {
                "rename" -> projects.renameEntry(id, relative, name)
                "copy" -> projects.copyEntry(id, relative, name)
                "delete" -> projects.deleteEntry(id, relative)
                else -> error("Unknown file action")
            }
        }
        mutable.update { it.copy(fileRevision = it.fileRevision + 1) }
        if (id == mutable.value.selectedId) refreshPlans()
    }
    fun importFile(projectId: String, directory: String, uri: Uri) = run(R.string.upload_file) {
        withContext(Dispatchers.IO) { projects.importFile(projectId, directory, uri) }
        mutable.update { it.copy(fileRevision = it.fileRevision + 1) }
        if (projectId == mutable.value.selectedId) refreshPlans()
    }
    fun export(tree: Uri) = run(R.string.export_project, foreground = true) {
        val id = mutable.value.selectedId ?: return@run
        val name = withContext(Dispatchers.IO) { projects.export(id, tree) }
        mutable.update { it.copy(message = context.getString(R.string.export_done, name)) }
    }
    fun installRuntime(offline: Uri? = null) = run(R.string.install_runtime, foreground = true, recheckTools = true) {
        runtime.install(mutable.value.settings, offline) { status -> mutable.update { it.copy(operation = status) } }
        mutable.update { it.copy(installed = true, message = context.getString(R.string.runtime_ready)) }
    }
    private var toolCheckJob: Job? = null
    private var toolCheckPending = false

    /** Coalesce requests; mutations cancel and join this owned process before changing tools. */
    fun doctor() {
        refreshDependencies()
        if (!mutable.value.installed) return
        toolCheckPending = true
        launchToolCheck()
    }
    private fun launchToolCheck() {
        if (!toolCheckPending || mutable.value.busy || mutable.value.loading || !mutable.value.installed || toolCheckJob?.isActive == true) return
        toolCheckPending = false
        mutable.update { it.copy(checkingTools = true, toolCheckError = "") }
        toolCheckJob = scope.launch {
            try {
                val result = runtime.execute(DevelopmentTools.doctorScript(DevelopmentTools.catalog), null, mutable.value.settings, timeoutMillis = 60_000)
                check(result.exitCode == 0) { result.output.takeLast(1000) }
                val checks = DevelopmentTools.parse(result.output, DevelopmentTools.catalog)
                mutable.update { state -> state.copy(bundleTools = checks, tools = checks.filter { check ->
                    DevelopmentTools.required(state.project?.kinds.orEmpty()).any { it.id == check.id }
                }, toolCheckRevision = state.toolCheckRevision + 1) }
            } catch (_: TimeoutCancellationException) {
                mutable.update { it.copy(toolCheckError = context.getString(R.string.tool_check_timeout)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.update { it.copy(toolCheckError = context.getString(R.string.tool_check_failed, error.message ?: error.javaClass.simpleName)) }
            } finally {
                mutable.update { it.copy(checkingTools = false) }
                toolCheckJob = null
                launchToolCheck()
            }
        }
    }
    fun installToolBundles(ids: Set<String>, acceptAndroidLicense: Boolean = false) = run(R.string.install_selected_bundles, foreground = true, recheckTools = true) {
        check(mutable.value.installed)
        require("android" !in ids || acceptAndroidLicense) { context.getString(R.string.android_sdk_license_required) }
        val androidPackages = ids.count { it == "android" || it == "android-ndk" }
        if (androidPackages > 0) require(withContext(Dispatchers.IO) { runtime.freeBytes() } >= androidPackages * 3L * 1024 * 1024 * 1024) {
            context.getString(R.string.android_toolchain_storage_required, androidPackages * 3)
        }
        if ("android-ndk" in ids && "android" !in ids) {
            val sdk = runtime.execute(AndroidToolchain.PROBE, null, mutable.value.settings, output = ::append)
            check(sdk.exitCode == 0) { context.getString(R.string.android_ndk_sdk_required) }
        }
        val packages = ToolBundles.packages(ids, mutable.value.bundleTools)
        if (packages.isNotEmpty()) {
            val result = runtime.execute(DevelopmentTools.installScript(packages), null, mutable.value.settings, output = ::append)
            append(context.getString(R.string.command_exit, result.exitCode))
            check(result.exitCode == 0) { context.getString(R.string.command_failed, result.exitCode) }
        }
        if ("android" in ids) {
            val script = withContext(Dispatchers.IO) { context.assets.open("android-build/install.sh").bufferedReader().use { it.readText() } }
            val result = runtime.execute(script, null, mutable.value.settings, timeoutMillis = 90 * 60 * 1000L, output = ::append)
            append(context.getString(R.string.command_exit, result.exitCode))
            check(result.exitCode == 0) { context.getString(R.string.command_failed, result.exitCode) }
        }
        if ("android-ndk" in ids) {
            val script = withContext(Dispatchers.IO) { context.assets.open("android-build/install-ndk.sh").bufferedReader().use { it.readText() } }
            val result = runtime.execute(script, null, mutable.value.settings, timeoutMillis = 90 * 60 * 1000L, output = ::append)
            append(context.getString(R.string.command_exit, result.exitCode))
            check(result.exitCode == 0) { context.getString(R.string.command_failed, result.exitCode) }
        }
    }
    fun repairPackages() = run(R.string.repair_packages, foreground = true, recheckTools = true) {
        check(mutable.value.installed)
        val result = runtime.execute("dpkg --configure -a", null, mutable.value.settings, output = ::append)
        append(context.getString(R.string.command_exit, result.exitCode))
        check(result.exitCode == 0) { context.getString(R.string.command_failed, result.exitCode) }
    }
    fun installPackages(packages: List<String>) = run(R.string.install_tools, foreground = true, recheckTools = true) {
        check(mutable.value.installed)
        val result = runtime.execute(DevelopmentTools.installScript(packages), mutable.value.selectedId, mutable.value.settings, output = ::append)
        append(context.getString(R.string.command_exit, result.exitCode))
        check(result.exitCode == 0) { context.getString(R.string.command_failed, result.exitCode) }
    }
    fun installDependencies(projectId: String, plan: DependencyPlan) = run(R.string.install_dependencies, foreground = true, recheckTools = true) {
        check(mutable.value.selectedId == projectId && mutable.value.plansProjectId == projectId && !mutable.value.checkingPlans && !mutable.value.plansError && plan in mutable.value.plans && mutable.value.installed)
        check(withContext(Dispatchers.IO) { plan in DevelopmentTools.dependencies(projects.directory(projectId)) })
        val result = projectTasks.observed(projectId, plan.command, 120_000, ::append, source = "dependency",
            executeCommand = { id, script, timeout, output ->
                runtime.execute(script, id, mutable.value.settings, timeoutMillis = timeout, output = output)
            })
        append(context.getString(R.string.command_exit, result.exitCode))
        check(result.exitCode == 0) { context.getString(R.string.command_failed, result.exitCode) }
        refreshPlans()
        mutable.update { it.copy(message = context.getString(R.string.dependencies_done)) }
    }
    fun saveSettings(settings: AppSettings) {
        val previous = mutable.value.settings
        val runtimeChanged = settings.copy(terminalFontSp = previous.terminalFontSp, terminalShell = previous.terminalShell) != previous
        run(R.string.save_settings, recheckTools = runtimeChanged) {
            withContext(Dispatchers.IO) {
                settingsRepository.validate(settings)
                if (mutable.value.installed && runtimeChanged) runtime.configure(settings)
                settingsRepository.save(settings)
            }
            mutable.update { it.copy(settings = settings, tools = if (runtimeChanged) emptyList() else it.tools,
                bundleTools = if (runtimeChanged) emptyList() else it.bundleTools, message = context.getString(R.string.settings_saved)) }
        }
    }
    fun clearCache() = run(R.string.clear_cache) { withContext(Dispatchers.IO) { runtime.clearDownloadCache() } }
    fun cancel() { scope.launch(Dispatchers.IO) { operation?.cancelAndJoin() } }
    fun startTerminal(projectId: String? = mutable.value.selectedId) = run(R.string.new_terminal, foreground = true, showProgress = false) {
        val state = mutable.value
        val project = state.projects.singleOrNull { it.id == projectId } ?: return@run
        check(state.installed) { context.getString(R.string.runtime_missing) }
        check(terminal.sessions.size < 4) { context.getString(R.string.terminal_limit) }
        val prepared = withContext(Dispatchers.IO) {
            projects.directory(project.id); runtime.prepareTerminal(state.settings)
            runtime.command(project.id, state.settings, null, projectName = project.name)
        }
        check(mutable.value.projects.any { it.id == project.id } && mutable.value.installed)
        terminal.create(project.id, project.name, prepared)
    }
    fun closeTerminal(id: String) { scope.launch { terminal.close(id); releaseService() } }
    fun stopAll() { git.stop(); projectTasks.stop(); ai.stop(); mcp.closeAll(); scope.launch { toolCheckPending = false; operation?.cancelAndJoin(); toolCheckPending = false; toolCheckJob?.cancelAndJoin(); terminal.closeAll(); releaseService() } }
    fun referenceAi(projectId: String, reference: dev.zakocoder.app.ai.AiReference): Boolean {
        val accepted = ai.referenceProject(projectId, reference)
        if (!accepted) mutable.update { it.copy(message = ai.state.value.error.ifBlank { context.getString(R.string.ai_reference_error) }, messageIsError = true) }
        return accepted
    }
    fun dismissMessage() { mutable.update { it.copy(message = "", messageIsError = false) } }
}
