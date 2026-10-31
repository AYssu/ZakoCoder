package dev.zakocoder.app.git

import org.junit.Assert.*
import org.junit.Test

class GitProtocolTest {
    @Test fun acceptsOnlyExplicitNetworkUrls() {
        assertEquals("https://github.com/team/repo.git", gitRemoteUrl("https://github.com/team/repo.git"))
        assertEquals("ssh://git@github.com/team/repo.git", gitRemoteUrl("ssh://git@github.com/team/repo.git"))
        assertEquals("ssh://git@host:2222/team/repo", gitRemoteUrl("ssh://git@host:2222/team/repo"))
        for (value in listOf("git@host:repo", "file:///tmp/repo", "ext::echo command", "http://host/repo", "https://user:password@host/repo", "ssh://user:password@host/repo", "https://host/repo?key=value", "https://host/repo#value", "https://host/repo\ncommand", "https://host", "ssh://-oProxyCommand=cmd@host/repo")) {
            assertTrue(value, runCatching { gitRemoteUrl(value) }.isFailure)
        }
    }
    @Test fun branchValidationRejectsOptionRevisionAndReflogExpressions() {
        for (value in listOf("main", "feat/git-workflow", "测试分支")) assertEquals(value, gitBranchName(value))
        for (value in listOf("", "-main", "main~1", "main^", "main:other", "main..other", "main@{1}", "a\\b", "a b", ".hidden", "a/.hidden", "a.lock", "a//b", "a/", "a.", "@")) assertTrue(value, runCatching { gitBranchName(value) }.isFailure)
    }
    @Test fun literalPathsPreserveSpacesAndWildcardFileNames() {
        assertTrue(gitSafePath("src/file name.kt")); assertTrue(gitSafePath("*.txt")); assertTrue(gitSafePath(":(top)*"))
        for (path in listOf("", "/etc/passwd", "../file", "src/../file", ".git/config", "src/.git/config", "line\nfile")) assertFalse(path, gitSafePath(path))
    }
    @Test fun parsesUnbornRepositoryBranchAndUntrackedFiles() {
        val result = parseGitStatus("# branch.oid (initial)\u0000# branch.head main\u0000? file name.txt\u0000")
        assertEquals("main", result.branch); assertEquals("", result.upstream); assertEquals(0, result.ahead)
        assertEquals(listOf(GitEntry("file name.txt", '?', '?')), result.entries)
    }
    @Test fun parsesTrackingCountsAndBothIndexes() {
        val result = parseGitStatus("# branch.head feat/a\u0000# branch.upstream origin/feat/a\u0000# branch.ab +12 -34\u00001 MM N... 100644 100644 100644 abc def src/a.kt\u0000")
        assertEquals("origin/feat/a", result.upstream); assertEquals(12, result.ahead); assertEquals(34, result.behind)
        assertEquals(GitEntry("src/a.kt", 'M', 'M'), result.entries.single())
    }
    @Test fun parsesRenameSourceWithoutCreatingPhantomEntry() {
        val result = parseGitStatus("2 R. N... 100644 100644 100644 abc def R100 renamed file.kt\u0000old file.kt\u0000? other\u0000")
        assertEquals(listOf(GitEntry("renamed file.kt", 'R', ' '), GitEntry("other", '?', '?')), result.entries)
    }
    @Test fun parsesUnmergedAndDetachedState() {
        val result = parseGitStatus("# branch.head (detached)\u0000u UU N... 100644 100644 100644 100644 abc def ghi conflict.kt\u0000")
        assertTrue(result.detached); assertEquals("", result.branch); assertEquals(GitEntry("conflict.kt", 'U', 'U'), result.entries.single())
    }
    @Test fun unsupportedControlCharacterPathsMarkSnapshotIncomplete() {
        val result = parseGitStatus("? filename\nwith-newline\u0000")
        assertTrue(result.entries.isEmpty()); assertTrue(result.unsafeEntries)
    }
    @Test fun refusesMalformedAndOversizedStatus() {
        assertTrue(runCatching { parseGitStatus("1 M invalid\u0000") }.isFailure)
        assertTrue(runCatching { parseGitStatus("x".repeat(1_000_000)) }.isFailure)
    }
    @Test fun classifiesActionableFailures() {
        assertEquals(GitFailure.Authentication, gitFailure("fatal: Authentication failed"))
        assertEquals(GitFailure.Network, gitFailure("Could not resolve host"))
        assertEquals(GitFailure.NonFastForward, gitFailure("Not possible to fast-forward, aborting."))
        assertEquals(GitFailure.DirtyWorktree, gitFailure("Your local changes would be overwritten"))
        assertEquals(GitFailure.Conflict, gitFailure("Automatic merge failed; fix conflicts"))
        assertEquals(GitFailure.Unknown, gitFailure("unexpected unknown failure"))
    }
    @Test fun capturedOutputIsBoundedAndObviousCredentialsAreRedacted() {
        assertTrue(safeGitOutput("x".repeat(30000)).length <= 24000)
        val result = safeGitOutput("fatal https://name:password@host/repo\nAuthorization: Bearer private-value")
        assertFalse(result.contains("password")); assertFalse(result.contains("private-value")); assertTrue(result.contains("[redacted]"))
    }
    @Test fun selectedRemoteHasExplicitBranchAndTrackingMeaning() {
        val state = GitState(branch = "work", upstream = "upstream/main", remotes = listOf(GitRemote("origin", "https://fork.invalid/repo"), GitRemote("upstream", "https://source.invalid/repo")))
        assertEquals("upstream", state.preferredRemote(""))
        assertEquals("origin", state.preferredRemote("origin"))
        assertEquals("main", state.destinationBranch("upstream"))
        assertEquals("work", state.destinationBranch("origin"))
        assertTrue(state.tracksRemote("upstream")); assertFalse(state.tracksRemote("origin"))
    }
    @Test fun networkPrefixPinsTlsCaAndClearsScopedHeaders() {
        val remote = "https://example.invalid/team/repository.git"
        val prefix = gitNetworkPrefix(remote)
        assertTrue(prefix.contains("GIT_SSL_NO_VERIFY=0"))
        for (option in listOf("http.sslVerify=true", "http.$remote.sslVerify=true", "http.$remote.sslCAInfo=/etc/ssl/certs/ca-certificates.crt", "http.extraHeader=", "http.$remote.extraHeader=")) assertTrue(option, prefix.contains(option))
        assertTrue(runCatching { gitNetworkPrefix("https://user:secret@example.invalid/repo") }.isFailure)
    }
}
