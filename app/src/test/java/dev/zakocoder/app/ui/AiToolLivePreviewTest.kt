package dev.zakocoder.app.ui

import org.junit.Assert.*
import org.junit.Test
import dev.zakocoder.app.ai.toolArgumentLine
import dev.zakocoder.app.ai.liveCallDraft

class AiToolLivePreviewTest {
    @Test fun incompleteCommandAdvancesToNewestDecodedLine() {
        assertEquals("git diff && tail build.log", toolArgumentLine("""{"command":"git diff && tail build.log"""))
        assertEquals("tail build.log", toolArgumentLine("""{"command":"git diff\ntail build.log"""))
        assertEquals("git diff", toolArgumentLine("""{"command":"git diff\n"""))
    }
    @Test fun fileContentPreviewShowsCodeInsteadOfEnvelope() {
        assertEquals("int main() {", toolArgumentLine("""{"path":"main.cpp","content":"#include <iostream>\nint main() {"""))
        assertEquals("main.cpp", toolArgumentLine("""{"path":"main.cpp"""))
        assertEquals("return \"你好\";", toolArgumentLine("""{"content":"return \"\u4f60\u597d\";"""))
    }
    @Test fun fieldOrderDoesNotReplaceContentWithPath() {
        assertEquals("new line", toolArgumentLine("""{"content":"new line","path":"later.cpp"}"""))
        assertEquals("git diff", toolArgumentLine("""{"reason":"content: something","command":"git diff","path":"x"}"""))
    }
    @Test fun editPreviewShowsReplacementCodeAndFollowsNewestReplacement() {
        val arguments = """{"path":"main.cpp","edits":[{"old_text":"old code","new_text":"first line\nnew code"},{"old_text":"second","new_text":"latest code"""
        assertEquals("latest code", liveCallDraft(0, "edit_file", arguments).previewLine)
        assertEquals("main.cpp", toolArgumentLine(arguments))
        assertEquals("old code", liveCallDraft(0, "edit_file", """{"path":"main.cpp","edits":[{"old_text":"old code","new_text":""}]}""").previewLine)
        assertEquals("return \"new\";", liveCallDraft(0, "edit_file", """{"edits":[{"new_text":"return \"new\";","old_text":"old"}],"path":"main.cpp"}""").previewLine)
    }
    @Test fun longWriteUsesBoundedLiveTailAndDoesNotExposeJsonSuffix() {
        assertEquals("new line", toolArgumentLine("""{"content":"${"x".repeat(65000)}\nnew line","path":"later.cpp","reason":"done"}"""))
        val draft = liveCallDraft(0, "write_file", """{"content":"${"x".repeat(65000)}\nnew line","path":"later.cpp"}""")
        assertEquals("new line", draft.previewLine)
        assertEquals(64000, draft.arguments.length)
        assertEquals(1024, draft.argumentTail.length)
        assertEquals(120, toolArgumentLine("""{"content":"${"a".repeat(500)}""").length)
        assertEquals("", toolArgumentLine("""{"content":"\u12"""))
    }
}
