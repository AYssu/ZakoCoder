# 运行环境与终端

当前仅构建 ARM64 调试版，x86_64 模拟器未适配。运行环境采用 Ubuntu 用户空间与 PRoot，不需要 QuickJS；它是开发工具兼容层，不是安全沙箱。

## 安装与发行限制

`runtime/artifacts.json` 固定原生验证制品提交及 Ubuntu 镜像版本、大小、SHA-256 与解包前缀。构建脚本 `scripts/prepare_runtime.py` 校验 PRoot／loader，缺失时下载到忽略目录 `runtime-artifacts/`；构建不依赖本地参考源码。

现有 PRoot／loader 仅用于本地技术验证，Release 打包被阻止，CI 不公开分发验证 APK。正式发行前必须固定可追溯的自编译源码、补丁、工具链、对应许可证和摘要，替换验证制品并重新验证；准确来源和许可缺口以[第三方说明](../THIRD_PARTY.md)为准。

应用显式下载固定 Ubuntu noble ARM64 基础系统，或通过 SAF 导入摘要相同的离线包，不接受任意 rootfs。安装依次完成下载／校验、暂存解包、guest Bash 自检及就绪标记；失败或取消清理暂存，不能显示已安装。进程中断后下次安装清理遗留暂存，不自动续执行安装。至少需要 600 MiB 可用空间；基础镜像约 60 MiB 下载、280 MiB 解包，额外工具另占空间。

解包限制路径、条目数和大小，拒绝越界链接与异常条目；合法 guest 绝对链接转换为内部相对链接。设备节点不从归档恢复，运行时显式绑定。PRoot 从 APK 提取的 nativeLibraryDir 执行，不直接执行可写目录内下载的宿主二进制。

## 工作目录与进程

私有项目路径为 `files/workspaces/<uuid>`，guest cwd 为 `/workspace/<uuid>`。SAF 只负责副本导入导出，不将 URI 转成猜测的宿主路径。导入限制 20,000 条目、512 MiB、40 层，跳过依赖缓存、生成目录、`.git` 和符号链接；导出新建目录，不保证完整备份或原始 POSIX 元数据。

所有命令具备明确环境、项目、cwd 和 argv。普通命令由固定宿主 wrapper 记录自身 PID 后执行 PRoot，脚本作为 guest Bash 参数传入；模型文本不拼接到宿主命令。取消只终止归属可验证的进程树，不按全局进程名清理。

项目共享应用权限、Ubuntu 用户空间及映射目录。获批 Shell 命令可以访问其他挂载项目，结构化文件工具的路径保护不能当作整个运行环境的隔离保证。

## 工具包与项目依赖

| 工具包 | 安装范围 |
| --- | --- |
| 基础／Git | Bash、Git、CA、curl |
| Node.js | nodejs、npm；当前保留系统 npm 并使用固定 npm 10.9.4 处理兼容性 |
| Python | python3、pip、venv |
| Go／Rust | golang-go／rustc、cargo |
| JVM | OpenJDK 17 headless |
| C/C++ | build-essential、cmake、pkg-config |
| 交互 Shell | 可选 Zsh，默认 Bash |
| Android／Flutter | 仅检测，兼容 SDK 和额外工具链由使用者安装 |

工具包独立于项目存在；类型识别只推荐，不隐式勾选或安装。包名来自固定白名单，多选后展示去重安装清单，确认后执行 APT；保留签名验证，不跳过校验。探针真实执行版本命令，分别表示缺失、失败及就绪。运行配置和安装改变后刷新，手动修改环境后可主动重检。发行版版本未必满足项目要求，当前无任意包市场、卸载或通用版本管理。

项目识别为有界扫描，不执行清单。依赖计划检查根清单，支持 npm ci/install、独立 `.venv` 与 pip、go mod download、cargo fetch、Gradle wrapper dependencies；在没有 Ubuntu 时也可识别计划，执行仍要求环境就绪并确认 cwd 和脚本。项目或依赖安装脚本可能有副作用，不能当作纯下载。嵌套工程、Maven 和 C/C++ 系统依赖需明确手动处理。

PRoot 硬链接模拟使用持久 `rootfs/.zakocoder-links`，统一将规范化路径传入 `PROOT_L2S_DIR`；新 PTY 和命令共用准备逻辑。该目录不随下载缓存清理，不自动修复历史断链，也不提供额外隔离。

## 交互终端与预览

终端复用 Termux Apache-2.0 基础库和本仓库编译的 PTY JNI，最多四个会话、每会话 4,000 行滚动缓冲。卡片显示真实近期输出，点击进入；输入法直接连接 TerminalView，辅助键使用同一编码路径。新建、切换、重组与主题变化不重启已有 PTY。

Bash／Zsh 偏好只影响新终端，安装及探针脚本固定使用 Bash。应用管理的 ZDOTDIR 提供启动配置，最后加载已有 `/root/.zshrc`，不覆盖该文件。

AI 可按项目权限列出、读取、新建、输入、中断及关闭终端，与手动会话共享名额。任务结束不自动关闭服务；Ctrl+C 中断前台程序，关闭会话或停止全部终止归属进程。返回列表和 Activity 重建保留会话，应用进程被回收后不保证继续运行或恢复滚动历史。

网页服务应在可见终端前台运行，例如 `python3 -m http.server 8000 --bind 127.0.0.1`。应用可将本机 HTTP(S) 地址交给系统浏览器；自动候选仅含主机和端口，子路径需手填。默认回环访问不等于局域网开放，候选地址不证明服务可用。

## 设置、恢复与兼容

软件源、IPv4 DNS 写入 guest 配置；附加 PATH 及 HTTP／SOCKS5 代理用于新进程。代理接受 `http://host:port` 与 `socks5://[user:password@]host:port`（HTTP 不支持凭据，SOCKS5 凭据可省略），作用于环境镜像下载与 AI 请求。SOCKS5 凭据通过 Android Keystore 加密保存在本机、界面以打码形式显示、不写入日志；应用请求（HttpURLConnection／OkHttp 经 `java.net.Proxy.Type.SOCKS`）经 JDK SOCKS 认证层使用该凭据，并实测把目标域名发送给代理（SOCKS5 ATYP=3）、由代理侧解析；guest 进程在 HTTP 下导出 `http_proxy`／`https_proxy` 等变量，SOCKS5 下只导出 `socks5://` 形式的 `ALL_PROXY`（**配置认证时该变量包含凭据，对 guest 进程可见**），guest 工具是否在本机解析域名取决于工具（curl／git 对 `socks5://` 本机解析，仅 `socks5h://` 才由代理解析，本项目不导出 socks5h）；apt 等只识别 HTTP 代理的工具不会走 SOCKS5。缓存清理仅删除环境下载缓存，不删除项目、已装工具或持久硬链接目录。

取消 APT 可能留下 dpkg 未配置状态，需确认后执行 `dpkg --configure -a` 并重检；取消不等于回滚已安装包。暂不提供自动环境升级、重装迁移或任意 rootfs 管理。

前台服务提供运行通知和停止入口，但不能保证系统永不回收。最低 Android 版本、新系统前台服务行为、不同厂商与工具链仍需设备覆盖。发布门槛见[发布流程](RELEASING.md)，进程与权限实现约束见[架构](ARCHITECTURE.md)。
