package dev.zakocoder.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualScreenIsolationTest {
    @Test fun captureRequiresKnownNonSecureFlagsOnTheSelectedDisplay() {
        val physical = window(0, "2000")
        assertTrue(virtualDisplayCaptureAllowed(physical + window(7, "81810100"), 7))
        assertFalse(virtualDisplayCaptureAllowed(window(7, "81812100"), 7))
        assertFalse(virtualDisplayCaptureAllowed(window(7, "SECURE HARDWARE_ACCELERATED"), 7))
        assertTrue(virtualDisplayCaptureAllowed(window(7, "NOT_FOCUSABLE HARDWARE_ACCELERATED"), 7))
        assertFalse(virtualDisplayCaptureAllowed(window(7, "unavailable"), 7))
        assertFalse(virtualDisplayCaptureAllowed(window(0, "81810100"), 7))
        assertFalse(virtualDisplayCaptureAllowed(window(7, "81810100"), 0))
    }

    @Test fun displayIdMatchingDoesNotAcceptAnUnrelatedDisplayOrUnknownDump() {
        assertFalse(virtualDisplayCaptureAllowed(window(17, "81810100"), 7))
        assertFalse(virtualDisplayCaptureAllowed("mDisplayId=7\nSecure details unavailable", 7))
        assertFalse(virtualDisplayCaptureAllowed(window(7, "81810100") + window(7, "2000"), 7))
    }

    @Test(expected = IllegalStateException::class) fun physicalDisplayIsNeverAnInputTarget() {
        requireVirtualDisplayId(0)
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedDisplayDoesNotAllocateBuffers() {
        validateVirtualScreenSize(1920, 2560, 320)
    }

    @Test fun phoneSizedDisplayIsAccepted() {
        validateVirtualScreenSize(720, 1600, 320)
        assertTrue(requireVirtualDisplayId(7) == 7)
    }

    private fun window(id: Int, flags: String) = """
      Window #0 Window{abc u0 dev.fixture/.MainActivity}:
        mDisplayId=$id rootTaskId=5
        mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION fl=$flags}
    """.trimIndent() + "\n"
}
