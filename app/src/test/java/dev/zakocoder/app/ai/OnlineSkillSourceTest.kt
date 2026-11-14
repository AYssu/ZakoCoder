package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Test

class OnlineSkillSourceTest {
    @Test fun rootRepositoryUsesDefaultRefAndSupportsDotGitSuffix() {
        assertEquals(OnlineSkillSource("example", "skills", null, ""), OnlineSkillSource.parse("https://github.com/example/skills.git/"))
    }
    @Test fun treeLinkSeparatesSingleSegmentRefAndSkillDirectory() {
        assertEquals(OnlineSkillSource("example", "skills", "main", "skills/frontend"),
            OnlineSkillSource.parse("https://github.com/example/skills/tree/main/skills/frontend"))
    }
    @Test fun complexRefRequiresExplicitFieldOnRootUrl() {
        assertEquals("feature/new-skills", OnlineSkillSource.parse("https://github.com/example/skills", "feature/new-skills").ref)
        assertInvalid("https://github.com/example/skills/tree/main/frontend", "feature/new-skills")
    }
    @Test fun credentialsPortsQueriesAndEncodedSeparatorsAreRejected() {
        listOf("http://github.com/example/skills", "https://user:password@github.com/example/skills", "https://github.com:443/example/skills",
            "https://github.com/example/skills?token=private", "https://github.com/example/skills#readme",
            "https://github.com/example/skills/tree/main%2Fprivate/frontend", "https://github.com.evil.example/example/skills",
            "file:///home/example/skills").forEach { assertInvalid(it) }
    }
    @Test fun traversalAndUnsupportedGithubPagesAreRejected() {
        listOf("https://github.com/example/skills/tree/main/../private", "https://github.com/example/skills/blob/main/SKILL.md",
            "https://github.com/example/skills/tree/main/%2e%2e/private", "https://github.com/example/skills/tree/main/unsafe%5cpath")
            .forEach { assertInvalid(it) }
    }
    private fun assertInvalid(url: String, ref: String = "") {
        try { OnlineSkillSource.parse(url, ref); fail("Expected invalid repository URL") }
        catch (failure: OnlineSkillException) { assertEquals(OnlineSkillProblem.InvalidUrl, failure.problem) }
    }
}
