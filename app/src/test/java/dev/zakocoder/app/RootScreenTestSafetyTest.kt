package dev.zakocoder.app

import dev.zakocoder.app.screentest.RootScreenTestBackend
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class RootScreenTestSafetyTest {
    @Test fun systemTimeoutExitsAreNotReportedAsUiRejection() {
        assertTrue(RootScreenTestBackend.isTimeoutExit(124))
        assertFalse(RootScreenTestBackend.isTimeoutExit(137))
        listOf(0, 1, 2, 126, 127, 143).forEach { assertFalse(RootScreenTestBackend.isTimeoutExit(it)) }
    }

    @Test fun modelTextRemainsOneLiteralShellArgumentAcrossQuotesAndSubstitutionSyntax() {
        listOf("", "hello world", "'; printf INJECTED; #", "$(printf injected)", "`printf injected`", "a\nb", "a\\b", "100% s", "中文").forEach { text ->
            val child = ProcessBuilder("/bin/sh", "-c", "printf '%s' " + RootScreenTestBackend.shellQuote(text)).start()
            try {
                assertTrue(child.waitFor(3, TimeUnit.SECONDS))
                assertEquals(0, child.exitValue())
                assertEquals(text, child.inputStream.bufferedReader().readText())
                assertEquals("", child.errorStream.bufferedReader().readText())
            } finally { child.destroyForcibly() }
        }
    }
    @Test fun nodeTokensRejectAlternateRepresentationsAndOverflowInsteadOfAddressingOtherNodes() {
        assertEquals(0, RootScreenTestBackend.parseNodeIndex("n1"))
        assertEquals(79, RootScreenTestBackend.parseNodeIndex("n80"))
        listOf("1", "n0", "n-1", "n01", " n1", "n1 ", "n1\n", "n2147483648", "n1; input tap 0 0").forEach {
            assertNull(it, RootScreenTestBackend.parseNodeIndex(it))
        }
    }
    @Test fun hostDeadlineExpiresBeforeSuEverExecutesItsInnerTimeout() {
        val start = TimeUnit.SECONDS.toNanos(100)
        assertFalse(RootScreenTestBackend.deadlineExceeded(start, start + TimeUnit.SECONDS.toNanos(44), 45))
        assertTrue(RootScreenTestBackend.deadlineExceeded(start, start + TimeUnit.SECONDS.toNanos(45), 45))
        assertTrue(RootScreenTestBackend.deadlineExceeded(start, start + TimeUnit.SECONDS.toNanos(60), 45))
    }
    private fun window(index: Int, id: String, title: String, bounds: String,
                       visible: Boolean = true, surface: Boolean = true): String = """
        Window #$index Window{$id u0 $title}:
          mHasSurface=$surface
          isOnScreen=$visible
          isVisible=$visible
          mFrame=$bounds
    """.trimIndent() + "\n"
    private fun focused(vararg windows: String): String = windows.joinToString("") +
        "mCurrentFocus=Window{target u0 dev.example.fixture/.MainActivity}\n"
    private fun uncovered(dump: String, top: Int = 100, bottom: Int = 300) =
        RootScreenTestBackend.isUncoveredWindowRegion(dump, "dev.example.fixture", 100, top, 300, bottom)

    @Test fun visibleSystemBarsOutsideActionRegionAreAllowedButKeyboardCoveringItIsRejected() {
        val dump = focused(
            window(0, "status", "StatusBar", "[0,0][1080,50]"),
            window(1, "navigation", "NavigationBar", "[0,950][1080,1000]"),
            window(2, "ime", "InputMethod", "[0,600][1080,950]"),
            window(3, "target", "dev.example.fixture/.MainActivity", "[0,50][1080,950]"))
        assertTrue(uncovered(dump))
        assertFalse(uncovered(dump, 650, 850))
    }
    @Test fun nonFocusableTouchableOverlayCannotStealRootCoordinateActions() {
        val overlay = window(0, "overlay", "dev.unselected.overlay", "[120,120][400,400]") +
            "  mAttrs={ty=APPLICATION_OVERLAY fl=NOT_FOCUSABLE}\n"
        val dump = focused(overlay, window(1, "target", "dev.example.fixture/.MainActivity", "[0,0][1080,1000]"))
        assertFalse(uncovered(dump))
        assertTrue(uncovered(dump.replaceFirst("isOnScreen=true\n  isVisible=true", "isOnScreen=false\n  isVisible=false")))
    }
    @Test fun unknownWindowFormatVisibilityOrOrderingFailsClosed() {
        val target = window(1, "target", "dev.example.fixture/.MainActivity", "[0,0][1080,1000]")
        assertFalse(uncovered(focused("Window #0 Window{unknown u0 Unknown}:\n", target)))
        assertFalse(uncovered(focused(window(0, "overlay", "Unknown", "[0,0][400,400]").replace("mFrame=", "unknownFrame="), target)))
        assertFalse(uncovered(focused(target, window(0, "overlay", "Unknown", "[0,0][400,400]"))))
        assertFalse(uncovered(focused(target).replace("mCurrentFocus=Window{target", "mCurrentFocus=Window{other")))
        assertFalse(uncovered(focused(target).replace("isVisible=true", "isVisible=unknown")))
    }
    // Synthetic identities only. Layout follows OEM Android 13's detailed (-a) dump:
    // focus in DISPLAYS before WINDOWS, inline flags, Frames.frame and shallow mAttrs.
    private fun detailedOemDump(): String = """
        WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
          mCurrentFocus=Window{target u0 dev.example.fixture/.MainActivity}

        WINDOW MANAGER WINDOWS (dumpsys window windows)
          Window #0 Window{status u0 StatusBar}:
            mAttrs={(0,0)(fillx94) ty=STATUS_BAR
         providedInternalInsets=Insets{left=0, top=0, right=0, bottom=0}
          fl=NOT_FOCUSABLE
          pfl=USE_BLAST}
            mHasSurface=true isReadyForDisplay()=true canReceiveKeys()=false
            Frames: parent=[0,0][1080,2400] frame=[0,0][1080,94] last=[0,0][1080,94]
            mForceSeamlesslyRotate=false seamlesslyRotate: pending=null    isOnScreen=true
            isVisible=true
          Window #1 Window{target u0 dev.example.fixture/.MainActivity}:
            mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION
          fl=LAYOUT_IN_SCREEN
          pfl=USE_BLAST}
            mHasSurface=true isReadyForDisplay()=true canReceiveKeys()=true
            Frames: parent=[0,0][1080,2400] frame=[0,94][1080,2356] last=[0,94][1080,2356]
            mForceSeamlesslyRotate=false seamlesslyRotate: pending=null    isOnScreen=true
            isVisible=true

          mGlobalConfiguration={mBounds=Rect(0, 0 - 1080, 2400)}
          mHasSurface=false isOnScreen=false isVisible=false frame=[0,0][1,1]
        WINDOW MANAGER TRACE (dumpsys window trace)
        Status: Disabled
    """.trimIndent()

    @Test fun detailedOemDumpSuppliesFocusFramesAndBalancedShallowAttributes() {
        val dump = detailedOemDump()
        assertEquals("target" to "dev.example.fixture", RootScreenTestBackend.focusedWindow(dump))
        assertTrue(uncovered(dump))
        assertFalse(uncovered(dump, 50, 150))
        assertNull(RootScreenTestBackend.focusedWindow(dump.substringAfter("WINDOW MANAGER WINDOWS")))
    }
    @Test fun freshFinalWindowEvidenceRejectsChangedFocusAndNewOverlappingOverlay() {
        val fresh = detailedOemDump()
        assertTrue(uncovered(fresh))
        val changedFocus = fresh.replace("mCurrentFocus=Window{target u0 dev.example.fixture/", "mCurrentFocus=Window{target u0 dev.unrelated.app/")
        assertEquals("dev.unrelated.app", RootScreenTestBackend.focusedWindow(changedFocus)?.second)
        assertFalse(uncovered(changedFocus))
        // A newly covering visible overlay must be rejected using this same final dump.
        val covering = fresh.replace("frame=[0,0][1080,94]", "frame=[0,0][1080,500]")
        assertFalse(uncovered(covering))
    }
    @Test fun lastWindowCannotBorrowGlobalVisibilityOrFrameEvidence() {
        val dump = detailedOemDump()
        // Opposite global flags do not invalidate the last real WindowState.
        assertTrue(uncovered(dump))
        // Neither can global state provide missing mandatory window evidence.
        val lastVisibility = dump.lastIndexOf("    isVisible=true")
        assertTrue(lastVisibility >= 0)
        assertFalse(uncovered(dump.removeRange(lastVisibility, lastVisibility + "    isVisible=true".length)))
        assertFalse(uncovered(dump.replace("frame=[0,94][1080,2356]", "unknownFrame=[0,94][1080,2356]")))
    }
    @Test fun ambiguousFocusAndUnbalancedOemAttributesRemainRejected() {
        val dump = detailedOemDump()
        assertFalse(uncovered(dump + "\n  mCurrentFocus=Window{other u0 dev.example.fixture/.MainActivity}\n"))
        assertFalse(uncovered(dump.replace("pfl=USE_BLAST}", "pfl=USE_BLAST")))
        assertFalse(uncovered(dump.replace("mCurrentFocus=Window{target", "mCurrentFocus=Window{unknown")))
    }

}
