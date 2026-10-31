# 手机上的 Flutter 开发

Flutter 是按需安装的开发工具包，运行在 ZakoCoder 的 ARM64 Ubuntu 用户空间中。应用提供项目模板、Dart 基础着色和终端；不包含 Flutter 可视化设计器或 Dart 语言服务。项目源码、依赖、构建和预览使用同一目录。

## 安装环境

1. 在环境页安装 Ubuntu，预留至少 **8 GiB** 可用空间；工程依赖和构建缓存还会额外占用空间。
2. 在工具包中选择 Flutter，检查安装说明后确认。当前固定 **Flutter 3.47.9 / Dart 3.13.7**，不是自动升级到上游最新版。
3. 安装完成后新建项目终端，运行 `flutter --version` 和 `dart --version` 核对；旧终端需要重新创建以取得环境变量。
4. 若需构建 Android APK，另外安装 Android 和 Android NDK 工具包并确认 Android SDK 许可；当前模板的 Android 构建配置可能使用 NDK。Web 开发不要求为了 Flutter 工具包自动安装 Android SDK。

安装从官方 Git 仓库取固定 tag 并核对提交、引擎版本，使用官方 Dart 与引擎制品。APT 依赖和后续项目依赖需联网获取，不能根据下载开始就判断安装完成；失败后检查真实终端日志和网络。来源和许可见 [第三方说明](../THIRD_PARTY.md)。

应使用应用私有工作区。共享存储缺少完整执行权限和符号链接支持，不适合作为 Flutter SDK 或完整构建工作区；导入副本与原文件的区别见 [运行环境](RUNTIME.md)。

## 从模板开始

在项目库创建 Flutter 项目，然后在该项目的终端执行。以下使用 `my_flutter_app`，应替换成生成的 `pubspec.yaml` 中 `name` 的实际值：

```sh
flutter create --platforms=android,web --project-name my_flutter_app .
flutter pub get
flutter analyze
flutter test
```

模板包含 `pubspec.yaml`、自有 `analysis_options.yaml`、`lib/main.dart`、`test/widget_test.dart`、`.gitignore` 与 README。真机已核验平台初始化前后 `lib/main.dart` 和 `test/widget_test.dart` 的 SHA-256 保持一致；Flutter 会迁移 `analysis_options.yaml`，在保留自有规则的同时补充 `build/**`、`android/**`、`web/**` 排除项，因此不承诺全部文件字节不变。平台初始化补充 Android/Web 文件；不添加 `--overwrite`，以免覆盖已有代码。对已有项目先备份并审阅差异。项目名含中文、空格、数字或保留字时，模板会生成合法 Dart 包名；初始化时使用包名，不直接将显示名称传入 `--project-name`。

模板的计数器应用与 Widget 测试为本项目自有代码，Android/Web 平台壳由已安装的 Flutter SDK 生成。项目任务仍通过现有“构建／测试／运行”命令页配置，不会因为打开项目就运行脚本。

## Web 构建和预览

```sh
flutter build web --no-web-resources-cdn
flutter run -d web-server --web-hostname 127.0.0.1 --web-port 8080
```

等待终端明确输出服务就绪后，通过终端的网页预览入口打开 8080 端口，或在手机浏览器打开 `http://127.0.0.1:8080`。服务占用当前终端，停止时按 Ctrl+C；应用被系统结束后不保证继续运行。Web server 不等于 Chrome 调试连接，不承诺完整 DevTools、热重载或自动控制手机浏览器。

## Android APK

确认 Android 和 Android NDK 工具包就绪，再执行：

```sh
flutter build apk --debug --target-platform android-arm64
flutter build apk --release --target-platform android-arm64
```

预期产物分别为 `build/app/outputs/flutter-apk/app-debug.apk` 与 `app-release.apk`。在项目文件列表点击 APK，按系统提示确认安装；这不是静默安装，也不自动授予未知来源权限。Flutter 默认模板的 Release 签名不能替代正式发布密钥，发布前按自己的工程配置签名、应用标识与版本。

官方 Flutter 的部分 Android AOT 工具仍为 Linux x86_64。当前仅为 Android arm64 profile／release 的 `gen_snapshot` 配置固定 QEMU 9.2.1 用户态执行包装；包装器独立安装，不升级或替换 Ubuntu 的系统 QEMU。Debug、Web 与 Dart 采用对应 ARM64 工具。QEMU 会增加构建时间，不代表支持任意 ABI 或任意 x86_64 编译器。原生插件可能另需 NDK、CMake、特定 Java／Gradle／SDK 版本；已有工程应保留自己的配置，不能因为基础模板通过就推断全部插件兼容。

## 当前验证记录

以下记录覆盖本次基础模板及工具链链路，不代表任意现有 Flutter 工程或全部插件已兼容。APK 安装通过 ADB 完成；本次未将项目文件列表的系统安装器入口计为已验收。

| 检查 | 当前状态 |
| --- | --- |
| Android ARM64 真机最终托管安装器、官方 Flutter 3.47.9 / Dart 3.13.7 | 完整安装、版本探针及幂等复检通过，使用私有 QEMU 9.2.1，不升级系统 QEMU |
| 官方计数器工程依赖、`flutter analyze`、Widget 测试 | 已通过 |
| 官方计数器 `flutter build web` | 已通过 |
| 产品模板依赖、分析、Widget 测试、`flutter build web --no-web-resources-cdn` | 已通过；最终托管 SDK 下分析约 12.3 秒，Widget 测试复验通过 |
| 产品模板平台初始化后 lib/test 哈希、分析规则保留 | 已通过；平台排除项由 Flutter 自动补充 |
| 固定 QEMU 9.2.1 执行 Android arm64 AOT | 真实 `app.so` 及官方计数器、产品模板的完整 Release APK 均已通过 |
| 官方计数器 Android Debug APK 完整构建、安装及计数交互 | 已通过；首次构建约 843.5 秒，真机点击由 0 增至 1 |
| 官方计数器 Android Release APK 完整构建、安装及计数交互 | 已通过；构建约 210.4 秒，真机点击由 0 增至 1 |
| 产品模板最终托管 SDK 下 Debug／Release APK 完整构建与运行 | 已通过；构建分别约 89.0／169.0 秒，两个产物均安装并点击 Count: 0 → 1 |
| 产品模板 Web 服务与手机浏览器 | 本机 38751 端口 HTTP 200；浏览器显示页面，点击 Count: 0 → 1 通过 |
| ZakoCoder 应用内模板创建、编辑保存完整流程 | 真机实际创建 Flutter 项目、打开 lib/main.dart、通过编辑器输入并保存，磁盘断言通过 |

仓库检查、应用 Debug 构建及 Lint 通过；JVM 429 项（含终端 145 项）、脚本 85 项、真机定向 3 项分批通过。真机 UI 测试使用独立测试应用，临时系统测试授权已恢复，未修改用户主应用。

界面截图与对应范围见 [本次验收记录](reviews/2026-11-01-flutter/README.md)。

不覆盖 iOS、Windows／macOS／Linux 桌面构建、模拟器 Flutter 工具链、无线 ADB 或 `flutter run` 自动部署到本机 Android。编辑器中的 Dart 仅提供基础词法着色，语义检查使用 `flutter analyze`；当前没有 Dart Analysis Server 补全、Widget Inspector 或完整重构。
