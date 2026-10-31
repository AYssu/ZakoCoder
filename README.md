<p align="center">
  <img src="docs/assets/brand-mark.svg" alt="ZakoCoder" width="80" height="80" />
</p>

<h1 align="center">ZakoCoder</h1>

<p align="center"><strong>面向 Android 的原生 AI 编码工作台</strong></p>

<p align="center">项目管理 · 代码编辑 · Linux 终端 · AI 编码 · 变更审阅</p>

<p align="center">
  <a href="https://github.com/AYssu/ZakoCoder/actions/workflows/ci.yml"><img src="https://github.com/AYssu/ZakoCoder/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI" /></a>
  <img src="https://img.shields.io/badge/status-Beta%20preparation-2563EB" alt="Beta 准备中" />
  <img src="https://img.shields.io/badge/Android-11%E2%80%9317%20%C2%B7%20ARM64-334155" alt="Beta 测试目标 Android 11–17 · ARM64" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-334155" alt="Apache-2.0" /></a>
</p>

<p align="center">
  <a href="#开始使用">开始使用</a> ·
  <a href="#功能概览">功能概览</a> ·
  <a href="#支持范围">支持范围</a> ·
  <a href="#技术架构">技术架构</a> ·
  <a href="#开发者区域">开发与贡献</a>
</p>

---

ZakoCoder 是面向 Android 手机的独立开源编码工具。围绕同一项目整合文件管理、代码编辑、Linux 开发环境和 AI 协作，让开发者在手机上完成修改、运行、审阅与验证。工具调用、权限审批和文件变更均有独立入口，执行过程可查看，源码修改可核对。

项目可以是网页、脚本、命令行程序、库或 Android 工程。工具按需安装，模型由你配置，项目与会话保存在本机。当前正在准备 `0.1.0-beta.1`，面向 ARM64 真机。Android 11–17（含 12L）已完成 AOSP x86_64 Debug 系统基线，ARM64 真机主要验证 Android 13；最终签名 Beta 的设备检查以发布记录为准。实际范围见[兼容性结果](docs/ANDROID_COMPATIBILITY_RESULTS.md)，不代表全部厂商设备或任意工程均已兼容。

## 功能概览

| 工作场景 | 你可以做什么 |
| --- | --- |
| **组织项目** | 创建模板项目、导入目录副本、绑定真实本地目录，或克隆公开／私有 HTTPS 仓库；搜索与切换项目。 |
| **编辑代码** | 使用 Sora 原生编辑器，切换多个文件，查找替换、撤销重做、恢复草稿；Markdown 可切换编辑与预览，Python／JS／TS 可选本机语言服务。 |
| **运行项目** | 按需安装开发工具包，使用最多 4 个真实终端会话；从终端启动服务，在手机浏览器预览网页。 |
| **与 AI 协作** | 配置及切换模型，流式阅读 Markdown 回复与服务返回的思考内容；附加图片、文件、选区或构建日志，查看每轮 Token、耗时与请求详情。 |
| **扩展能力** | 按会话启用内置或自定义 Skill，导入 Markdown / ZIP；配置 stdio、Streamable HTTP 或旧 SSE MCP 服务；按项目开启屏幕操作、SSH 远程开发能力包。 |
| **审阅与恢复** | 检查工具审批和文件增删行数，进入代码差异审阅；独立管理任务检查点、文件恢复与会话分叉。 |
| **验证与版本管理** | 配置构建、测试、运行命令，查看日志与退出码；选择项目 Debug APK，通过系统安装及受限屏幕测试验证界面；查看 Git 状态、获取／拉取／推送、管理分支与冲突，选择暂存并创建提交。 |

图片从附件入口添加，可单独发送或配合文字描述，需使用支持图片输入的模型服务；真实服务的视觉理解效果仍待验证。服务公开返回的思考内容在当前轮实时展开，开始正式回答或工具调用后自动收起；历史可展开和复制；不会推测或补造模型内部思考。工具权限提供普通、高权限和高级逐工具规则：高权限需确认，保留禁止项，MCP、Android 屏幕操作与 SSH 仍询问，不获得 Android root。

AI 输出中的项目路径可以跳转到文件所在位置。Shell、BAT/CMD、HTML/CSS、YAML、SQL、Go、Rust 等常见文件提供基础语法高亮，完整编辑范围见[编辑器说明](docs/EDITOR.md)。

## 开始使用

### 获取安装包

Beta 请从 [Releases](https://github.com/AYssu/ZakoCoder/releases) 选择标记为 **Pre-release** 的版本，下载 ARM64 APK 与 `SHA256SUMS.txt` 两个附件；构建详情和本次固定提交的完整源码入口在发布页，以实际存在的发布为准。首次版本计划为 `0.1.0-beta.1`，安装、升级与反馈方式见 [Beta 测试说明](docs/BETA_TESTING.md)。本次 Beta 为不可调试的 Release + R8 包，默认沿用仓库 CI Debug 签名（可选自有签名）；同签名且版本递增可覆盖相应安装，其他 Debug 来源须先核对证书。未来切换正式密钥需另行说明迁移；签名冲突时先确认备份，避免清除重要数据。

开发测试仍保留 [Build Debug APK](https://github.com/AYssu/ZakoCoder/actions/workflows/build-apk.yml) 手动入口。成功运行的 **Artifacts** 提供 ARM64 Debug APK、自编译运行时、对应源码、摘要与构建信息，保留 14 天；下载需要登录 GitHub。无可用安装包时，可按[源码构建说明](docs/DEVELOPMENT.md#从源码构建-debug-apk)自行构建。

Beta 的 Release 构建、签名、源码与许可证分发要求见[发布流程](docs/RELEASING.md)。Beta 预发布与稳定版分别验收，未执行的检查不会标记为通过。

### 完成第一个项目

1. **添加项目**：创建项目、导入副本、绑定本地目录或克隆 HTTPS 仓库。私有仓库需填写具有对应权限的用户名和访问令牌。导入副本与原目录独立；绑定本地目录直接修改原文件。
2. **准备环境**：在环境页安装 Ubuntu，再按项目需要选择工具包。工具检测不会自动安装依赖。
3. **开始编辑**：点击文件打开编辑器；Markdown 可切换预览，修改后显式保存。
4. **配置助手**：在设置中填写模型地址、模型和密钥，手动测试连接；高级设置可调整生成参数、思考模式、上下文和压缩策略、请求头；服务是否支持这些参数需实际测试。也可以独立使用编辑器和终端。
5. **运行并核对**：通过终端或项目命令构建、测试、启动服务；使用 AI 时，核对审批、变更和实际验证结果。

**预览网页**：在项目终端启动开发服务，再通过终端的“预览网页”打开手机浏览器。已有 Python 工具包时，可以在项目目录运行：

```sh
python3 -m http.server 8000 --bind 127.0.0.1
```

Vite 等工程使用自身的安装和启动命令。按实际输出选择端口；地址出现不代表服务已经就绪。

## 支持范围

| 项目 | 当前支持 |
| --- | --- |
| 设备 | Beta 面向 ARM64、Android 11–17；已有 AOSP x86_64 系统基线和 Android 13 ARM64 真机记录，不保证全部厂商设备。最低 SDK 仍为 Android 8.0，但完整开发环境未验收；实验 x86_64 仅用于基础兼容性验证，不含 Android SDK／NDK 工具包。见[实际结果](docs/ANDROID_COMPATIBILITY_RESULTS.md)。 |
| 开发环境 | Ubuntu 24.04 用户空间，按需安装 Zsh、Git、Node.js、Python、Go、Rust、Android 等工具包。 |
| Android 工程 | Java / Kotlin Debug APK 构建及系统安装入口；可选 NDK 支持 arm64-v8a CMake / JNI。曾验证切换运行时前的 ZakoCoder Compose + JNI 构建；当前仓库的运行时源码构建固定 Linux x86_64 宿主，其他工程兼容性取决于依赖与工具链。 |
| 模型服务 | Chat Completions、Responses、Anthropic Messages、Gemini 原生协议，含流式工具调用、图片格式转换、签名／推理续接与常用服务预设。真实服务、参数与视觉理解需按所选模型验证；详见[模型协议](docs/MODEL_PROTOCOLS.md)，不含本地模型推理。 |
| 编辑器 | UTF-8、单文件最多 1 MiB、最多 8 个缓冲区；基础词法着色，可选实验性 Python／JS／TS LSP 补全与诊断。暂不提供 Java／Kotlin 语言服务或完整重构。 |
| Git | 公开／私有 HTTPS 克隆、按仓库保存令牌、状态、暂存、提交、获取／快进拉取／推送、分支管理及冲突编辑入口；不提供强推、rebase 或 Git SSH 密钥管理。 |

Git 与语言服务均使用同一项目及手机上的 Ubuntu 环境。Git 拉取默认仅快进，分叉需明确合并；冲突处理后保存并标记解决，再提交。领先／落后数量来自最近获取的记录。已有 `ssh://` 远端使用终端配置的身份与严格主机校验，不提供密钥管理界面，详见 [Git 工作流](docs/GIT.md)。

语言服务需按需联网安装，仅为当前可见文件建立连接。Python 与 JavaScript／TypeScript 提供实验性补全和诊断，不能代替项目构建，也不代表其他语言已具备语义支持；依赖解析和设备适用性见[语言服务说明](docs/LSP.md)。

「设置 → 开发能力 → 能力包」与助手更多菜单提供统一入口，**Android 屏幕识别与操作**、**SSH 远程开发**是两个独立能力包，按项目启用且默认关闭。未开启时，不向模型加载对应工具定义与专属说明。屏幕包优先读取节点树，需要检查排版或图像时再按需截图；普通、无障碍和 Root 方式的权限分别授权，截图分享默认关闭，目标仍限用户选择的项目调试 APK。具体边界见[屏幕测试说明](docs/SCREEN_TESTING.md)。

SSH 使用原生客户端，无需安装 Ubuntu；支持远程命令、SFTP 文件操作及可选 tmux 长任务。首次连接需确认主机指纹，认证凭据由设备 Keystore 加密保存，不回填或发给模型。远程项目目录只是工作目录，不限制获批 Shell 命令的访问范围；取消连接不保证远程后台进程退出，tmux 任务可以保留并重新接管。算法兼容性、并发写入等限制见[产品范围](docs/PRODUCT.md#可选能力包)。

完整限制与未完成项见[产品范围](docs/PRODUCT.md)、[运行环境](docs/RUNTIME.md)和[路线图](docs/ROADMAP.md)。

### 项目存放方式

| 方式 | 适用情况 | 修改对象 |
| --- | --- | --- |
| 创建 / 克隆 | 在应用内开始开发 | 应用私有工作区。 |
| 导入副本 | 从文件选择器、文档提供者取得源码 | 导入后的副本，原目录保持独立。 |
| 绑定本地目录 | 持续维护手机共享存储中的同一份源码 | 原目录；编辑器、AI 和项目终端使用同一根目录。解除绑定保留源码。 |

绑定通过图形化文件夹浏览器选择真实目录，无需输入路径；访问共享存储需另行授予系统文件权限。Android 11 及以上仍需要一次“管理所有文件”授权，不等于只授权所选文件夹。云盘和仅提供 `content://` 文档的来源使用导入副本。共享存储不完整支持 Linux 执行权限和符号链接，部分构建工具需使用应用私有工作区；详见[运行环境](docs/RUNTIME.md)。

### 数据与操作边界

- 应用内项目、草稿、会话和日志保存在私有存储；绑定项目的源码仍在原目录。卸载或清除数据会删除私有数据及绑定登记；项目导出是源码副本，不是所有数据的完整备份。
- AI 任务读取的项目内容及附加图片会发送到你配置的模型服务。密钥在本机加密保存，项目规则、Skill 与 MCP 输出不会自动提升工具权限。
- 文件恢复前会检查后续修改；检查点有范围和容量限制，不能回滚安装依赖、网络、数据库等外部副作用。
- PRoot 用于兼容 Linux 工具，不构成独立安全隔离。系统结束应用后，不保证终端服务继续运行。

## 技术架构

应用采用 **Kotlin + Jetpack Compose** 原生方案。页面展示状态并派发操作，控制器处理任务与生命周期，文件、网络和进程操作由各自模块负责。

| 层次 | 主要实现 |
| --- | --- |
| 原生交互 | Jetpack Compose、StateFlow、Sora 编辑器、原生终端视图。 |
| 编码与协作 | 项目文件、编辑缓冲、Git、AI 会话、工具审批、Skill / MCP、检查点及验证记录。 |
| 运行环境 | PRoot + Ubuntu、PTY 会话、开发工具包及项目命令管理。 |
| 传输与存储 | OkHttp / SSE、CommonMark 文本解析、应用私有存储、Android Keystore 配置加密。 |

模块职责、异步状态与生命周期约束见[架构说明](docs/ARCHITECTURE.md)。

## 开发者区域

面向源码构建、架构维护与贡献协作。欢迎提交问题、修复与功能建议。开发工作从短期分支发起 PR，采用 Conventional Commits；PR 需说明实际行为、验证结果和兼容影响。

| 文档 | 内容 |
| --- | --- |
| [贡献指南](CONTRIBUTING.md) | 分支、Commit、PR、审查及合并流程。 |
| [开发与测试](docs/DEVELOPMENT.md) | 工具链、源码构建、仓库检查与真机验证。 |
| [Android 兼容性](docs/ANDROID_COMPATIBILITY.md) | 系统版本矩阵、隔离测试包、进程恢复、覆盖升级及手动 CI。 |
| [界面规范](docs/DESIGN.md) | 原生页面结构、交互与移动布局约定。 |
| [AI 工作流](docs/AI_WORKFLOW.md) | 模型、上下文、权限、扩展、审阅与验证。 |
| [Git 工作流](docs/GIT.md) · [语言服务](docs/LSP.md) | 仓库同步、认证、冲突处理及可选本机补全与诊断。 |
| [发布流程](docs/RELEASING.md) · [更新记录](CHANGELOG.md) | Debug／Beta 打包、发布签名与分发条件。 |
| [安全报告](SECURITY.md) · [行为准则](CODE_OF_CONDUCT.md) | 漏洞反馈与社区协作。 |

<details>
<summary><strong>开发者快速构建</strong></summary>

准备 JDK 17、Android SDK Platform 37、Build Tools 36.0.0、NDK 28.2.13676358 和 Python 3，配置 `ANDROID_HOME` 或本地 `local.properties`，然后运行：

```sh
python3 scripts/check_repository.py
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。首次构建需要联网获取 Maven 依赖；PRoot 与 loader 使用仓库内源码和固定 NDK 离线编译；不依赖本地参考工程。详细步骤见[开发文档](docs/DEVELOPMENT.md)。

</details>

## 开源致谢

感谢以下项目及其贡献者为原生编辑、终端和开发环境提供基础能力。

| 项目 | 在 ZakoCoder 中的用途 |
| --- | --- |
| [Sora Editor](https://github.com/Rosemoe/sora-editor) | 原生代码编辑器、TextMate 与实验性 LSP 接入。 |
| [Termux](https://github.com/termux/termux-app) | 终端模拟与视图基础库。 |
| [PRoot](https://github.com/proot-me/PRoot) · [proot-distro](https://github.com/termux/proot-distro) · [Ubuntu](https://ubuntu.com/) | Linux 用户空间运行基础。 |
| [termux/proot](https://github.com/termux/proot) · [talloc](https://talloc.samba.org/) · [libandroid-shmem](https://github.com/termux/libandroid-shmem) | 仓库内保留源码，自行编译 PRoot 与 loader；见 [独立子项目](native-runtime/README.md)。 |
| [android-arm-build-tools](https://github.com/Commit451/android-arm-build-tools) · [android-ndk-custom](https://github.com/HomuHomu833/android-ndk-custom) | 可选手机 Android / NDK 构建工具链。 |
| [CommonMark Java](https://github.com/commonmark/commonmark-java) · [OkHttp](https://github.com/square/okhttp) | Markdown 解析和网络传输。 |
| [JSch](https://github.com/mwiede/jsch) | 原生 SSH 与 SFTP 传输。 |
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) | 随应用离线提供的等宽字体。 |

各组件的准确版本、来源、许可证、修改与分发方式以[第三方材料](THIRD_PARTY.md)为准；上表不替代原始许可证声明。

## 许可证

ZakoCoder 自有代码使用 [Apache License 2.0](LICENSE)。第三方组件保留各自许可证与版权声明。
