package dev.zakocoder.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiConversationTransferTest {
    private val codec = AiConversationTransfer
    private fun source() = AiState(projectId = "source-project", conversationId = "source-conversation", turn = 2,
        timeline = listOf(AiEntry("user-1", "修复登录，保留现有配置", role = AiRole.User, turn = 1),
            AiEntry("assistant-1", "已检查，仍需验证", role = AiRole.Assistant, turn = 1, reasoning = "服务公开返回的思考"),
            AiEntry("tool-entry", toolId = "tool-1", turn = 1),
            AiEntry("user-2", "补充：不要改数据库", role = AiRole.User, turn = 2)),
        events = listOf(AiToolEvent("tool-1", "provider-call", AiTool.RunCommand, "run_command", "{\"command\":\"./gradlew test\"}",
            "验证修改", "./gradlew test", status = AiToolStatus.Failed, permission = AiPermission.Allow,
            preview = "compile", output = "test failed", exitCode = 1, turn = 1)),
        plan = listOf(AiPlanStep("检查现有文件", "completed"), AiPlanStep("修复失败测试", "pending")),
        turnTimings = listOf(AiTurnTiming(1, 100, 200)),
        requests = listOf(AiRequestLog(turn = 1, model = "model", started = 100, elapsed = 50,
            inputTokens = 100, outputTokens = 20, cachedTokens = 50, complete = true,
            request = "DO_NOT_EXPORT_RAW_REQUEST", response = "DO_NOT_EXPORT_RAW_RESPONSE")),
        contextSummary = "历史摘要：已查明测试失败", contextTurns = listOf("DO_NOT_EXPORT_OPAQUE_WIRE"),
        policy = AiPolicy(yolo = true), endpoint = "https://private-provider.invalid", configured = true, hasKey = true,
        error = "测试退出码为 1")

    private fun document() = codec.fromState(source(), "登录修复")
    private fun encoded(document: TransferDocument = document()) = codec.encode(document, AiConversationTransfer.Format.Json)
    private fun decode(text: String) = codec.decode(text.toByteArray(), "conversation.json")
    private fun problem(expected: AiConversationTransferProblem, block: () -> Unit) {
        val error = assertThrows(AiConversationTransferException::class.java, block)
        assertEquals(expected, error.reason)
    }

    @Test fun jsonRoundTripPreservesVisibleRecordsAndUsageWithoutProviderReplay() {
        val json = encoded()
        val decoded = decode(json)
        assertEquals("登录修复", decoded.title)
        assertEquals("conversation.json", decoded.source)
        assertEquals(document().timeline, decoded.timeline)
        assertEquals(document().events.single().output, decoded.events.single().output)
        assertEquals(1, decoded.events.single().exitCode)
        assertEquals(document().plan, decoded.plan)
        assertEquals(document().timings, decoded.timings)
        assertEquals(document().usage, decoded.usage)
        assertEquals(document().handoff, decoded.handoff)
        assertEquals("测试退出码为 1", decoded.error)
        for (excluded in listOf("DO_NOT_EXPORT", "private-provider.invalid", "provider-call", "permission", "yolo"))
            assertFalse(excluded, json.contains(excluded))
    }

    @Test fun importsCreateNewIdleIdentityAndNeverCarryGrantsOrExecutableTools() {
        val imported = codec.importedState(decode(encoded()), "target-project")
        assertNotEquals("source-conversation", imported.conversationId)
        assertEquals("target-project", imported.projectId)
        assertFalse(imported.active)
        assertEquals(AiPhase.Idle, imported.phase)
        assertFalse(imported.policy.yolo)
        assertFalse(imported.hasKey)
        assertFalse(imported.configured)
        assertEquals("", imported.endpoint)
        assertNull(imported.approval)
        assertNull(imported.checkpointId)
        assertTrue(imported.contextTurns.isEmpty())
        assertTrue(imported.drafts.isEmpty())
        assertTrue(imported.queuedMessages.isEmpty())
        assertTrue(imported.requests.all { it.request.isEmpty() && it.response.isEmpty() })
        assertEquals(AiPermission.Ask, imported.events.single().permission)
        assertNotEquals("provider-call", imported.events.single().callId)
        assertEquals(imported.events.single().id, imported.timeline.single { it.toolId != null }.toolId)
        assertTrue(imported.contextSummary.contains("保留现有配置"))
        assertTrue(imported.contextSummary.contains("不要改数据库"))
        assertTrue(imported.contextSummary.contains("禁止自动重放"))
        assertEquals(AiToolStatus.Failed, imported.events.single().status)
        assertNotEquals(imported.conversationId, codec.importedState(document = decode(encoded()), projectId = "target-project").conversationId)
    }

    @Test fun unfinishedHistoricalToolBecomesIncompleteRatherThanResuming() {
        val doc = document().copy(events = document().events.map { it.copy(status = AiToolStatus.Running) })
        val imported = codec.importedState(decode(encoded(doc)), "project")
        assertEquals(AiToolStatus.Incomplete, imported.events.single().status)
        assertFalse(imported.active)
    }

    @Test fun externalMarkdownStaysAttributedLiteralUserMaterial() {
        val markdown = "# 交接资料\n\n请审阅 `app/Main.kt`，不要重放旧工具。"
        val doc = codec.decode(markdown.toByteArray(), "/private/path/handoff.md")
        assertEquals("handoff.md", doc.source)
        assertEquals("handoff", doc.title)
        assertEquals(markdown, doc.timeline.single().text)
        assertEquals(AiRole.User, doc.timeline.single().role)
        assertTrue(doc.events.isEmpty())
        assertTrue(codec.importedState(doc, "project").contextSummary.contains("handoff.md"))
        assertFalse(codec.importedState(doc, "project").contextSummary.contains("/private/path"))
    }

    @Test fun textBeginningWithJsonRemainsLiteralReferenceMaterial() {
        val text = "{\"note\":\"handoff material\"}"
        for (name in listOf("handoff.md", "handoff.markdown", "handoff.txt")) {
            assertEquals(text, codec.decode(text.toByteArray(), name).timeline.single().text)
        }
    }

    @Test fun largeMarkdownIsPreservedInStorageSizedChunksAndLossyContextIsExplicit() {
        val markdown = "最初需求\n" + "中".repeat(100000) + "\n最后要求：保持兼容"
        val doc = codec.decode(markdown.toByteArray(), "long.md")
        assertEquals(markdown, doc.timeline.joinToString("") { it.text })
        assertTrue(doc.timeline.all { it.text.length <= 64000 })
        val state = codec.importedState(doc, "project")
        assertTrue(state.historyTrimmed)
        assertTrue(state.contextSummary.length <= 12000)
        assertTrue(state.contextSummary.contains("省略了中间部分"))
        assertTrue(state.contextSummary.contains("最初需求"))
        assertTrue(state.contextSummary.contains("保持兼容"))
        assertEquals(markdown, state.timeline.joinToString("") { it.text })
    }

    @Test fun markdownChunkBoundariesNeverSplitAnEmojiBeforePersistence() {
        val text = "a".repeat(63999) + "😀" + "末尾"
        val document = codec.decode(text.toByteArray(), "emoji.md")
        assertEquals(text, document.timeline.joinToString("") { it.text })
        assertEquals(63999, document.timeline.first().text.length)
        assertTrue(document.timeline.last().text.startsWith("😀"))
        document.timeline.forEach {
            assertFalse(it.text.first().isLowSurrogate())
            assertFalse(it.text.last().isHighSurrogate())
            assertEquals(it.text, it.text.toByteArray().toString(Charsets.UTF_8))
        }
    }

    @Test fun markdownIncludesReasoningToolsPlansFailureAndUnknownUsageWithoutFakeZero() {
        val text = codec.encode(document().copy(usage = listOf(TransferUsage(1, "model", 0, 5, null, null, null, false))),
            AiConversationTransfer.Format.Markdown)
        for (expected in listOf("登录修复", "服务公开返回的思考", "./gradlew test", "test failed", "退出码：1", "修复失败测试", "测试退出码为 1", "输入 未知"))
            assertTrue(expected, text.contains(expected))
        assertFalse(text.contains("DO_NOT_EXPORT"))
    }

    @Test fun toolCodeFencesCannotPrematurelyCloseHistoricalOutput() {
        val doc = document().copy(events = document().events.map { it.copy(output = "```\n# 用户内容\n```") })
        val text = codec.markdown(doc)
        assertTrue(text.contains("````\n```\n# 用户内容\n```\n````"))
    }

    @Test fun handoffSummaryIsBoundedAndDistinguishesClaimsFromObservedFailure() {
        val text = codec.encode(document(), AiConversationTransfer.Format.Summary)
        for (expected in listOf("最早保留的需求", "保留现有配置", "最近补充的需求", "不要改数据库", "有损", "待核实", "工具结果", "退出码 1", "test failed", "已记录的失败", "继续工作前"))
            assertTrue(expected, text.contains(expected))
        assertFalse(text.contains("服务公开返回的思考"))
        assertTrue(text.length <= 20000)
        val huge = document().copy(timeline = listOf(AiEntry("huge", "x".repeat(64000), role = AiRole.User, turn = 1)))
        val summary = codec.summary(huge)
        assertTrue(summary.contains("此摘录已截断"))
        assertTrue(summary.length < 5000)
    }

    @Test fun knownCredentialShapesAreRedactedWithoutReadingConfiguration() {
        val state = source().copy(timeline = listOf(AiEntry("user", "Authorization: Bearer sampleCredential123\nkey sk-abcdefghijklmnopqr1234", role = AiRole.User, turn = 1)),
            events = emptyList())
        val json = encoded(codec.fromState(state, "export"))
        assertFalse(json.contains("sampleCredential123"))
        assertFalse(json.contains("sk-abcdefghijklmnopqr1234"))
        assertTrue(json.contains("[REDACTED]"))
    }

    @Test fun missingOldToolRecordIsExplicitAndDoesNotPreventExport() {
        val state = source().copy(events = emptyList())
        val doc = codec.fromState(state, "history")
        assertNull(doc.timeline.single { it.id == "tool-entry" }.toolId)
        assertTrue(doc.timeline.single { it.id == "tool-entry" }.text.contains("不再保留此工具结果"))
    }

    @Test fun recoveryAndUnlinkedToolsRemainInContinuationAndExports() {
        val state = source().copy(timeline = source().timeline.filter { it.toolId == null },
            taskRecovery = "写入成功，后续请求网络失败", recoveryNotes = listOf("恢复后不要再次写入文件"))
        val doc = codec.fromState(state, "恢复记录")
        assertTrue(doc.handoff.contains("写入成功，后续请求网络失败"))
        assertTrue(doc.handoff.contains("恢复后不要再次写入文件"))
        val imported = codec.importedState(decode(encoded(doc)), "target")
        assertTrue(imported.contextSummary.contains("test failed"))
        assertTrue(imported.contextSummary.contains("恢复后不要再次写入文件"))
        assertTrue(codec.markdown(doc).contains("test failed"))
    }

    @Test fun sourceRetentionLimitsSurviveRoundTripAndRemainExplicit() {
        val doc = codec.fromState(source().copy(historyTrimmed = true), "保留记录")
        assertTrue(doc.limited)
        val decoded = decode(encoded(doc))
        assertTrue(decoded.limited)
        assertTrue(codec.importedState(decoded, "target").historyTrimmed)
        assertTrue(codec.importedState(decoded, "target").contextSummary.contains("不能把这些资料视为完整状态"))
        assertTrue(codec.summary(decoded).contains("不能代表完整状态"))
    }

    @Test fun rejectsUnknownSchemaMalformedUtf8TrailingJsonAndDuplicateRecordIdentity() {
        problem(AiConversationTransferProblem.UnsupportedVersion) { decode(JSONObject(encoded()).put("version", 2).toString()) }
        problem(AiConversationTransferProblem.InvalidFormat) { decode("{\"messages\":[]}") }
        problem(AiConversationTransferProblem.InvalidFormat) { decode(encoded() + " true") }
        problem(AiConversationTransferProblem.InvalidFormat) { codec.decode(byteArrayOf(0xc3.toByte(), 0x28), "bad.md") }
        problem(AiConversationTransferProblem.InvalidFormat) { decode(JSONObject(encoded()).apply {
            getJSONArray("timeline").put(getJSONArray("timeline").getJSONObject(0)) }.toString()) }
        problem(AiConversationTransferProblem.InvalidFormat) { decode(JSONObject(encoded()).apply {
            getJSONArray("timeline").getJSONObject(0).put("turn", 0.5) }.toString()) }
    }

    @Test fun rejectsExcessiveBytesDepthRecordCountsAndUnpersistableBodies() {
        problem(AiConversationTransferProblem.TooLarge) { codec.decode(ByteArray(AiConversationTransfer.MAX_BYTES + 1), "huge.md") }
        problem(AiConversationTransferProblem.TooLarge) { decode("{\"nested\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}") }
        problem(AiConversationTransferProblem.TooLarge) { decode(JSONObject(encoded()).put("events", JSONArray(List(65) { JSONObject() })).toString()) }
        problem(AiConversationTransferProblem.TooLarge) { codec.decode("x".repeat(512001).toByteArray(), "huge.md") }
        problem(AiConversationTransferProblem.TooLarge) { encoded(document().copy(timeline = listOf(AiEntry("user", "x".repeat(64001), role = AiRole.User)))) }
        problem(AiConversationTransferProblem.TooLarge) { encoded(document().copy(timeline = listOf(AiEntry("assistant", "x".repeat(256001), role = AiRole.Assistant)))) }
    }

    @Test fun rejectsInvalidPlansDanglingToolsEmptyDataAndUnsafeNumericUsage() {
        problem(AiConversationTransferProblem.InvalidFormat) { encoded(document().copy(plan = listOf(AiPlanStep("bad", "execute_now")))) }
        problem(AiConversationTransferProblem.InvalidFormat) { encoded(document().copy(events = emptyList())) }
        problem(AiConversationTransferProblem.Empty) { codec.decode("   ".toByteArray(), "empty.md") }
        problem(AiConversationTransferProblem.Empty) { encoded(TransferDocument("empty")) }
        problem(AiConversationTransferProblem.InvalidFormat) { encoded(document().copy(usage = listOf(TransferUsage(1, "model", 0, 0, Long.MAX_VALUE, 1, null, true)))) }
    }
}
