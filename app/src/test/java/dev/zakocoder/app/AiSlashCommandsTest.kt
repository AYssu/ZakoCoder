package dev.zakocoder.app

import dev.zakocoder.app.ai.AiSlashCommandId
import dev.zakocoder.app.ai.AiSlashCommands
import dev.zakocoder.app.ai.AiSlashParseResult
import org.junit.Assert.*
import org.junit.Test

class AiSlashCommandsTest {
    @Test fun eachBuiltinHasAStableUniqueTokenAndParsesCaseInsensitively() {
        assertEquals(AiSlashCommandId.entries.toSet(), AiSlashCommands.catalog.map { it.id }.toSet())
        assertEquals(AiSlashCommandId.entries.size, AiSlashCommands.catalog.map { it.token }.distinct().size)
        AiSlashCommands.catalog.forEach { entry ->
            assertEquals(AiSlashParseResult.Command(entry.id, ""), AiSlashCommands.parse(" /${entry.token.uppercase()} \t"))
        }
    }

    @Test fun conversationTransferCommandsUseEnglishTokensAndStayLocal() {
        assertEquals(AiSlashParseResult.Command(AiSlashCommandId.Export, ""), AiSlashCommands.parse("/export"))
        assertEquals(AiSlashParseResult.Command(AiSlashCommandId.Import, ""), AiSlashCommands.parse("/import"))
        assertEquals(AiSlashParseResult.Command(AiSlashCommandId.Recap, ""), AiSlashCommands.parse("/recap"))
        assertEquals(AiSlashParseResult.Command(AiSlashCommandId.Recap, ""), AiSlashCommands.parse("/RECAP"))
        assertEquals(AiSlashParseResult.Unknown("导出摘要"), AiSlashCommands.parse("/导出摘要"))
        assertTrue(AiSlashCommands.suggestions("/导出").isEmpty())
        assertEquals(listOf(AiSlashCommandId.Recap), AiSlashCommands.suggestions("/rec").map { it.id })
    }

    @Test fun ordinaryMessagesUrlsAndAbsolutePathsStayOrdinaryText() {
        listOf("", " \r\n", "帮我 /compact", "https://example.com/help", "/usr/local/bin/node",
            "/home/user/project\n继续分析", "//server/share").forEach {
            assertEquals(it, AiSlashParseResult.None, AiSlashCommands.parse(it))
        }
    }

    @Test fun unknownActionsAreExplicitAndNeverTurnIntoTasks() {
        assertEquals(AiSlashParseResult.Unknown("unknown"), AiSlashCommands.parse(" /unknown arg"))
        assertEquals(AiSlashParseResult.Unknown(""), AiSlashCommands.parse("/"))
        assertEquals(AiSlashParseResult.Unknown("help;rm"), AiSlashCommands.parse("/help;rm /tmp/file"))
    }

    @Test fun commandArgumentsPreserveMultiLineTextAndCrLf() {
        assertEquals(AiSlashParseResult.Command(AiSlashCommandId.Compact, "保留测试结论\r\n以及报错\n  保留缩进  "),
            AiSlashCommands.parse("\r\n /compact\t保留测试结论\r\n以及报错\n  保留缩进  "))
        assertEquals(AiSlashParseResult.Command(AiSlashCommandId.Rename, "新的 /help 名称"),
            AiSlashCommands.parse("/rename 新的 /help 名称"))
    }

    @Test fun skillCommandAndExplicitSkillAreDifferentActions() {
        assertEquals(AiSlashParseResult.Command(AiSlashCommandId.Skill, "前端"), AiSlashCommands.parse("/skill 前端"))
        assertEquals(AiSlashParseResult.Skill("frontend-design", "重做页面\r\n保留结构"),
            AiSlashCommands.parse("/skill:frontend-design 重做页面\r\n保留结构"))
        assertEquals(AiSlashParseResult.Skill("调查问题", ""), AiSlashCommands.parse("/SKILL:调查问题"))
    }

    @Test fun quotedSkillNamesSupportSpacesAndUnicodeWithoutShellInterpretation() {
        assertEquals(AiSlashParseResult.Skill("前端 设计", "任务\n  子项  "),
            AiSlashCommands.parse("/skill:\"前端 设计\" 任务\n  子项  "))
        assertEquals(AiSlashParseResult.Skill("safe refactor", "run"), AiSlashCommands.parse("/skill:'safe refactor' run"))
        assertEquals(AiSlashParseResult.Skill("a\"b", "x"), AiSlashCommands.parse("/skill:\"a\\\"b\" x"))
        assertEquals(AiSlashParseResult.Skill("$(touch marker)", "echo hello"),
            AiSlashCommands.parse("/skill:\"\$(touch marker)\" echo hello"))
    }

    @Test fun skillNamesWithPathSeparatorsAreLiteralNamesRatherThanFilesystemPaths() {
        assertEquals(AiSlashParseResult.Skill("UI/UX Design", "Improve the page"),
            AiSlashCommands.parse("/skill:\"UI/UX Design\" Improve the page"))
        assertEquals(AiSlashParseResult.Skill("Windows\\Shell", "Inspect"),
            AiSlashCommands.parse("/skill:\"Windows\\\\Shell\" Inspect"))
        assertEquals(AiSlashParseResult.Skill("../skill", "task"), AiSlashCommands.parse("/skill:../skill task"))
        assertEquals(AiSlashParseResult.None, AiSlashCommands.parse("/usr/local/bin"))
    }

    @Test fun malformedSkillSelectionIsNeverSilentlySubmittedAsAMessage() {
        listOf("/skill:", "/skill: task", "/skill:\"\"").forEach {
            assertEquals(it, AiSlashParseResult.Invalid(AiSlashParseResult.Reason.MissingSkillName), AiSlashCommands.parse(it))
        }
        listOf("/skill:\"unclosed", "/skill:\"name\"suffix task", "/skill:\"bad\\nname\" task",
            "/skill:\" name \" task", "/skill:\"multi\nline\" task", "/skill:\"tab\tname\" task").forEach {
            assertEquals(it, AiSlashParseResult.Invalid(AiSlashParseResult.Reason.MalformedSkillName), AiSlashCommands.parse(it))
        }
    }

    @Test fun hiddenControlAndDirectionOverridesAreRejectedBeforeDispatch() {
        listOf('\u0000', '\u001b', '\u007f', '\u0085', '\u202e', '\u2066').forEach { control ->
            assertEquals(AiSlashParseResult.Invalid(AiSlashParseResult.Reason.ControlCharacter),
                AiSlashCommands.parse("/compact keep${control}this"))
            assertEquals(AiSlashParseResult.Invalid(AiSlashParseResult.Reason.ControlCharacter),
                AiSlashCommands.parse("/skill:\"name${control}\" task"))
        }
    }

    @Test fun autocompleteAcceptsSlashOrPlainPrefixesAndDoesNotIncludeArguments() {
        assertEquals(AiSlashCommands.catalog, AiSlashCommands.suggestions("/"))
        assertEquals(AiSlashCommands.catalog, AiSlashCommands.suggestions(""))
        assertEquals(listOf(AiSlashCommandId.Model, AiSlashCommandId.Mcp), AiSlashCommands.suggestions(" /M").map { it.id })
        assertEquals(listOf(AiSlashCommandId.Sessions, AiSlashCommandId.Skill, AiSlashCommandId.Stop),
            AiSlashCommands.suggestions("s").map { it.id })
        listOf("/model ", "/skill:name", "/usr/bin", "missing").forEach {
            assertTrue(it, AiSlashCommands.suggestions(it).isEmpty())
        }
    }
}
