package dev.zakocoder.app.projects

import java.util.UUID

/** Original, text-only scaffolds. Managed guest tooling supplies SDK and AAPT2 paths. */
internal object AndroidProjectTemplates {
    fun files(projectName: String, kotlin: Boolean, native: Boolean = false): Map<String, String> {
        require(!native || kotlin) { "Native template requires Kotlin" }
        // Generated once into the build file: rebuilding a saved project retains its identity.
        val applicationId = "dev.zakocoder.generated.p${UUID.randomUUID().toString().replace("-", "")}"
        val language = if (native) "Kotlin + C++" else if (kotlin) "Kotlin" else "Java"
        return linkedMapOf(
            "settings.gradle.kts" to """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = "${ProjectTemplates.identifier(projectName)}"
                include(":app")
            """.trimIndent(),
            "build.gradle.kts" to buildString {
                append("plugins {\n    id(\"com.android.application\") version \"8.13.2\" apply false\n")
                if (kotlin) append("    id(\"org.jetbrains.kotlin.android\") version \"2.2.21\" apply false\n")
                append("}\n")
            },
            "gradle.properties" to """
                org.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8
                org.gradle.workers.max=2
                org.gradle.parallel=false
                kotlin.compiler.execution.strategy=in-process
            """.trimIndent(),
            "app/build.gradle.kts" to appBuild(kotlin, applicationId, native),
            "app/src/main/AndroidManifest.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:label="@string/app_name" android:theme="@android:style/Theme.Material.Light.NoActionBar">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />
                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
            """.trimIndent(),
            "app/src/main/res/values/strings.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name" formatted="false">"${resourceText(projectName)}"</string>
                    <string name="welcome">Hello Android!</string>
                </resources>
            """.trimIndent(),
            "app/src/main/${if (kotlin) "kotlin" else "java"}/dev/zakocoder/generated/MainActivity.${if (kotlin) "kt" else "java"}" to
                if (native) NATIVE_ACTIVITY else if (kotlin) KOTLIN_ACTIVITY else JAVA_ACTIVITY,
            ".gitignore" to ".gradle/\n.kotlin/\n.cxx/\n**/.cxx/\n**/build/\nlocal.properties\n*.jks\n*.keystore\n",
            "README.md" to """
                # $projectName

                原生 Android $language 项目，由 ZakoCoder 原创模板生成。
                使用系统 Activity 和 TextView，不依赖 Compose 或 AppCompat。${if (native) "通过 JNI 调用 C++ 原生库。" else "不使用 NDK。"}

                ## 环境

                在 ZakoCoder 的环境页面安装 Ubuntu 和 Android 构建工具包，并同意 Android SDK 许可。
                需要 JDK 17、Gradle 8.13、Android SDK Platform 36 和 Build Tools 36.0.0。
                手机 ARM64 的 AAPT2 由工具包配置；普通桌面环境需自行配置 Android SDK。
                本项目不包含 Gradle Wrapper 二进制文件，使用环境提供的 gradle。
                ${if (native) NATIVE_ENVIRONMENT.replace("\n", "\n                ") else ""}
                插件版本固定为 AGP 8.13.2${if (kotlin) "、Kotlin 2.2.21" else ""}；首次构建需联网下载依赖。

                ## 构建

                在项目终端运行：

                ```sh
                gradle --no-daemon :app:assembleDebug
                ```

                测试安装包位于 app/build/outputs/apk/debug/app-debug.apk。
                在项目文件列表点击 APK，交给系统安装器，并按提示允许安装来源。
                每次创建自动生成独立 applicationId 并保存到 app/build.gradle.kts，多个项目可同时安装。
                后续构建保持此包名，若手动修改 applicationId，系统会将其视为另一款应用。
                Debug 签名由当前开发环境生成，不用于正式发布。
            """.trimIndent(),
        ).apply {
            if (native) {
                put("app/src/main/cpp/CMakeLists.txt", CMAKE_LISTS)
                put("app/src/main/cpp/native-lib.cpp", NATIVE_SOURCE)
            }
        }
    }

    private fun appBuild(kotlin: Boolean, applicationId: String, native: Boolean): String = buildString {
        append("plugins {\n    id(\"com.android.application\")\n")
        if (kotlin) append("    id(\"org.jetbrains.kotlin.android\")\n")
        append("}\n\n")
        append("""
            android {
                namespace = "dev.zakocoder.generated"
                compileSdk = 36
                buildToolsVersion = "36.0.0"
                ${if (native) "ndkVersion = \"28.2.13676358\"" else ""}

                defaultConfig {
                    applicationId = "$applicationId"
                    minSdk = 26
                    targetSdk = 35
                    versionCode = 1
                    versionName = "1.0"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        """.trimIndent())
        if (native) append("\n\n" + NATIVE_ANDROID_BUILD)
        if (kotlin) append("\n\nkotlin {\n    jvmToolchain(17)\n}\n")
    }

    /** Android resource quoting also protects leading @/? and preserves surrounding whitespace. */
    private fun resourceText(value: String): String = buildString {
        value.forEach { char ->
            append(when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\'' -> "\\'"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> if (char.isISOControl()) " " else char.toString()
            })
        }
    }

    private val NATIVE_ANDROID_BUILD = """
        android {
            defaultConfig {
                ndk {
                    abiFilters += "arm64-v8a"
                }
                externalNativeBuild {
                    cmake {
                        arguments += "-DANDROID_STL=c++_static"
                    }
                }
            }
            externalNativeBuild {
                cmake {
                    path = file("src/main/cpp/CMakeLists.txt")
                    version = "3.28.3"
                }
            }
        }
    """.trimIndent()

    private val NATIVE_ENVIRONMENT = """
        另需 NDK r28c（28.2.13676358）、CMake 3.28.3 和 Ninja；模板仅构建 arm64-v8a。
        手机环境安装 NDK 工具包后使用其 ARM64 编译器，不能直接运行官方 x86_64 Linux NDK。
        手机工具包使用 Ubuntu 系统 CMake 3.28.3，Gradle 从 PATH 查找，安装后请重新创建终端。
        桌面环境需配置匹配的 NDK 与 CMake 3.28.3，或自行修改 app/build.gradle.kts 中的 CMake 版本。
        C++ 使用静态 libc++，JNI 入口位于 app/src/main/cpp/native-lib.cpp。
    """.trimIndent()

    private val CMAKE_LISTS = """
        cmake_minimum_required(VERSION 3.22)
        project(zako_native LANGUAGES CXX)
        add_library(zako_native SHARED native-lib.cpp)
        target_compile_features(zako_native PRIVATE cxx_std_17)
        target_link_options(zako_native PRIVATE "-Wl,-z,max-page-size=16384")
    """.trimIndent()

    private val NATIVE_SOURCE = """
        #include <jni.h>

        extern "C" JNIEXPORT jstring JNICALL
        Java_dev_zakocoder_generated_MainActivity_greeting(JNIEnv* env, jobject) {
            return env->NewStringUTF("Hello from C++!");
        }
    """.trimIndent()

    private val NATIVE_ACTIVITY = """
        package dev.zakocoder.generated

        import android.app.Activity
        import android.os.Bundle
        import android.view.Gravity
        import android.widget.TextView

        class MainActivity : Activity() {
            private external fun greeting(): String

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                val text = TextView(this).apply {
                    text = greeting()
                    textSize = 24f
                    gravity = Gravity.CENTER
                }
                setContentView(text)
            }

            companion object {
                init {
                    System.loadLibrary("zako_native")
                }
            }
        }
    """.trimIndent()

    private val JAVA_ACTIVITY = """
        package dev.zakocoder.generated;

        import android.app.Activity;
        import android.os.Bundle;
        import android.view.Gravity;
        import android.widget.TextView;

        public final class MainActivity extends Activity {
            @Override
            public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                TextView text = new TextView(this);
                text.setText(R.string.welcome);
                text.setTextSize(24);
                text.setGravity(Gravity.CENTER);
                setContentView(text);
            }
        }
    """.trimIndent()

    private val KOTLIN_ACTIVITY = """
        package dev.zakocoder.generated

        import android.app.Activity
        import android.os.Bundle
        import android.view.Gravity
        import android.widget.TextView

        class MainActivity : Activity() {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                val text = TextView(this).apply {
                    setText(R.string.welcome)
                    textSize = 24f
                    gravity = Gravity.CENTER
                }
                setContentView(text)
            }
        }
    """.trimIndent()
}
