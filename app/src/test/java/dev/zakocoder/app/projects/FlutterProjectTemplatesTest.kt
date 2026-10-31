package dev.zakocoder.app.projects

import org.junit.Assert.*
import org.junit.Test

class FlutterProjectTemplatesTest {
    @Test fun packageNamesAreValidDartIdentifiersAndAvoidReservedNames() {
        mapOf("My Project" to "my_project", "123 测试" to "app_123", "中文" to "app",
            "class" to "app_class", "flutter_lints" to "app_flutter_lints", "meta" to "app_meta", "collection" to "app_collection",
            "native" to "app_native", "inout" to "app_inout", "flutter" to "app_flutter", "DART" to "app_dart",
            "_hello" to "hello", "a'.\n: test" to "a_test").forEach { (input, expected) ->
            assertEquals(input, expected, FlutterProjectTemplates.packageName(input))
        }
        listOf("123", "class", "flutter_test", "中文", "---", "A".repeat(200)).forEach {
            assertTrue(FlutterProjectTemplates.packageName(it).matches(Regex("[a-z][a-z0-9_]*")))
            assertTrue(FlutterProjectTemplates.packageName(it).length <= ProjectTemplates.MAX_IDENTIFIER_LENGTH + 4)
        }
    }

    @Test fun flutterScaffoldIsBoundedAndNeverInterpolatesRawNamesIntoSourceOrYaml() {
        val hostile = "'\n  dependencies: bad\n${'$'}{evil()} 中文"
        val files = ProjectTemplates.files(ProjectTemplate.Flutter, hostile)
        assertEquals(setOf("pubspec.yaml", "analysis_options.yaml", "lib/main.dart", "test/widget_test.dart", ".gitignore", "README.md"), files.keys)
        assertTrue(files.size <= ProjectTemplates.MAX_FILES)
        files.forEach { (path, content) ->
            assertTrue(ProjectTemplates.isSafeRelativePath(path))
            assertTrue(content.toByteArray().size <= ProjectTemplates.MAX_FILE_BYTES)
            assertFalse(content.contains(hostile))
            assertFalse(content.contains('\u0000'))
        }
        assertFalse(files.getValue("analysis_options.yaml").contains("package:"))
        assertTrue(files.getValue("analysis_options.yaml").contains("avoid_print: true"))
        val name = FlutterProjectTemplates.packageName(hostile)
        assertTrue(files.getValue("pubspec.yaml").startsWith("name: $name\n"))
        assertTrue(files.getValue("test/widget_test.dart").contains("package:$name/main.dart"))
        assertTrue(files.getValue("lib/main.dart").contains("class ZakoApp"))
        assertTrue(files.getValue("test/widget_test.dart").contains("const ZakoApp()"))
        assertTrue(files.getValue("README.md").contains("flutter create --platforms=android,web --project-name $name ."))
        assertTrue(files.getValue("README.md").contains("flutter build apk --debug --target-platform android-arm64"))
        assertFalse(files.keys.any { it.startsWith("android/") || it.startsWith("web/") })
    }

    @Test fun rawProjectNamesDoNotChangeDartApplicationSource() {
        assertEquals(FlutterProjectTemplates.files("one").getValue("lib/main.dart"),
            FlutterProjectTemplates.files("quote'\n${'$'}name").getValue("lib/main.dart"))
    }
}
