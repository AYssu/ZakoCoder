# Flutter 开发支持验收

本记录对应可选 Flutter 工具包、Flutter 项目模板和 Dart 基础编辑支持。页面保留 Kotlin／Compose 原生实现。截图为 Android ARM64 真机实际画面；官方计数器验证工具链可执行性，不代替产品模板及完整应用工作流的验收。

## 已确认结果

| 检查 | 实际结果 |
| --- | --- |
| 最终托管安装器 | 真机 Ubuntu 完整安装成功，固定 Flutter 3.47.9 / Dart 3.13.7；独立 QEMU 9.2.1 校验与探针通过，没有升级或替换系统 QEMU；最终修订的幂等复检通过，不重复安装 |
| 官方计数器 | 依赖、分析、Widget 测试和 Web 构建通过 |
| 官方计数器 Debug APK | 首次构建约 843.5 秒；ADB 安装、启动，点击计数从 0 增至 1 |
| 官方计数器 Release APK | 构建约 210.4 秒；ADB 安装、启动，点击计数从 0 增至 1 |
| 产品模板平台初始化 | `lib/main.dart`、`test/widget_test.dart` 前后 SHA-256 保持；自有分析规则保留，Flutter 自动补充构建及平台目录排除项 |
| 产品模板分析、Widget 测试 | 最终托管 SDK 下已通过，分析约 12.3 秒 |
| 产品模板 Web 构建 | `flutter build web --no-web-resources-cdn` 已通过 |
| 产品模板 Debug／Release APK | 最终托管 SDK 构建分别约 89.0／169.0 秒；两个实际产物均安装 `com.example.app_123`，点击 Count: 0 → 1，节点断言通过 |
| 应用内项目创建及编辑保存 | `EditorDeviceTest#flutterTemplateCanBeCreatedOpenedEditedAndSaved` 真机 日间 5.727 秒、夜间 5.661 秒通过；实际添加项目、选择 Flutter、创建、浏览 lib/main.dart，通过真实 CodeEditor InputConnection 输入注释并点击保存，磁盘内容断言通过 |
| 产品模板手机浏览器预览 | Release Web 服务本机 38751 端口 HTTP 200；实际浏览器显示页面、点击 Count: 0 → 1，截图核对通过 |

## 界面截图

| 位置 | 日间 | 夜间 |
| --- | --- | --- |
| 工具包入口 | [日间](screenshots/toolkit-light.png) | [夜间](screenshots/toolkit-dark.png) |
| Flutter 安装确认 | [日间](screenshots/install-light.png) | [夜间](screenshots/install-dark.png) |
| Dart 编辑保存 | [日间](screenshots/flutter-editor-saved.png) | [夜间](screenshots/flutter-editor-saved-dark.png) |
| Flutter 模板选择 | [日间](screenshots/template-light.png) | [夜间](screenshots/template-dark.png) |
| 工具包入口，字体 130% | [日间](screenshots/toolkit-light-font130.png) | [夜间](screenshots/toolkit-dark-font130.png) |

工具链产物运行截图：

- [官方计数器 Debug APK 真机交互](screenshots/flutter-debug-device.png)
- [官方计数器 Release APK 真机交互](screenshots/flutter-release-device.png)
- [产品模板 Debug APK 真机交互](screenshots/flutter-product-debug.png)
- [产品模板 Release APK 真机交互](screenshots/flutter-product-release.png)
- [产品模板手机浏览器预览与交互](screenshots/flutter-product-web.png)
- [ZakoCoder Flutter 项目编辑并保存](screenshots/flutter-editor-saved.png)

计数器截图用于证明实际构建产物可安装并完成交互，不把 Flutter 演示应用当作 ZakoCoder 产品界面，也不据此推断任意插件兼容。

## 自动检查与验收边界

- 仓库检查、应用 Debug 构建、Lint 与 JVM 检查通过。
- JVM 共 429 项，其中终端 145 项；脚本检查 85 项通过。
- 最终真机定向 3 项分批通过：编辑器项目创建／保存 1 项，`FlutterDevelopmentDeviceTest` 2 项；设备测试使用独立名称，可重复执行。
- UI 验收使用独立测试应用；测试期间临时调整的系统启动授权已恢复，没有修改用户主应用。

本次官方和产品模板 APK 都通过 ADB 安装；没有将项目文件列表调起系统安装器的操作计为本次已验证入口。日夜截图覆盖本次工具包及模板界面，Dart 编辑保存图和 Flutter 产物图为交互证据。基础链路通过不代表任意原生插件、其他 ABI、iOS、桌面或全型号设备均兼容。

完整步骤、依赖、源码与许可入口见 [Flutter 开发](../../FLUTTER.md) 和 [第三方说明](../../../THIRD_PARTY.md)。

产品模板 APK 摘要（本次验收产物，不作为应用附件提交）：

```text
e56e849f8f98e9ea1c4b8e5b81e0dcc8cda4aa25bf057c8338aad97cbb63760a  app-debug.apk
196ed8f93e6e9d6723d0786a9156f2ed3c99abc14f80e39962e676aa728e1379  app-release.apk
```

网页服务停止后已确认端口关闭，临时代理及 ADB 端口映射均已清理。
