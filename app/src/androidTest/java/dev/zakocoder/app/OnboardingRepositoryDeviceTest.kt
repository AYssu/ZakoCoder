package dev.zakocoder.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakocoder.app.ai.AiBuiltinSkills
import dev.zakocoder.app.settings.OnboardingProgress
import dev.zakocoder.app.settings.OnboardingRepository
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Every test owns a preference namespace; no live account, workspace or environment is touched. */
class OnboardingRepositoryDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "setup-check-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("$namespace-$name", Context.MODE_PRIVATE)
    }

    @After fun cleanup() {
        assertTrue(context.getSharedPreferences("first-run-setup", Context.MODE_PRIVATE).edit().clear().commit())
    }

    @Test fun freshInstallRequiresSetupAndCannotAutoInstall() {
        val state = OnboardingRepository(context).load(hasExistingSetup = false)
        assertTrue(state.required)
        assertFalse(state.completed)
        assertFalse(state.allowsBundledInitialization)
        assertEquals(0, state.step)
        assertEquals(AiBuiltinSkills.defaults.toSet(), state.selectedSkillIds)
    }

    @Test fun existingInstallationMigratesWithoutInterruptingUser() {
        val migrated = OnboardingRepository(context).load(hasExistingSetup = true)
        assertFalse(migrated.required)
        assertTrue(migrated.allowsBundledInitialization)
        assertEquals(migrated, OnboardingRepository(context).load(hasExistingSetup = false))
    }

    @Test fun incompleteSetupSurvivesRestartAndNewlyCreatedProject() {
        val repository = OnboardingRepository(context)
        val fresh = repository.load(hasExistingSetup = false)
        val saved = fresh.copy(step = 4, purpose = "web", selectedBundleIds = setOf("git", "node"),
            selectedSkillIds = setOf("builtin:lean-build"), environmentConfirmed = true)
        repository.save(saved)
        // Adding a project/model during setup must not silently finish the remaining steps.
        val restored = OnboardingRepository(context).load(hasExistingSetup = true)
        assertEquals(saved, restored)
        assertTrue(restored.required)
        assertEquals(listOf("builtin:lean-build"), OnboardingRepository(context).defaultSkills())
    }

    @Test fun disablingAllSkillsStaysEmptyAfterRecreation() {
        val repository = OnboardingRepository(context)
        repository.save(repository.load(false).copy(selectedSkillIds = emptySet()))
        val recreated = OnboardingRepository(context)
        assertTrue(recreated.load(false).selectedSkillIds.isEmpty())
        assertTrue(recreated.defaultSkills().isEmpty())
    }

    @Test fun persistedStepIsClampedAtBothEnds() {
        val repository = OnboardingRepository(context)
        repository.save(OnboardingProgress(step = -12))
        assertEquals(0, OnboardingRepository(context).load(false).step)
        repository.save(OnboardingProgress(step = 900))
        assertEquals(OnboardingRepository.LAST_STEP, OnboardingRepository(context).load(false).step)
    }

    @Test fun completingWithoutEnvironmentConfirmationCannotTriggerLargeInstall() {
        val repository = OnboardingRepository(context)
        repository.save(repository.load(false).copy(completed = true, step = OnboardingRepository.LAST_STEP))
        val completed = OnboardingRepository(context).load(false)
        assertFalse(completed.required)
        assertFalse(completed.allowsBundledInitialization)
        repository.save(completed.copy(environmentConfirmed = true))
        assertTrue(OnboardingRepository(context).load(false).allowsBundledInitialization)
    }
}
