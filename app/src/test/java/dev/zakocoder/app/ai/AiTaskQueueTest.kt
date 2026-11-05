package dev.zakocoder.app.ai

import org.junit.Assert.*
import org.junit.Test

class AiTaskQueueTest {
    @Test fun keepsPendingIntentWhenTrimmingReceipts() {
        val pending = AiQueuedMessage(id = "pending", text = "不要改文件", kind = AiQueuedKind.Steer, turn = 1, created = 1)
        val receipts = (1..62).map { AiQueuedMessage(id = "receipt-$it", text = "已处理", kind = AiQueuedKind.FollowUp,
            turn = it, created = it.toLong() + 1, status = AiQueuedStatus.Applied) }
        val added = AiTaskQueue.add(receipts + pending, AiQueuedMessage(text = "运行测试", kind = AiQueuedKind.FollowUp, turn = 62))
        assertTrue(added.contains(pending))
        assertEquals(2, added.count { it.status == AiQueuedStatus.Pending })
        assertTrue(added.size <= 64)
    }
    @Test fun capsPendingWorkWithoutSilentlyDroppingIt() {
        val pending = (1..16).map { AiQueuedMessage(text = "任务 $it", kind = AiQueuedKind.FollowUp, turn = 1) }
        assertThrows(IllegalArgumentException::class.java) {
            AiTaskQueue.add(pending, AiQueuedMessage(text = "第17个任务", kind = AiQueuedKind.Steer, turn = 1))
        }
        assertEquals(16, pending.size)
    }
    @Test fun rejectsBlankAndOversizedInstructions() {
        for (text in listOf("  \n", "x".repeat(16001))) assertThrows(IllegalArgumentException::class.java) {
            AiTaskQueue.add(emptyList(), AiQueuedMessage(text = text, kind = AiQueuedKind.Steer, turn = 1))
        }
    }
    @Test fun interruptedTaskRecoveryRetainsSupplementalIntent() {
        val state = AiState(turn = 1, taskPrompt = "原任务", timeline = listOf(
            AiEntry("first", text = "原任务", role = AiRole.User, turn = 1),
            AiEntry("supplement", text = "不要修改配置", role = AiRole.User, turn = 1)))
        assertTrue(recoveryNote(state, AiFailure.Interrupted).contains("不要修改配置"))
    }
}
