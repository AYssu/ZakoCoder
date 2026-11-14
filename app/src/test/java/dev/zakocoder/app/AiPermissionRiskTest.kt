package dev.zakocoder.app

import dev.zakocoder.app.ai.*
import org.junit.Assert.*
import org.junit.Test

class AiPermissionRiskTest {
    private fun risk(tool: AiTool, vararg args: Pair<String, String>): AiPermissionRisk {
        val fields = args.toMap()
        return AiPermissionRisks.classify(tool, action = fields["action"].orEmpty(), command = fields["command"].orEmpty(), url = fields["url"].orEmpty())
    }

    @Test fun builtinReadsAndPrevalidatedWorkspaceWritesHaveSeparateClasses() {
        assertEquals(AiPermissionRisk.Read, risk(AiTool.ReadFile))
        assertEquals(AiPermissionRisk.WorkspaceWrite, risk(AiTool.EditFile))
        assertEquals(AiPermissionRisk.Unknown, risk(AiTool.SendTerminalInput, "text" to "pwd"))
        assertEquals(AiPermissionRisk.Unknown, risk(AiTool.UseMcp, "method" to "tools/call"))
        assertEquals(AiPermissionRisk.Unknown, risk(AiTool.SshRemote, "action" to "read_file"))
    }

    @Test fun onlyLiteralShellBuiltinsAreAutomaticAndExternalExecutablesNeverTrustPath() {
        listOf("pwd", "echo hello", "printf '%s' hello", "true", "false", "echo 'hello\u2003world'", "echo 'hello\u00a0world'").forEach {
            assertEquals(it, AiPermissionRisk.Read, risk(AiTool.RunCommand, "command" to it))
        }
        listOf("cat src/main.kt", "cat 'src with space/main.kt'", "ls -la", "wc -l -- src/main.kt", "cat /etc/passwd", "cat ../secret", "cat src/link", "cat .env", "cat -", "cat", "ls /", "cat src/main.kt; curl x", "echo $(curl x)", "echo `id`", "cat src/*", "echo x > src/file", "pwd && curl x", "npm test", "python3 fix.py", "curl https://example.com", "bash -c pwd", "PATH=x pwd", "echo x\nid", "echo 'unterminated", "echo\u2003hello", "echo\u00a0hello").forEach {
            assertEquals(it, AiPermissionRisk.Unknown, risk(AiTool.RunCommand, "command" to it))
        }
    }

    @Test fun localPreviewExternalNavigationAndPageInteractionsAreDifferentRisks() {
        listOf("http://127.0.0.1:8000/", "http://localhost:5173/", "http://[::1]:3000/").forEach {
            assertEquals(it, AiPermissionRisk.WorkspaceWrite, risk(AiTool.Browser, "action" to "navigate", "url" to it))
        }
        assertEquals(AiPermissionRisk.NetworkRead, risk(AiTool.Browser, "action" to "navigate", "url" to "https://example.com"))
        listOf("javascript:alert(1)", "file:///etc/passwd", "http://user:secret@example.com", "not a url").forEach {
            assertEquals(it, AiPermissionRisk.Unknown, risk(AiTool.Browser, "action" to "navigate", "url" to it))
        }
        assertEquals(AiPermissionRisk.Read, risk(AiTool.Browser, "action" to "read"))
        assertEquals(AiPermissionRisk.Unknown, risk(AiTool.Browser, "action" to "click"))
    }
}
