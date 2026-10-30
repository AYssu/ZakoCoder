package dev.zakocoder.app.ui

import org.junit.Assert.*
import org.junit.Test

class AiProjectPathsTest {
    private val project = "project-123"

    @Test fun resolvesProjectRelativeAndGuestAbsoluteReferences() {
        assertEquals("app/build/outputs/apk/debug/app-debug.apk", AiProjectPaths.resolve("app/build/outputs/apk/debug/app-debug.apk", project))
        assertEquals("src/Main.kt", AiProjectPaths.resolve("/workspace/$project/src/Main.kt:12:3", project))
        assertEquals("测试 目录/文件.kt", AiProjectPaths.resolve("./测试 目录/文件.kt", project))
        assertEquals("README.md", AiProjectPaths.resolve("README.md", project))
    }

    @Test fun rejectsTraversalForeignWorkspaceAndNonProjectDestinations() {
        listOf("../secret", "app/../../secret", "app/./src", "/etc/passwd", "/workspace/other/src/Main.kt", "https://example.com/app/main.kt", "file:///etc/passwd", "app%2Fsecret", "app\\secret", "~/secret", "app//main.kt", "src/main.kt\n", "app/main.kt?download=1").forEach {
            assertNull(it, AiProjectPaths.resolve(it, project))
        }
        assertNull(AiProjectPaths.resolve("app/main.kt", null))
        assertNull(AiProjectPaths.resolve("普通说明", project))
    }

    @Test fun findsTextAndCodeStyleReferencesWithoutStealingUrlsOrAbsolutePaths() {
        val text = "修改后的文件 app/build/outputs/apk/debug/app-debug.apk，以及 `src/Main.kt:24` 和 README.md。"
        val refs = AiProjectPaths.references(text, project)
        assertEquals(listOf("app/build/outputs/apk/debug/app-debug.apk", "src/Main.kt", "README.md"), refs.map { it.relative })
        assertEquals("src/Main.kt:24", text.substring(refs[1].start, refs[1].end))
        assertTrue(AiProjectPaths.references("https://example.com/app/main.kt /etc/passwd /workspace/other/app/main.kt", project).isEmpty())
    }
}
