# Android 系统兼容性验证

本轮范围是 Android 11–17 的系统行为基线，不要求每个模拟器安装 Android SDK、NDK 或完整开发工具链。实际覆盖版本、镜像、ABI、构建提交及结果应由运行报告记录；有测试脚本或编译成功不代表设备验证完成。

本地基线结果见 [2026-10-31 验证记录](ANDROID_COMPATIBILITY_RESULTS.md)。

## 分层范围

| 层次 | 验证内容 | 前置条件 |
| --- | --- | --- |
| 应用基础 | 启动、项目和编辑器、真实 IME、前后台生命周期、模型本地 TLS、文件导入、亮暗及放大字体 | 可安装本次 APK 和测试 APK；无需 Ubuntu、真实模型配置或商业 API |
| 系统目录授权 | 系统 DocumentsUI 选择目录、持久 URI 权限、导入副本与原文档分离、撤销授权 | 标准系统 DocumentsUI；仅使用测试 APK 的只读文档提供者 |
| 持久恢复 | 真正结束应用进程后恢复项目、草稿、会话和检查点 | 同一 UUID 的三阶段用例，由外部运行器终止进程 |
| 覆盖升级 | 同包名和签名、较高 versionCode 安装后保留上述数据 | 两个递增版本的本次测试 APK；不代表历史所有版本的数据迁移 |
| Ubuntu 基础 | 对应 ABI 的 PRoot、Ubuntu、Shell、PTY、退出码及子进程清理 | 显式安装当前 ABI 固定摘要的 Ubuntu 镜像 |
| 本地 HTTP | 真实终端启动服务、应用停止与恢复后访问、关闭终端后端口关闭 | Ubuntu 中显式安装 Python；不访问外网、不调用模型 |

应用层测试与运行环境测试分别报告。x86_64 模拟器通过不能代替 ARM64 真机、厂商后台策略或正式 Release 混淆包验收；AOSP 容器与官方模拟器的差别也需要注明。

## 可重复运行的测试入口

以下类均位于 `dev.zakocoder.app`，`ui.` 前缀表示其子包。选定版本只运行明确的类／方法，不能对全部仪器测试无差别扫描：仓库仍包含付费模型、真实网络、Root、安装工具和已有环境前提的显式验收用例。

| 入口 | 实际覆盖／注意事项 |
| --- | --- |
| `HttpTlsCompatibilityDeviceTest` | 生产传输拒绝不可信证书和主机名不匹配；本机生成证书 |
| `AiModelsDeviceTest` | 独立模型目录、Keystore、参数、发现／探测、取消、跨源及重定向保护；本地 TLS fixture |
| `ProjectFileDeviceTest` | 单文件导入、重名保护、非法名称、取消和临时文件清理 |
| `SafDirectoryCompatibilityDeviceTest` | 真实系统目录选择与授权；不把 URI 猜测成 Linux 路径 |
| `LocalDirectoryBrowserDeviceTest` | 独立目录浏览、链接边界、异步取消、分页 |
| `CapabilityPackagesDeviceTest` | 独立能力包状态保存、租约撤销和原子文件恢复 |
| `EditorDeviceTest` | 真实应用编辑、保存、冲突、IME、停止／恢复及 Activity 重建；重建不是进程死亡 |
| `MobileEntryUiDeviceTest` | 亮暗、字体放大、入口按钮独立触控与展开状态，保持最小 48dp 断言 |
| `ui.EditorPreviewDeviceTest` | Markdown 预览、草稿、撤销及离线语法 |
| `ui.AiProtocolUiDeviceTest` | 协议配置界面及亮暗、窄屏放大字体 |
| `ui.AiComposerUsageUiDeviceTest` | 独立生产会话页面、本地流式 TLS、模型／上下文行与 Token 显示 |
| `NativeRuntimeArtifactDeviceTest#installedNativeFilesMatchSelfBuiltManifest` | 按已安装 APK 的实际 ABI 校验自编译原生文件，不要求 Ubuntu |

`LocalProjectBindingDeviceTest` 需要共享存储文件权限及 Ubuntu；`SettingsDeviceTest` 会使用实际应用设置及已安装环境，均不属于无环境默认组。`AiDeviceTest`、`RuntimeDeviceTest` 等混合前置条件的类应选择具体方法。

### Android 11 旧版 DocumentsUI 的测试 Provider 可见性

本地 API 30 官方镜像所带 DocumentsUI 的 `targetSdkVersion=30`，清单仅查询 SEND、SEND_MULTIPLE、VIEW，没有 `QUERY_ALL_PACKAGES` 或 DOCUMENTS_PROVIDER 查询，因此未枚举新安装的测试 Provider。后续 Android 11 源码已补齐该查询权限。

运行器仅给 `dev.zakocoder.app.runtimecheck.test` 使用 `adb install -r --force-queryable`，使固定测试 Provider 可被系统查询；产品 APK 不增加权限。此标志不授予 MANAGE_DOCUMENTS 或文档读写权限。SAF 用例仍严格验证授权前不可读、真实系统选择后可导入、Provider 主动撤销全部授权后不可读，导入副本保留。只释放持久授权不等同于撤销全部临时授权。

## 真实进程死亡与覆盖升级

`CompatibilityPersistenceDeviceTest` 要求每次带相同的 `compatibilityRunId` UUID，仅操作 `no_backup/compatibility-probes/<UUID>` 与对应命名偏好。运行器按顺序执行：

1. 安装较低 versionCode 的 APK 和测试 APK，运行 `#prepare`。
2. 外部明确终止该应用进程，再运行 `#verifyAfterProcessDeath`。
3. 保持签名和包名，通过覆盖安装较高 versionCode 的 APK，运行 `#verifyAfterUpgradeAndClean`。

三个阶段断言运行在真正应用主进程及相同应用 UID；后两个阶段检查 PID 变化，升级阶段还检查 versionCode 严格增大。验证覆盖项目登记、选中项目、源码、未保存草稿、会话和文件检查点。最后一阶段只在 UUID 所有权标记匹配后清理本次数据。中途失败保留证据，不清除应用数据，不覆盖其他项目。

这是本次代码的系统覆盖安装保留测试，未证明旧 schema 或历史所有公开版本迁移。

## Ubuntu 与 HTTP

`NativeRuntimeProvisioningDeviceTest#installIsolatedBaseEnvironment` 只安装 Ubuntu，要求外部提供固定摘要的当前 ABI 镜像到应用缓存的 `native-runtime-verification/rootfs.tar.xz`。普通应用包运行该方法还必须显式提供 `compatibilityProvisionRuntime=true`、`compatibilityRunId=<UUID>`，且设备硬件标识属于一次性模拟器；既有隔离验证包 `dev.zakocoder.app.runtimecheck` 沿用原入口。

需要 Python／Node 的既有验证可显式选择 `#installIsolatedEnvironmentAndTools`；它会联网安装相应基础包，不安装 Android SDK／NDK。不得把此入口加入普通无环境测试组。

- `RuntimeDeviceTest#guestExecutionCwdUnicodeExitCancellationAndPty`：基础 Shell／PTY、Unicode、工作目录、退出码、取消及子进程归属。
- `RuntimeHttpCompatibilityDeviceTest#loopbackServiceSurvivesStopResumeAndClosesWithOwnedTerminal`：要求预装 Python；应用托管终端运行真实本地 HTTP 服务，以唯一 UUID 内容验证访问，Activity 停止和恢复后重复请求，关闭自有终端后确认端口关闭。该用例不会自行安装依赖。
- `RunningNotificationsDeviceTest`：前台通知及停止／恢复；通知权限状态需要独立记录，不能将未授予通知权限的情况误判为后台进程兼容。

## 验证节奏

完整版本矩阵适合首次建立基线、发布前以及 target SDK、权限、存储、原生库、进程／前台服务或生命周期等系统契约变更后运行。普通文案和局部业务修改按影响范围执行定向验证，不必每次重复所有 Android 版本。

常规 PR CI 保留现有仓库检查、编译和单元测试。兼容性矩阵可作为显式手动或阶段性任务，避免每个 PR 重复下载镜像、运行付费服务或占用真机。每次报告区分通过、失败、跳过和未覆盖，并保留实际测试数、失败原因及所用系统环境。

## 本地构建与运行器命令

使用专用模拟器与隔离包名 `dev.zakocoder.app.runtimecheck`，构建两个递增版本并保留初始版本对应的测试 APK。默认 APK 仍是 ARM64；`compatibilityAbis` 仅在显式提供时改变本次打包目标。

```sh
mkdir -p build/compatibility-apks
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  -PruntimeVerification=true -PcompatibilityAbis=x86_64 -PcompatibilityVersionCode=1
cp app/build/outputs/apk/debug/app-debug.apk build/compatibility-apks/base.apk
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk build/compatibility-apks/test.apk
./gradlew :app:assembleDebug \
  -PruntimeVerification=true -PcompatibilityAbis=x86_64 -PcompatibilityVersionCode=2
cp app/build/outputs/apk/debug/app-debug.apk build/compatibility-apks/upgrade.apk
```

在所选专用模拟器完成开机后执行：

```sh
python3 scripts/android_compatibility.py \
  --serial emulator-5554 \
  --apk build/compatibility-apks/base.apk \
  --test-apk build/compatibility-apks/test.apk \
  --upgrade-apk build/compatibility-apks/upgrade.apk \
  --output build/android-compatibility/api-30
```

每次使用新的输出目录。运行器检查包名及递增版本号，记录设备属性、APK SHA-256、逐项日志与 `report.json`；仪器测试必须报告非零测试数并通过。默认应用组覆盖上述 34 项应用／目录授权／扩展 UI 检查，随后进行三个持久恢复阶段。可用 `--classes` 显式替换应用组；Ubuntu、HTTP 和通知权限链路仍需独立选择与记录，不能从应用组通过推断它们已通过。

脚本不会卸载或清除应用数据，但会安装和覆盖所选包；不要指向承载个人数据的日常应用。失败时保留 UUID 测试证据，复测应使用新的运行记录。更新版与初始版由同一构建环境签名，不能拿签名不同的已有 APK 代替升级输入。

## 手动兼容性 CI

GitHub Actions 中选择 **Android compatibility (manual)**，在 **Run workflow** 中选择代码分支和 `api`：`all` 覆盖 API 30、31、33、34、35、36、37，也可选单个 API。该入口仅由 `workflow_dispatch` 触发，不会在普通 PR 自动启动全矩阵。

工作流先构建一次隔离包的初始、测试和升级 APK，再供各 x86_64 官方模拟器复用；使用 Android SDK 的 emulator／avdmanager，不依赖额外模拟器 Action。Android 17 镜像包为 `system-images;android-37.0;google_apis;x86_64`，其他版本的版本段为 `android-<API>`；API 编号不能直接替代带小版本号的官方包 ID。

每个 API 保存基础检查、真实进程恢复、覆盖升级及模拟器／logcat 证据 14 天；测试 APK 保留 7 天，仅用于兼容性验证，不是正式 Release 分发。手动 CI 默认包含系统目录选择和扩展 UI，不安装 Ubuntu、不调用付费模型；Ubuntu、HTTP 和通知权限独立链路以实际选中并完成的测试为准。工作流实现与本地命令存在不等于远端工作流已执行成功，最终报告须列明实际运行来源。
