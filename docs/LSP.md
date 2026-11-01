# 编辑器语言服务

编辑器使用 Sora 0.23.6 的实验性 LSP 模块，连接手机 Ubuntu 中的本地语言服务器，提供补全和诊断。基础语法高亮仍可单独使用。支持的文件类型、安装入口与实际项目兼容性是不同的边界，不能用安装成功替代工程验收。

## 使用

语言服务默认关闭，包括从旧版本升级后首次使用。在编辑器底部点击「LSP · 已关闭」，主动开启「启用代码智能」后才会检测并连接；选择保存在本机，关闭会释放当前连接和进程。只安装工具包不会自动开启。

在「环境 → 工具包」按需安装相应语言服务。开启后，编辑对应文件时，底部状态入口显示检测、连接、诊断数量或失败；缺少服务时可从详情确认安装。安装需要联网及用户确认，不在打开文件时自动安装。

| 工具包 | 文件 | 依赖与项目条件 |
| --- | --- | --- |
| Python 语言服务 | `.py`、`.pyi` | 独立 venv 中的 Python LSP Server 1.13.1 与 Pyflakes 3.4.0，基础诊断和 Jedi 补全；不自动继承工程 venv 的所有包。 |
| JavaScript / TypeScript 语言服务 | `.js`、`.jsx`、`.ts`、`.tsx` | 独立 npm 前缀中的 TypeScript Language Server 4.4.0 与 TypeScript 5.9.3，需要 Node.js 18 及以上。 |
| Java 语言服务 | `.java` | Eclipse JDT LS 1.46.1 与 Ubuntu OpenJDK 21；支持独立文件及 Maven／Gradle JVM 工程，依赖解析仍需工程所需的工具与网络。 |
| Kotlin 语言服务 | `.kt`、`.kts` | 官方 Kotlin LSP 263.7084.0，仅 Ubuntu ARM64，自带 JBR 25。优先用于 Gradle／Maven JVM 工程；服务为 Alpha，Android Gradle Plugin 支持为实验性，不能承诺独立脚本、KMP 或全部 Android 工程的语义支持。 |
| C / C++ 语言服务 | `.c`、`.h`、`.cc`、`.cpp`、`.cxx`、`.c++`、`.hh`、`.hpp`、`.hxx`、`.h++` | Ubuntu APT 提供的 clangd 与 build-essential；准确诊断需要匹配的头文件、编译参数和 `compile_commands.json`，安装 clangd 不等于已配置 NDK。 |
| Go 语言服务 | `.go` | Ubuntu APT 提供的 gopls 与 golang-go；模块依赖和所需 Go 版本需与工程匹配。 |
| Rust 语言服务 | `.rs` | 固定 rust-analyzer 2026-10-26，Ubuntu APT 提供 rustc、cargo、rust-src；工程所需编译器、目标和依赖仍需另行匹配。 |
| Dart 语言服务 | `.dart` | 复用已安装 Flutter 工具包中的 Dart SDK，运行 `dart language-server --protocol=lsp`，不额外下载 Dart SDK。需先安装 Flutter 工具包，依赖解析使用工程的 `pubspec.yaml` 与实际依赖状态。 |

APT 组件版本随设备配置的 Ubuntu 签名仓库更新，不宣称固定版本或完整锁定依赖树；Kotlin 仅 ARM64，Rust 下载支持 ARM64／amd64，其余服务依赖当前 Ubuntu 环境及仓库的架构支持。这不代表 ZakoCoder 的全部开发工具链已支持其他设备架构。

固定下载的服务安装在 `/opt/zakocoder/lsp/`；clangd、gopls 使用 Ubuntu 系统路径，Dart 使用 `/opt/zakocoder/flutter/`，不会向用户工程写入语言服务依赖。Java 固定选择 JDK 21 启动，安装前已有的 Java 工具选择会恢复并固定为 alternatives 手动模式；原来未安装时可使用 JDK 21 默认值；Kotlin 使用分发包自带的 JBR。Java 的索引缓存按项目目录区分。固定制品下载后校验 SHA-256，再在暂存目录准备并发布安装结果，不覆盖未确认归属的既有目录。

Kotlin 安装仅调整已校验暂存分发包的 VM options：最大堆 1 GiB、代码缓存 256 MiB，并关闭 OOM 堆转储；保留 JBR、上游组件与许可。该设置不是进程总内存上限，复杂工程仍可能耗用较多内存。

通过编辑器现有补全弹窗和诊断标记查看结果。保存通知跟随成功的显式保存，语言服务不会替用户保存文件。

## 生命周期与边界

开启后，只有可见文档持有语言服务连接；切换文件、项目或离开编辑器会释放该连接，重新进入需要再次连接。因此大型工程的首次索引可能较慢，Java／Kotlin 的 JVM 启动和工程导入也会增加等待时间。进程在应用所属 Ubuntu 中运行，二进制标准输入／输出传输 JSON-RPC，标准错误单独收集，取消时清理所属进程。

文档使用项目工作区路径，初始打开和内容变化发送编辑器的当前缓冲区，未保存内容可参与诊断及补全。当前绕过 Sora 0.23.6 的增量范围问题，使用完整文档同步；这里的“按需开启”指用户控制启停，不是仅发送字符差量。接收服务器推送诊断，同时支持声明该能力的服务器拉取诊断；丢弃旧版本结果及超出当前缓冲区范围的位置。服务可以读取同一运行环境可访问的工程文件；Gradle、Maven、Cargo 等工程解析也可能执行项目提供的构建逻辑或获取依赖。PRoot 不提供安全隔离，应仅为可信项目启用开发工具。

暂不提供完整重命名重构、符号搜索或导航界面，也不自动安装语言服务器插件。诊断准确性和第三方依赖解析取决于具体项目环境。失败后从状态详情重试，核对 Ubuntu 和工具包检测结果；服务启动成功不代表工程依赖已解析完成。

来源、版本及许可证见 [第三方材料](../THIRD_PARTY.md)。本次在 Android 13 ARM64 独立测试应用的 Ubuntu 环境中，验证 Java、Kotlin、C、C++、Go、Rust、Dart 的初始化、未保存内容诊断、真实补全、修复后清除诊断和释放连接。Kotlin 使用离线导入的最小 JVM 工作区，未验证普通独立脚本，也未覆盖真实 Gradle／Android 工程导入；其余语言使用无第三方依赖的最小工程。单台设备与最小工程结果不代表全部设备或工程兼容。
