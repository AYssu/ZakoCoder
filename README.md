# ZakoCoder

Android 原生 AI 编码工作台。以项目为单位组织代码、终端、模型对话、文件变更和验证记录，在手机上完成编辑、运行与审阅。

使用 Kotlin、Jetpack Compose 和本地 Ubuntu 用户空间，当前版本为 `0.1.0-dev`。面向 ARM64 真机开发；x86_64 和模拟器尚未适配。

## 功能

| 模块 | 能力 |
| --- | --- |
| 项目库 | 创建空项目、导入目录副本、克隆公开 HTTPS 仓库、搜索与切换项目 |
| 代码编辑 | Sora 原生编辑器、语法着色、多文件页签、查找替换、显式保存与本地草稿 |
| 开发环境 | Ubuntu 24.04、按需选择工具包、异步检测工具与项目根依赖清单 |
| 终端 | 真实 PTY、最多四个会话、直接键盘输入、辅助键与浏览器预览入口 |
| AI 编码 | Chat Completions 兼容接口、流式 Markdown、本地会话、项目规则、文件及报错引用、可选 Skill |
| 修改审阅 | 工具执行过程、权限审批、只读 Diff、任务检查点与冲突检查 |
| 验证与诊断 | 项目构建/测试/运行命令、有界日志、退出状态、请求用量和上下文记录 |
| Git | 状态、初始化、选择暂存和本地提交；不自动推送 |

网页预览是可选工作流；脚本、库、命令行及其他项目使用各自的工具和验证命令。设置中可配置软件源、DNS、附加 PATH 与无凭据 HTTP／SOCKS5 代理；SOCKS5 覆盖应用自身下载与 AI 请求（由代理侧解析目标域名）及识别 `ALL_PROXY` 的终端工具（本应用只导出 `socks5://`，域名解析取决于具体工具），apt 等仅识别 HTTP 代理的工具不会走 SOCKS5。

## 开始使用

1. 在项目库选择“添加项目”，导入已有源码、创建空项目或克隆公开仓库。导入生成应用内工作副本，保留原目录。
2. 在环境页显式安装 Ubuntu，按项目需要选择工具包。工具检测不会自动安装；项目依赖安装前需确认命令。
3. 点击文件进入编辑器；在终端页新建项目会话，点击卡片进入终端。返回卡片列表会保留会话，关闭会话会结束其进程。
4. 在设置的模型配置中填写自己的服务地址、模型与密钥，再显式测试连接。应用不附带模型账户、密钥或预置会话。
5. 从 AI 编码页选择项目并发送任务。按权限策略审批文件和命令操作，在变更及验收页核对实际结果。

项目规则和 Skill 是模型的任务参考，不会提升工具权限。模型说“完成”不等于构建或测试通过，以实际执行记录为准。

### 网页预览

在项目终端启动服务，再用终端的“预览网页”打开同一手机的浏览器。例如已有 Python 工具包时：

```sh
python3 -m http.server 8000 --bind 127.0.0.1
```

Vite 等工程需先安装项目依赖，再运行自身开发命令。使用实际输出的端口；地址识别不代表服务已经就绪。默认使用回环地址，不自动开放局域网。应用被系统结束后不保证服务继续运行。

## 从源码构建

需要 JDK 17、Android SDK Platform 36、Build Tools 36.0.0、NDK 28.2.13676358 和 Python 3。Gradle Wrapper 固定为 8.13，库版本集中在 `gradle/libs.versions.toml`。

在 Android Studio 打开根目录，或配置 `ANDROID_HOME`。也可在不提交的 `local.properties` 中设置 `sdk.dir`。首次构建需要联网获取依赖及校验后的运行环境验证制品。

```sh
python3 scripts/check_repository.py
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。最低 Android 版本为 8.0（API 26），当前真机覆盖为 Android 13 ARM64，最低版本尚未验收。

当前 **Release 构建被阻止**：PRoot 验证制品的可复现自编译与完整源码对应关系尚未完成。公开源码不代表已具备正式 APK 发行条件，准确来源、许可证和限制见 [第三方材料](THIRD_PARTY.md) 与 [发布流程](docs/RELEASING.md)。构建不需要本地参考工程、过程记录或设备数据。

## 数据与边界

- 项目副本、草稿、会话与命令日志保存在应用私有目录。卸载或清除数据会删除它们；需要保留的源码应先导出。
- 导出会跳过依赖缓存、生成目录、Git 元数据和符号链接，不是无损同步或完整备份。
- 编辑器支持最多 1 MiB 的 UTF-8 文件、20,000 行及每行 20,000 字符，最多八个缓冲区；保存检查原始文件基线，不自动覆盖外部修改。暂不提供 LSP 或语义补全。
- AI 任务会将允许读取的项目内容发送给用户配置的服务。密钥在本地加密保存；请求诊断、会话和日志仍需按项目敏感程度谨慎管理。
- 检查点及日志有大小与保留数量限制；检查点不能回滚依赖安装、外部网络、数据库或项目外副作用，也不能消除并发进程的竞争。
- PRoot 是运行 Linux 开发工具的兼容方案，不构成独立安全边界。命令执行前应核对项目、目录和权限。
- Android/Flutter 工程仍需兼容的 SDK 与构建链；安装 JDK 不等于已经具备这些构建环境。
- Git 暂不提供私有仓库认证、分支合并、冲突界面或自动推送。更多未完成项见 [路线图](docs/ROADMAP.md)。

## 工程结构

```text
app/src/main/java/dev/zakocoder/app/
  ai/          模型传输、上下文、会话和工具审批
  editor/      编辑缓冲、文件基线、草稿与语言资源
  git/         项目 Git 操作
  projects/    项目注册、导入导出与清单识别
  runtime/     下载校验、Ubuntu 与工具链
  settings/    本地设置
  terminal/    PTY 会话与运行服务
  ui/          Compose 页面
  workflow/    项目命令、日志和验证证据
third_party/   终端基础库及上游声明
runtime/       固定运行环境制品元数据
scripts/       仓库与提交规范检查
```

## 开发文档

- [贡献指南](CONTRIBUTING.md)：分支、commit、PR 与审查流程
- [产品范围](docs/PRODUCT.md)、[架构](docs/ARCHITECTURE.md)、[运行环境](docs/RUNTIME.md)
- [界面设计规范](docs/DESIGN.md)、[开发与测试](docs/DEVELOPMENT.md)
- [AI 任务工作流](docs/AI_WORKFLOW.md)、[编辑器与文件安全](docs/EDITOR.md)
- [路线图](docs/ROADMAP.md)、[发布流程](docs/RELEASING.md)
- [安全报告](SECURITY.md)、[行为准则](CODE_OF_CONDUCT.md)、[第三方材料](THIRD_PARTY.md)

## 许可证

自有代码采用 [Apache License 2.0](LICENSE)。第三方组件保留各自版权、许可证和来源声明，不因本项目许可证而改变。
