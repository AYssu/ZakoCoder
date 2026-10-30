# Android 屏幕测试验收记录

日期：2026-10-30。基线：`56d8608`，功能分支：`feat/android-screen-testing`。本记录随功能代码归档；不是对未覆盖系统或商业模型决策能力的保证。

## 设备与隔离

Android 13 / ARM64 真机。测试使用独立宿主，不覆盖日常使用的应用、不导入用户模型凭据。Root 权限由设备所有者手动授予；测试不修改设备的授权配置。目标是仓库 `test-fixtures/screen-app/` 的真实独立 Debug APK，界面提供计数、文本输入及独立密码页。

UI 使用真实 Compose 界面的隔离渲染状态；屏幕操作和 AI 工具闭环另外使用实际安装的目标应用。两种证据不可互相替代。

## 已执行结果

| 检查 | 结果与边界 |
| --- | --- |
| 仓库检查、XML、文档链接、忽略规则、`git diff --check` | 通过 |
| `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug` | 通过；Root 正则修复后重新执行 |
| 应用单元测试 | 217 项通过；最后仅重新执行受正则修复影响的 Root 安全测试 9 项，全部通过 |
| 终端单元测试 | 145 项通过；后续未修改终端模块，复用已通过结果 |
| 控制器设备测试 | 7 项通过：目标与制品限制、停止、状态竞争、审计失败不能覆盖取消等 |
| AI 协议与权限设备测试 | 12 项通过：强制审批、任务租约、禁止项、截图临时请求、持久历史清理及取消 |
| 无障碍设备测试 | 4 项通过：真实节点与中文输入、点击和断言、密码页截图拒绝、停止与同版本旧制品拒绝、系统安装请求交付 |
| Compose UI 设备测试 | 2 项通过，26 张日夜截图；360dp 窄屏与 130% 字号，含授权回调、报告、Root 说明和底部入口 |
| AI → 真实应用端到端设备测试 | 1 项通过：本地 HTTPS 模型协议驱动真实启动、观察、实际节点点击、重新观察、文本断言、真实截图像素回传、完成后撤销授权 |
| Root 设备测试 | 2 项通过：真实 Android ICU 窗口解析与覆盖拒绝；用户授予 Root 权限后真实观察、点击计数变化、错误断言拒绝、旧快照拒绝和其他前台应用拒绝 |
| 独立只读审查 | 未发现新增 P0/P1 阻塞项；不替代设备测试 |

设备用例共 28 项，按模块分批运行。小修改后仅重跑受影响用例，未把重复运行计为新增覆盖。Root 点击验收发现并修复了 JVM 与 Android ICU 对未转义闭合符号的兼容差异；保留原有窗口与遮挡拒绝条件。

端到端模型使用本地确定性 HTTPS 协议 fixture；每一步根据前一步真实工具结果决定后续参数，截图验证实际传输的图片可解码。它证明接线与权限生命周期，不证明真实商业模型能够自主规划任意工程。启动测试先等待目标布局稳定，不放宽产品对过期快照的检查。

## 界面截图

26 张界面截图均为真机原生 Compose 渲染，按日夜成对归档。图片仅裁掉系统状态栏与导航栏，不改应用内容；原图摘要和处理信息见 [截图清单](assets/android-screen-testing/screenshots.json)。截图来源是测试隔离状态，不包含用户项目、真实密钥或会话。

| 场景 | 日间 | 夜间 |
| --- | --- | --- |
| 普通模式与安装入口 | [查看](assets/android-screen-testing/light-normal-360-font130.webp) | [查看](assets/android-screen-testing/dark-normal-360-font130.webp) |
| 无障碍目标 | [查看](assets/android-screen-testing/light-accessibility-target.webp) | [查看](assets/android-screen-testing/dark-accessibility-target.webp) |
| Root 授权说明 | [查看](assets/android-screen-testing/light-root-permission-guidance.webp) | [查看](assets/android-screen-testing/dark-root-permission-guidance.webp) |
| 截图分享确认 | [查看](assets/android-screen-testing/light-screenshot-consent.webp) | [查看](assets/android-screen-testing/dark-screenshot-consent.webp) |
| 运行与停止 | [查看](assets/android-screen-testing/light-root-share-stop.webp) | [查看](assets/android-screen-testing/dark-root-share-stop.webp) |
| 测试报告 | [查看](assets/android-screen-testing/light-report-content.webp) | [查看](assets/android-screen-testing/dark-report-content.webp) |

真实 AI 工具闭环另附 [目标应用计数变化截图](assets/android-screen-testing/ai-workflow-target.webp) 与 [原始 JSONL 审计记录](assets/android-screen-testing/ai-workflow-report.jsonl)。其中只有 `assert_text` 条目声明断言通过，点击和启动只记录已请求，安装请求不冒充安装成功。

## 尚未覆盖与限制

- 未在本次隔离测试宿主中安装完整 Ubuntu Android 工具链，因此不宣称这条屏幕用例重新执行过手机端源码构建。既有构建/测试命令与 `AndroidBuildDeviceTest` 保持独立；测试 APK 在开发机构建后安装到手机。
- 安装和 Android 系统授权仍由用户确认，不能自动批准。当前只接受独立 Debug APK，不支持 split APK、发布包或其他应用。
- 本次设备覆盖 Android 13；Android 8–10 节点路径与 Android 14+ 目标窗口截图仍需对应设备实测。
- Android 11–13 的截图在前后检查覆盖窗口后裁剪，无法严格排除两次检查之间瞬时出现的覆盖窗口。
- Root 输入仅支持聚焦输入框中的可打印 ASCII；中文使用无障碍。Root 截图同样依赖受限无障碍捕获，不使用全屏 Root 截屏。
- OEM 后台冻结或跨应用启动限制可能仍要求用户允许后台运行/系统首次启动确认；不通过静默修改系统全局设置绕过。

功能与安全契约见 [Android 应用屏幕测试](SCREEN_TESTING.md)。
