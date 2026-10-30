# 开发与测试规范

贡献流程、分支、提交与 PR 要求见[贡献指南](../CONTRIBUTING.md)。当前工程采用 Kotlin + Compose；产品范围见[产品说明](PRODUCT.md)，运行与发行限制见[运行环境](RUNTIME.md)。

## 工具链与依赖

使用 JDK 17、Android SDK Platform 36、Build Tools 36.0.0、NDK 28.2.13676358、Python 3.12+，Gradle Wrapper 固定 8.13；依赖版本集中在 `gradle/libs.versions.toml`。首次构建需要下载 Maven 依赖；运行时使用仓库内源码离线编译，需 Linux x86_64、GNU Make、patch 和 readelf。

构建不得依赖本地参考材料、开发者绝对路径、私有 Maven 仓库或未提交脚本。禁止动态版本与 SNAPSHOT；新增第三方代码、工具和 CI Action 记录准确来源、版本、许可证及必要声明。依赖升级单独审查，网络失败不能通过跳过校验或未经审查的镜像掩盖。

## 从源码构建 Debug APK

在 Android Studio 打开仓库根目录，或配置 `ANDROID_HOME`；也可在不提交的 `local.properties` 中填写 `sdk.dir`。准备好以上工具链后运行：

```sh
./gradlew :app:assembleDebug
```

产物为 `app/build/outputs/apk/debug/app-debug.apk`，目前面向 ARM64 真机。Ubuntu 基础系统不内置 APK，用户在环境页另行安装；PRoot／loader 由独立 `native-runtime/` 子项目从仓库内固定源码编译并校验，不下载第三方预编译文件，不依赖本地参考工程。构建需要 Linux x86_64、Python 3.12+、GNU Make、patch、binutils 与固定 NDK，详见[子项目说明](../native-runtime/README.md)。正式 Release 仍受[发布流程](RELEASING.md)中的来源材料门禁约束。

首次安装可通过 Android 系统安装器或明确指定设备的 `adb install`。不同调试签名可能无法覆盖安装；先核对[测试包签名说明](RELEASING.md#手动打包测试-apk)，不要为绕过签名冲突直接卸载并丢弃用户数据。

## 实现约束

- 类与文件使用 PascalCase，函数／属性 camelCase，包名小写；注释解释契约和边界，不逐行复述代码。
- UI 只展示状态、接收输入与派发事件，长期操作由 Workbench／控制器及前台服务持有；不得将 Activity 强引用交给长期对象。
- 磁盘、文档提供者、网络及阻塞操作放在 IO 调度器。`CancellationException` 重新抛出，不能用空列表或成功结果掩盖异常。
- 异步结果绑定项目、会话、文档或请求代际；切换身份后不沿用旧列表、授权、错误或验证状态。
- SAF URI 与文件路径保持不同语义。路径规范化、敏感文件、链接、大小及冲突检查不能为通过测试而关闭。
- 模型文本不得作为宿主 Shell 命令执行。guest 环境、argv、cwd、超时与进程归属必须明确；安装与外部副作用需按原权限确认。
- 不覆盖未保存缓冲或用户后续修改，不默认清理工作区，不引入无需求支持的插件框架、后端或空抽象。
- 文案使用字符串资源，日志与错误不得泄漏密钥、授权头、完整环境或无必要的个人源码。请求诊断正文需明确范围并脱敏。

变更包名、存储位置、schema、配置或工具协议前说明兼容影响，验证旧数据、失败和重启；未发布也不能静默删除已有用户文件。新的设计决策应写入公开 PR 或可维护的规范，而非仅存在本地记录。

## 检查策略

同一模块的小改动合批后执行定向编译、测试及必要读图；公共契约、依赖和跨模块变化再扩大覆盖。已通过检查只在相关内容变化、失败或新增风险时重跑。文档修改做仓库文档检查，不重装应用；仅测试修改增量构建测试制品。

完整收尾基线：

```sh
python3 scripts/check_repository.py
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest
```

仓库脚本检查公共文件、XML、Markdown 链接及禁止路径，不是安全扫描。Lint 使用 warningsAsErrors；不能增加宽泛基线掩盖问题。固定依赖／target 更新提示为 informational，API 兼容、安全及正确性问题仍阻塞。没有测试或 `NO-SOURCE` 不等于功能通过。

审查应一次提供有证据、按优先级排列的问题清单，再分批修复。并行工作预先明确写入范围；共享设备由单一执行者合批使用，避免同时安装、切换主题或修改系统设置。

## 测试分层与隔离

纯状态、参数、路径和差异算法使用单元测试；SAF、PTY、Android 文件权限、Keystore、输入法和生命周期使用仪器测试。回归应触发原失败机制，断言用户可见结果及副作用，避免只重复实现常量。

默认模型测试使用独立 TLS／SSE fixture，不能把固定回复称为真实模型能力。网络下载、软件包安装、系统浏览器和商业服务测试必须显式选择；可能计费的模型测试不得进入常规 CI 或自动重试。

默认隔离测试只使用自有 UUID 项目、独立目录、偏好、权限与历史，不读取用户模型配置或源码。显式选择的真实模型测试可使用设备上已配置的服务，必须先确认测试范围和可能产生的费用。若需要已有环境，先检查条件并明确跳过原因，不能后台安装。finally 仅清理自有文件、会话和进程，恢复修改过的选择与设置；禁止清空应用／浏览器数据或停止无关服务。

## 仪器测试

先构建测试 APK：

```sh
./gradlew :app:assembleDebugAndroidTest
```

安装与运行必须显式指定设备，避免误操作其他手机或模拟器：

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -e class 'dev.zakocoder.app.DirectoryRetryDeviceTest' dev.zakocoder.app.test/androidx.test.runner.AndroidJUnitRunner
```

`SERIAL` 替换为本次明确选择的设备，不提交个人设备标识。部分用例要求 Ubuntu／工具已经安装；先读对应测试前置条件。编译成功、shell 退出 0 和 Instrumentation 实际通过是不同结论。

重点覆盖项目身份、目录失败重试、模型与权限、取消／超时、持久化错误、检查点恢复冲突、日志截断、终端归属及源码未保存状态。界面检查覆盖亮暗、放大字体、键盘、长内容和窄屏；组件截图不能替代整应用导航，Activity 重建不能替代进程死亡。

## PR 验证说明

报告修改后的实际行为、运行的命令与结果、适用 Android／架构及未覆盖项。不把测试编译、fixture、单台设备或有限模型兼容性扩展为全部平台通过。脱敏截图与摘要作为 PR 证据，不将临时日志、凭据、设备标识或本地路径写进公开规范。

源码、测试、[产品说明](PRODUCT.md)和[路线图](ROADMAP.md)应保持一致；功能目标不能提前标记完成。发布检查见[发布流程](RELEASING.md)。
