package dev.zakocoder.app.editor

import android.content.Context
import android.graphics.Typeface
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource

/** Reads only bundled, app-owned grammars and themes, once on IO. */
class EditorLanguages(private val context: Context) {
    private var prepared = false
    lateinit var typeface: Typeface
        private set
    @Synchronized fun prepare() {
        if (prepared) return
        typeface = Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono-Regular.ttf")
        val themes = ThemeRegistry.getInstance()
        for (name in listOf("light", "dark")) {
            context.assets.open("editor/$name.json").use {
                themes.loadTheme(IThemeSource.fromInputStream(it, "zako-$name.json", Charsets.UTF_8))
            }
        }
        for (name in EditorLanguageResolver.bundled) {
            context.assets.open("editor/$name.tmLanguage.json").use {
                GrammarRegistry.getInstance().loadGrammar(DefaultGrammarDefinition.withGrammarSource(
                    IGrammarSource.fromInputStream(it, "$name.tmLanguage.json", Charsets.UTF_8), name, "source.zako.$name"))
            }
        }
        prepared = true
    }
    fun language(path: String, firstLine: String = ""): String? = EditorLanguageResolver.resolve(path, firstLine)
}
