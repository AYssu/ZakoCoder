package dev.zakocoder.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VirtualScreenCreationLeaseTest {
    @Test fun cancelledRequestDisposesLateDisplayEvenAfterAnotherRequestStarts() {
        val disposed = mutableListOf<String>()
        val old = VirtualScreenCreationLease<String> { disposed += it }
        old.cancel()
        val next = VirtualScreenCreationLease<String> { disposed += it }
        next.publish("next")
        assertThrows(IllegalStateException::class.java) { old.publish("late") }
        next.take("next")
        assertEquals(listOf("late"), disposed)
    }

    @Test fun timeoutAfterAllocationBeforeHandoffDisposesExactlyOnce() {
        var disposed = 0
        val creation = VirtualScreenCreationLease<Any> { disposed++ }
        val resource = Any()
        creation.publish(resource)
        creation.cancel()
        creation.cancel()
        assertThrows(IllegalStateException::class.java) { creation.take(resource) }
        assertEquals(1, disposed)
    }

    @Test fun completedHandoffIsOwnedByCallerInsteadOfCancelledRequest() {
        var disposed = 0
        val creation = VirtualScreenCreationLease<Any> { disposed++ }
        val resource = Any()
        creation.publish(resource)
        creation.take(resource)
        creation.cancel()
        assertEquals(0, disposed)
    }
}
