package dev.zakocoder.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {
    @Test fun unknownAptValueFallsBackToDefault() {
        val stored = mapOf("apt" to "FutureRepository")
        val original = stored.toMap()

        assertEquals(AppSettings().aptSource, settingsFromStorage(stored).aptSource)
        assertEquals(original, stored)
    }

    @Test fun validStoredValuesAreRead() {
        val settings = settingsFromStorage(mapOf(
            "apt" to AptSource.Ubuntu.name,
            "font" to 18,
            "shell" to TerminalShell.Zsh.name,
            "path" to "/opt/bin:/usr/local/bin",
            "dns" to "1.1.1.1, 8.8.8.8",
            "proxy" to "socks5://127.0.0.1:1080",
            "editor-lsp-enabled" to true,
        ))

        assertEquals(AptSource.Ubuntu, settings.aptSource)
        assertEquals(18, settings.terminalFontSp)
        assertEquals(TerminalShell.Zsh, settings.terminalShell)
        assertEquals("/opt/bin:/usr/local/bin", settings.additionalPath)
        assertEquals("1.1.1.1, 8.8.8.8", settings.dns)
        assertEquals("socks5://127.0.0.1:1080", settings.proxy)
        assertEquals(true, settings.editorLspEnabled)
    }

    @Test fun outOfRangeFontFallsBackToDefault() {
        assertEquals(
            AppSettings().terminalFontSp,
            settingsFromStorage(mapOf("font" to 29)).terminalFontSp,
        )
        assertEquals(
            AppSettings().terminalFontSp,
            settingsFromStorage(mapOf("font" to 8)).terminalFontSp,
        )
    }
}
