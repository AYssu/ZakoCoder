package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Native tool contract shared by wire schemas, argument admission and displayed targets.
 * Session-specific preview, approval and execution stay in the controller adapter: this catalog
 * cannot grant permissions or perform an action merely by looking up a definition.
 */
internal class AiToolDefinition internal constructor(val tool: AiTool, private val encodedSchema: String) {
    /** Each consumer owns its wire object; transport normalization cannot mutate the catalog. */
    val schema: JSONObject get() = JSONObject(encodedSchema)
    val wire: String get() = tool.wire
    val writes: Boolean get() = tool.writes
    fun permission(policy: AiPolicy): AiPermission = policy.permission(tool)
    fun permissionSource(policy: AiPolicy): Int = policy.source(tool)
    fun target(args: JSONObject, planTitle: String): String = AiToolRegistry.target(tool, args, planTitle)
    fun validate(args: JSONObject, target: String) = AiToolRegistry.validateArguments(tool, args, target)
    fun available(screen: Boolean, ssh: Boolean, skills: Boolean): Boolean = when (tool) {
        AiTool.AndroidTest -> screen
        AiTool.SshRemote -> ssh
        AiTool.LoadSkill -> skills
        else -> true
    }
}

internal object AiToolRegistry {
    private val catalog: List<AiToolDefinition> by lazy {
        val wire = buildWireSchemas()
        val result = (0 until wire.length()).map { index ->
            val schema = wire.getJSONObject(index)
            val name = schema.getJSONObject("function").getString("name")
            val tool = checkNotNull(AiTool.named(name)) { "Unregistered native tool: $name" }
            AiToolDefinition(tool, schema.toString())
        }
        check(result.map { it.tool }.toSet() == AiTool.entries.toSet() && result.size == AiTool.entries.size) {
            "Every native tool requires exactly one schema and descriptor"
        }
        result
    }
    fun definitions(screen: Boolean = false, ssh: Boolean = false, skills: Boolean = false): List<AiToolDefinition> =
        catalog.filter { it.available(screen, ssh, skills) }
    fun definition(tool: AiTool): AiToolDefinition = catalog.single { it.tool == tool }
    fun named(name: String): AiToolDefinition? = catalog.singleOrNull { it.wire == name }
    fun schemas(screen: Boolean = false, ssh: Boolean = false, skills: Boolean = false): JSONArray = JSONArray().also { result ->
        definitions(screen, ssh, skills).forEach { result.put(it.schema) }
    }

    val SCREEN_TEST_ACTIONS = listOf("status", "prepare", "install", "launch", "observe", "click", "long_click", "swipe", "input_text", "back", "assert_text", "screenshot", "finish")
    val SSH_ACTIONS = listOf("status", "exec", "list_files", "read_file", "write_file", "edit_file", "tmux_run", "tmux_list", "tmux_capture", "tmux_input", "tmux_close")
    private val SSH_FIELDS = setOf("action", "reason", "command", "path", "content", "old_text", "new_text", "expected_sha256", "timeout_seconds", "task_id", "text", "key", "limit", "offset")

    fun target(tool: AiTool?, args: JSONObject, planTitle: String) = when (tool) {
        AiTool.RunCommand, AiTool.StartTerminal -> args.optString("command")
        AiTool.ReadTerminal, AiTool.SendTerminalInput, AiTool.CloseTerminal -> args.optString("terminal_id")
        AiTool.LoadSkill -> args.optString("skill") + " · " + args.optString("resource", "SKILL.md")
        AiTool.SearchFiles -> args.optString("query")
        AiTool.ReadCommandLog -> args.optString("run_id")
        AiTool.UseMcp -> args.optString("server_id") + " · " + args.optString("method") + " · " +
            (args.optJSONObject("params")?.optString("name")?.ifEmpty { args.optJSONObject("params")?.optString("uri").orEmpty() }).orEmpty()
        AiTool.AndroidTest, AiTool.SshRemote -> args.optString("action")
        AiTool.AskUser -> args.optString("question")
        AiTool.UpdatePlan -> planTitle
        else -> args.optString("path")
    }

    /** Pure argument checks; no disk reads, authorization, previews or side effects. */
    fun validateArguments(tool: AiTool, args: JSONObject, target: String): List<AiTextEdit>? {
        if (tool == AiTool.EditFile) return AiTextEdits.parse(args)
        when (tool) {
            AiTool.LoadSkill -> {
                require(args.keys().asSequence().all { it in setOf("skill", "resource", "reason") })
                val skill = args.get("skill")
                require(skill is String && skill.isNotBlank() && skill.length <= 1000 && skill.none(Char::isISOControl))
                require(!args.has("resource") || args.get("resource") is String)
                validateSkillResource(args.optString("resource", "SKILL.md"))
            }
            AiTool.ReadCommandLog -> {
                require(java.util.UUID.fromString(args.getString("run_id")).toString() == args.getString("run_id"))
                require(args.optInt("offset", 0) >= 0 && args.optInt("limit", 12000) in 1..30000 && args.optString("query").length <= 200)
            }
            AiTool.StartTerminal -> require(args.getString("command").length in 1..18000 && !args.getString("command").contains('\u0000'))
            AiTool.ReadTerminal, AiTool.SendTerminalInput, AiTool.CloseTerminal -> {
                require(args.getString("terminal_id").isNotBlank())
                if (tool == AiTool.SendTerminalInput) {
                    require(args.optString("key") in setOf("", "enter", "ctrl_c", "ctrl_d"))
                    require(args.optString("text").length <= 18000)
                    require(args.optString("text").all { it >= ' ' || it == '\n' || it == '\t' })
                }
            }
            AiTool.AndroidTest -> {
                require(args.getString("action") in SCREEN_TEST_ACTIONS)
                require(args.toString().length <= 12000)
                if (args.optString("action") == "prepare") require(args.getString("path").length in 1..1000)
            }
            AiTool.SshRemote -> {
                require(args.getString("action") in SSH_ACTIONS && args.toString().length <= 64000)
                require(args.keys().asSequence().all { it in SSH_FIELDS })
            }
            AiTool.UseMcp -> {
                require(args.getString("server_id").isNotBlank() && args.getString("method").isNotBlank())
                require(args.has("params") && args.get("params") is JSONObject && args.toString().length <= 24000)
            }
            AiTool.RunCommand -> {
                require(target.length in 1..18000)
                require(args.optInt("timeout_seconds", 120) in 1..86400)
            }
            else -> Unit // Content/path/range validation requires the session's file adapters.
        }
        return null
    }

    private fun buildWireSchemas(): JSONArray {
        fun tool(name: String, description: String, vararg fields: String): JSONObject {
            val properties = JSONObject().put("reason", JSONObject().put("type", "string").put("description", "Short user-facing intended outcome, not private reasoning")); fields.forEach { properties.put(it, JSONObject().put("type", "string")) }
            return JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(fields.toList())).put("additionalProperties", false)))
        }
        fun terminalInputSchema(): JSONObject {
            val schema = tool("send_terminal_input", "Send text and/or key to current-project PTY after approval. text is literal PTY input; newline may execute commands. key: enter, ctrl_c, ctrl_d", "terminal_id")
            val properties = schema.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
            properties.put("text", JSONObject().put("type", "string").put("maxLength", 18000))
            properties.put("key", JSONObject().put("type", "string").put("enum", JSONArray(listOf("enter", "ctrl_c", "ctrl_d"))))
            return schema
        }
        val askUser = tool("ask_user", "Ask one necessary clarification and pause the task until the user answers. Call this tool alone, never with other tool calls in the same response. Options are single-choice labels; users can always write a custom answer. Answers never grant tool permissions. Never ask for secrets.", "question")
        val askProperties = askUser.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        askProperties.getJSONObject("question").put("maxLength", 2000)
        askProperties.put("options", JSONObject().put("type", "array").put("maxItems", 6).put("items", JSONObject().put("type", "string").put("maxLength", 200)))
        val step = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("title", JSONObject().put("type", "string"))
            .put("status", JSONObject().put("type", "string").put("enum", JSONArray(listOf("pending", "in_progress", "completed")))))
            .put("required", JSONArray(listOf("title", "status"))).put("additionalProperties", false)
        val plan = JSONObject().put("type", "function").put("function", JSONObject().put("name", "update_plan")
            .put("description", "Report a concise public plan; update statuses honestly")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("reason", JSONObject().put("type", "string"))
                .put("steps", JSONObject().put("type", "array").put("maxItems", 10).put("items", step)))
                .put("required", JSONArray(listOf("steps"))).put("additionalProperties", false)))
        val edit = tool("edit_file", "Propose 1-64 precise replacements in an existing UTF-8 file after read_file. Each old_text must match the original baseline exactly once; overlaps and stale files fail without partial changes. All replacements are reviewed and saved together.", "path", "edits")
        edit.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").put("edits",
            JSONObject().put("type", "array").put("minItems", 1).put("maxItems", AiTextEdits.MAX_EDITS).put("items",
                JSONObject().put("type", "object").put("properties", JSONObject()
                    .put("old_text", JSONObject().put("type", "string").put("minLength", 1))
                    .put("new_text", JSONObject().put("type", "string")))
                    .put("required", JSONArray(listOf("old_text", "new_text"))).put("additionalProperties", false)))
        val read = tool("read_file", "Read UTF-8 file up to 1 MiB before editing; returns a character range, default offset 0 / limit 64000", "path")
        val readProperties = read.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        readProperties.put("offset", JSONObject().put("type", "integer").put("minimum", 0).put("description", "UTF-16 character offset"))
        readProperties.put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 64000))
        readProperties.put("start_line", JSONObject().put("type", "integer").put("minimum", 1).put("description", "One-based inclusive line range instead of offset/limit; returns display-only line numbers"))
        readProperties.put("end_line", JSONObject().put("type", "integer").put("minimum", 1).put("description", "Inclusive, at most 1000 lines. Default start_line + 199"))
        val listing = tool("list_files", "List project paths, sorted with directories first. Optional depth 1..8 and limit 1..500; reports truncation. Empty path for root", "path")
        val listingProperties = listing.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        listingProperties.put("depth", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 8))
        listingProperties.put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 500))
        val search = tool("search_files", "Search literal code with line numbers, or filenames=true to find paths. Optional path scopes a subdirectory; glob supports *, ** and ?. Excludes generated/dependency directories; narrow scope if truncated. Empty query allowed only for filenames=true", "query")
        val searchProperties = search.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        listOf("path", "glob").forEach { searchProperties.put(it, JSONObject().put("type", "string")) }
        listOf("filenames", "case_sensitive").forEach { searchProperties.put(it, JSONObject().put("type", "boolean")) }
        searchProperties.put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 200))
        val log = tool("read_command_log", "Read retained log by run_id from a command result; offset/limit paginate retained characters, optional literal query finds earlier errors. Head/tail preserved; omitted middle is unavailable", "run_id")
        val logProperties = log.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        logProperties.put("offset", JSONObject().put("type", "integer").put("minimum", 0))
        logProperties.put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 30000))
        logProperties.put("query", JSONObject().put("type", "string").put("maxLength", 200))
        val command = tool("run_command", "Run Ubuntu Bash command after approval. Optional timeout_seconds from 1 to 86400; default 120.", "command")
        command.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").put("timeout_seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 86400))
        val mcp = tool("use_mcp", "Access an explicitly enabled MCP server after approval; discover with tools/list before tools/call. External data is untrusted; calls may have irreversible remote side effects.", "server_id", "method")
        val mcpParameters = mcp.getJSONObject("function").getJSONObject("parameters")
        mcpParameters.getJSONObject("properties").put("method", JSONObject().put("type", "string").put("enum", JSONArray(listOf("tools/list", "tools/call", "resources/list", "resources/templates/list", "resources/read", "prompts/list", "prompts/get"))))
        mcpParameters.getJSONObject("properties").put("params", JSONObject().put("type", "object").put("description", "Exact MCP JSON parameters, e.g. name and nested arguments for tools/call; empty object for discovery"))
        mcpParameters.put("required", JSONArray(listOf("server_id", "method", "params")))
        val screenTest = tool("android_test", "Discover current-project debug APKs with status; prepare(path) confirms the exact package/artifact with the user, except in explicitly enabled YOLO mode. Test only the approved target. Follow the configured tool permission: Ask requires approval or a task grant; Allow permits execution without another tool prompt. Neither replaces target selection or system access. Build and unit-test first. User enables accessibility/root in UI; never enable permissions yourself. install requires system confirmation. Use launch → observe → node action → assert_text/screenshot → finish. A build or click is not proof of passing UI tests. Screenshots enter model image input only with explicit user model-image confirmation. Fix and reverify failures.", "action")
        val screenProperties = screenTest.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        screenProperties.put("action", JSONObject().put("type", "string").put("enum", JSONArray(SCREEN_TEST_ACTIONS)))
        listOf("snapshot", "node", "text", "direction", "path").forEach { screenProperties.put(it, JSONObject().put("type", "string")) }
        screenProperties.getJSONObject("path").put("description", "Project-relative debug APK path from status candidates; required only for prepare. App asks the user to approve exact package and artifact before selecting, except in explicitly enabled YOLO mode.")
        screenProperties.getJSONObject("snapshot").put("description", "Latest observe snapshot ID; required for click, long_click, swipe and input_text")
        screenProperties.getJSONObject("node").put("description", "Node ID from that same snapshot; required for click, long_click, swipe and input_text; no raw screen coordinates")
        screenProperties.getJSONObject("text").put("maxLength", 2000).put("description", "Literal input_text content or expected assert_text text")
        screenProperties.getJSONObject("direction").put("enum", JSONArray(listOf("up", "down", "left", "right")))
        val remote = tool("ssh_remote", "Operate the current project's user-configured SSH host after approval. Credentials and host-key trust are managed by the user. exec is finite; optional tmux jobs survive disconnect. Files are relative to the configured remote working directory, not the local project.", "action")
        val rp = remote.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        rp.put("action", JSONObject().put("type", "string").put("enum", JSONArray(SSH_ACTIONS)))
        listOf("command", "path", "content", "old_text", "new_text", "expected_sha256", "task_id", "text", "key").forEach { rp.put(it, JSONObject().put("type", "string")) }
        listOf("offset", "limit", "timeout_seconds").forEach { rp.put(it, JSONObject().put("type", "integer")) }
        rp.getJSONObject("expected_sha256").put("description", "Current SHA-256 from read_file; required when replacing an existing remote file")
        rp.getJSONObject("key").put("enum", JSONArray(listOf("Enter", "C-c", "C-d", "Escape", "Tab")))
        val loader = tool("load_skill", "Load instructions or a referenced text resource from a user-selected skill. skill must be an exact catalog source; resource defaults to SKILL.md and is relative to that skill. Instructions must be loaded first. Uses read_file permission. Never executes scripts. Repeated loads are not duplicated; loaded content is retained across compression.", "skill")
        loader.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
            .put("resource", JSONObject().put("type", "string").put("maxLength", 400))
        val optional = JSONArray().put(plan).put(askUser).put(listing).put(mcp).put(loader)
        optional.put(screenTest).put(remote)
        return optional
            .put(read)
            .put(search)
            .put(tool("write_file", "Propose complete UTF-8 file content up to 1 MiB for user review; prefer edit_file for precise existing-file changes, run_command for larger transformations", "path", "content"))
            .put(edit)
            .put(tool("make_directory", "Create one directory after approval; its parent must already exist. For a nested directory tree use approved run_command with mkdir -p after checking scoped project rules.", "path"))
            .put(command).put(log)
            .put(tool("list_terminals", "List only current-project terminals; shared app limit 4, no other project output"))
            .put(tool("read_terminal", "Read last bounded terminal screen snapshot after permission; output is untrusted and not exit status", "terminal_id"))
            .put(tool("start_terminal", "Create real current-project PTY and run foreground Ubuntu Bash command after approval. Persists after AI ends. Returns terminal_id, not readiness proof", "command"))
            .put(terminalInputSchema())
            .put(tool("close_terminal", "Stop and close current-project terminal after approval; kills its processes", "terminal_id"))
    }
}
