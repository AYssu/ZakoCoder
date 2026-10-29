package dev.zakocoder.app

import dev.zakocoder.app.git.publicCloneUrl
import dev.zakocoder.app.workflow.*
import org.junit.Assert.*
import org.junit.Test

class ProjectCommandTest {
    @Test fun cloneOnlyAcceptsPublicHttpsWithoutEmbeddedCredentials() {
        assertEquals("https://github.com/example/repo.git", publicCloneUrl("https://github.com/example/repo.git"))
        for (url in listOf("http://host/repo", "file:///tmp/repo", "ssh://host/repo", "https://user:pass@host/repo", "https://host/repo?token=hidden", "https://host/repo#secret", "https://host/repo\ncommand")) assertTrue(url, runCatching { publicCloneUrl(url) }.isFailure)
    }
    @Test fun shellArgumentsKeepQuotesDollarBackticksAndNewlinesLiteral() {
        assertEquals("'a'\\''b'", shellArgument("a'b"))
        val c = ProjectCommand("build", "printf done", "space ' dollar${'$'}", 1800)
        assertEquals("cd -- 'space '\\'' dollar${'$'}' &&\nprintf done", c.shell())
        assertEquals("printf done", ProjectCommand("test", "printf done").shell())
    }
    @Test fun dependencyInstallDoesNotClaimValidationConfigurationChanged() {
        val run = ProjectRun("fixture", "dependency", "npm install", 0, source = "dependency")
        assertEquals("not_configured_command", run.configuration(listOf(ProjectCommand("test", "npm install"))))
        assertEquals("unchanged", run.copy(kind = "test", source = "user", timeoutMillis = 1800000).configuration(listOf(ProjectCommand("test", "npm install"))))
        assertEquals("changed", run.copy(kind = "test").configuration(listOf(ProjectCommand("test", "npm test"))))
    }
}
