package dev.zakocoder.app.settings

import android.annotation.SuppressLint
import android.content.Context

/** Installation-local setup state, independent of projects, credentials and runtime contents. */
data class OnboardingProgress(
    val completed: Boolean = false,
    val step: Int = 0,
    val environmentConfirmed: Boolean = false,
    val purpose: String = "",
    val selectedBundleIds: Set<String> = emptySet(),
    val selectedSkillIds: Set<String> = dev.zakocoder.app.ai.AiBuiltinSkills.defaults.toSet(),
) {
    val required: Boolean get() = !completed
    val allowsBundledInitialization: Boolean get() = environmentConfirmed
}

class OnboardingRepository(context: Context) {
    private val preferences = context.getSharedPreferences("first-run-setup", Context.MODE_PRIVATE)

    /** Existing installations bypass setup without changing their files or preferences. */
    fun load(hasExistingSetup: Boolean): OnboardingProgress {
        if (!preferences.contains("initialized")) {
            return OnboardingProgress(completed = hasExistingSetup, environmentConfirmed = hasExistingSetup).also(::save)
        }
        return OnboardingProgress(
            completed = preferences.getBoolean("completed", false),
            step = preferences.getInt("step", 0).coerceIn(0, LAST_STEP),
            environmentConfirmed = preferences.getBoolean("environment-confirmed", false),
            purpose = preferences.getString("purpose", "").orEmpty(),
            selectedBundleIds = preferences.getStringSet("bundles", emptySet()).orEmpty().toSet(),
            selectedSkillIds = preferences.getStringSet("skills", dev.zakocoder.app.ai.AiBuiltinSkills.defaults.toSet()).orEmpty().toSet(),
        )
    }

    /** Called from IO; synchronous persistence ensures a resumed process sees the last step. */
    @SuppressLint("UseKtx") // Check commit() success before leaving setup; KTX edit discards its result.
    fun save(progress: OnboardingProgress) {
        check(preferences.edit().apply {
            putBoolean("initialized", true)
            putBoolean("completed", progress.completed)
            putInt("step", progress.step.coerceIn(0, LAST_STEP))
            putBoolean("environment-confirmed", progress.environmentConfirmed)
            putString("purpose", progress.purpose.take(256))
            putStringSet("bundles", progress.selectedBundleIds.toSet())
            putStringSet("skills", progress.selectedSkillIds.toSet())
        }.commit())
    }

    fun defaultSkills(): List<String> = preferences.getStringSet(
        "skills", dev.zakocoder.app.ai.AiBuiltinSkills.defaults.toSet()
    ).orEmpty().filter(dev.zakocoder.app.ai.AiBuiltinSkills::contains).sorted()

    companion object { const val LAST_STEP = 6 }
}
