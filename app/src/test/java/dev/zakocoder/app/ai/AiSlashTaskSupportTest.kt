package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Test

class AiSlashTaskSupportTest {
    @Test fun explicitSkillIsTaskOnlyAndDeduplicatesSelection() {
        val enabled = listOf("builtin:lean-build")
        val chosen = AiSkill("builtin:audit", "audit", "Check changes")
        val selected = explicitTaskSkills(enabled, listOf(chosen), chosen.path)
        assertEquals(listOf("builtin:lean-build", chosen.path), selected)
        assertEquals(listOf("builtin:lean-build"), enabled)
        assertEquals(selected, explicitTaskSkills(selected, listOf(chosen), chosen.path))
        assertSame(enabled, explicitTaskSkills(enabled, emptyList(), null))
    }

    @Test fun explicitSelectionRejectsStaleInvalidAndOverBudgetSkills() {
        val chosen = AiSkill("builtin:audit", "audit", "Check changes")
        assertThrows(IllegalArgumentException::class.java) { explicitTaskSkills(emptyList(), emptyList(), chosen.path) }
        assertThrows(IllegalArgumentException::class.java) { explicitTaskSkills(emptyList(), listOf(chosen.copy(valid = false)), chosen.path) }
        assertThrows(IllegalArgumentException::class.java) {
            explicitTaskSkills(List(AiSkills.MAX_ENABLED) { "skill-$it" }, listOf(chosen), chosen.path)
        }
    }

    @Test fun explicitExpressionStyleReplacesOtherStyleWithoutChangingDefaults() {
        val styles = AiBuiltinSkills.expressionStyles.toList()
        assertTrue(styles.size >= 2)
        val enabled = listOf(styles.first(), "builtin:lean-build")
        val chosen = AiSkill(styles.last(), "expression", "Expression workflow")
        assertEquals(listOf("builtin:lean-build", chosen.path), explicitTaskSkills(enabled, listOf(chosen), chosen.path))
        assertEquals(styles.first(), enabled.first())
    }
}
