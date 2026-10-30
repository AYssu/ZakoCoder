# 运行环境与终端

当前仅构建 ARM64 调试版，x86_64 模拟器未适配。运行环境采用 Ubuntu 用户空间与 PRoot，不需要 QuickJS；它是开发工具兼容层，不是安全沙箱。

## 安装与发行限制

`runtime/artifacts.json` 固定自编译原生制品提交及 Ubuntu 镜像版本、大小、SHA-256 与解包前缀。构建脚本 `scripts/prepare_runtime.py` 调用独立 `native-runtime/` 子项目，从仓库内固定源码编译 PRoot／loader，校验产物锁与 ELF 后复制到忽略目录 `runtime-artifacts/`；不下载第三方预编译 PRoot，不依赖本地参考源码。

PRoot／loader 已使用固定 NDK 自编译，完整输入与测试见[独立子项目](../native-runtime/README.md)。Release 打包仍等待维护者审查分发材料和设备覆盖；准确来源和许可见[第三方说明](../THIRD_PARTY.md)。

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
| Android | JDK 17、SDK Platform 36、Build Tools 36.0.0、Gradle 8.13 与 Linux ARM64 AAPT2／AIDL／zipalign／split-select；用户确认 SDK 许可后安装 |
| Android NDK | 可选社区 GNU/Linux ARM64 NDK r28c、APT CMake、Ninja、Python；要求 Android 工具包已安装或同批安装 |
| Flutter | 仅检测，兼容 SDK 和额外工具链由使用者安装 |

工具包独立于项目存在；类型识别只推荐，不隐式勾选或安装。包名来自固定白名单，多选后展示去重安装清单，确认后执行 APT；保留签名验证，不跳过校验。探针真实执行版本命令，分别表示缺失、失败及就绪。运行配置和安装改变后刷新，手动修改环境后可主动重检。发行版版本未必满足项目要求，当前无任意包市场、卸载或通用版本管理。

项目识别为有界扫描，不执行清单。依赖计划检查根清单，支持 npm ci/install、独立 `.venv` 与 pip、go mod download、cargo fetch、Gradle wrapper dependencies；在没有 Ubuntu 时也可识别计划，执行仍要求环境就绪并确认 cwd 和脚本。项目或依赖安装脚本可能有副作用，不能当作纯下载。嵌套工程、Maven 和 C/C++ 系统依赖需明确手动处理。

PRoot 硬链接模拟使用持久 `rootfs/.zakocoder-links`，统一将规范化路径传入 `PROOT_L2S_DIR`；新 PTY 和命令共用准备逻辑。该目录不随下载缓存清理，不自动修复历史断链，也不提供额外隔离。

## Android Debug 构建

环境工具包中的 Android 需要先装 Ubuntu、至少 3 GiB 可用空间，并显式同意 Android SDK 许可。APT 安装 JDK 17、curl、CA、unzip 及运行库；之后在 guest 安装 SDK Command-line Tools 19.0、Platform 36、Build Tools 36.0.0 与 Gradle 8.13。四项需要执行的原生构建工具使用固定摘要的 Linux ARM64 制品。Google SDK 包由 sdkmanager 校验其发布元数据；CLI、Gradle、ARM64 制品另核对固定 SHA-256。版本、来源和许可见[第三方说明](../THIRD_PARTY.md)。

完整工具链验证通过后才写入 `/opt/zakocoder/android/.ready`，失败不会标记就绪。取消后 APT 副作用不会回滚；下一次安装取得独占锁后清理遗留暂存（强制结束时 shell 的退出清理不一定执行）。损坏的本版本托管工具链会在新安装验证通过后换入，旧目录保留为 `.android-backup.*`；不会替换未知版本或用户自建目录。已校验的固定下载缓存和断点位于 guest `/var/cache/zakocoder-android`，占用计入已用空间，不属于环境页的镜像下载缓存。不要手工删除共享 Gradle 缓存或签名来排查构建问题。

后续新终端和项目命令注入 ANDROID_HOME、JDK 17、Gradle PATH、AAPT2 路径覆盖。适配仅在托管工具链就绪时生效，不写项目 `local.properties`，不覆盖用户 Gradle 配置或启动文件；安装前已打开的终端需重新创建。HTTP／SOCKS 设置同时传递给 SDK manager 和 Gradle 的 Java 网络层；Google／Maven／GitHub 不可达时需要先配置可用网络或代理，软件不擅自更换依赖仓库。

SDK manager 和 guest Gradle JVM 不继承 Android 应用的 SOCKS5 认证器，目前只支持 HTTP 或无认证 SOCKS5 代理。选择需账号密码的 SOCKS5 时，Android SDK 安装会在下载及 APT 变更前明确拒绝；已有 SDK 的 Gradle 联网构建也需先改用受支持的代理。该限制不禁止普通终端及支持认证 SOCKS5 的 guest 工具运行。

新建项目可选 Android Java 或 Kotlin：系统 Activity + TextView，固定 AGP 8.13.2、Kotlin 2.2.21（Kotlin 模板）、compileSdk 36、Build Tools 36.0.0。终端执行 `gradle --no-daemon :app:assembleDebug`；项目构建命令配置会预填相同脚本，仍需用户保存并确认执行。模板不携带 Gradle Wrapper 二进制；导入已有项目优先使用其 Wrapper，但其 JDK、插件、SDK 版本与额外原生工具需要另行适配。不能由本阶段推断任意 Android／Compose／NDK 工程都已兼容。

构建产物通常位于 `app/build/outputs/apk/debug/app-debug.apk`。从项目文件页打开 APK，先确认文件；未知来源权限交给系统设置，授权返回后需重新打开文件。应用在 IO 线程将 APK 复制到独立缓存，通过仅暴露 `cache/apk-install/` 的 FileProvider 给系统安装器只读授权，不开放整个项目，不静默安装、卸载或覆盖用户数据。缓存单 APK 上限 512 MiB、总计 1 GiB，保留近期副本供安装器读取，超过 24 小时的副本在下次准备时清理。系统负责最终格式、签名、设备兼容检查和用户安装确认。

### 可选 NDK / JNI 构建

Android NDK 是独立可选工具包，需要 Android 工具包就绪、额外至少 3 GiB 可用空间。安装固定摘要的社区 GNU/Linux ARM64 NDK r28c（28.2.13676358）到 `/opt/zakocoder/android-ndk-r28c`，保留制品许可证；不是直接运行 Google 的 x86_64 Linux 编译器。来源和限制见[第三方说明](../THIRD_PARTY.md)。APT 安装 CMake、Ninja、Python 与解压依赖。

安装与 SDK 共用独占锁，完整解包并实际运行 Clang、LLD、CMake、Ninja 后才标记就绪。NDK 注册到托管 SDK 的 `ndk/28.2.13676358`，不覆盖已有用户目录或不同目标的链接；已损坏的本版本托管安装在替换前保留备份。固定下载缓存与 SDK 共用目录，取消不会回滚 APT 包。SDK 修复后若注册丢失，重新安装 NDK 工具包可恢复注册。

新终端和项目命令注入 NDK 路径与 ANDROID_NDK_HOME/ROOT。新建“Android Kotlin + C++”项目，运行相同的 `gradle --no-daemon :app:assembleDebug`；模板固定 NDK r28c、CMake 3.28.3，仅构建 arm64-v8a，以静态 libc++ 链接 JNI 库。CMake 从 Ubuntu PATH 查找匹配版本，避免下载 x86_64 的 SDK CMake。已有工程需自行匹配 ndkVersion、CMake 版本及 ABI；不会改写用户项目配置。

其他 NDK 版本／ABI／复杂第三方库、模拟器和正式签名发行不在本阶段验收范围。

### 在手机上构建 ZakoCoder 自身（历史验证）

在切换独立自编译运行时前，曾在 Android 13 ARM64 真机的应用 PRoot 内，使用上述 Android 和 NDK 工具包完成当时 ZakoCoder 源码的 `:app:assembleDebug`，覆盖 Compose 页面和终端 `ndk-build` JNI 库。首次完整构建（包含依赖下载）耗时约 8 分钟，生成 APK 的签名验证通过；本次未用该包覆盖安装现有应用，不代表任意 Compose 工程都已兼容。

当前 `native-runtime/` 固定的运行时构建宿主为 Linux x86_64，手机 ARM64 社区 NDK 尚未适配该配方与产物锁，不能把以下历史命令当作当前仓库手机自构建已通过。历史验证命令为：

```sh
python3 scripts/check_repository.py
gradle --no-daemon :app:assembleDebug --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process --console=plain
```

这里使用工具包内与 Wrapper 相同版本的 Gradle 8.13，限制并行和堆大小以降低手机内存压力；仍需要足够可用内存、存储及可访问依赖仓库的网络。本次使用临时 HTTP 代理访问 Google、Maven、GitHub，直连网络未验收。当时构建会下载固定 PRoot 验证制品；当前版本改为源码编译，新的宿主限制见上文。

Debug 签名由构建环境生成，手机内生成的签名可能不同于现有安装包或 CI 包。签名不同不能直接覆盖安装；不要为测试自构建而卸载承载项目和会话的应用。

## 交互终端与预览

终端复用 Termux Apache-2.0 基础库和本仓库编译的 PTY JNI，最多四个会话、每会话 4,000 行滚动缓冲。卡片显示真实近期输出，点击进入；输入法直接连接 TerminalView，辅助键使用同一编码路径。新建、切换、重组与主题变化不重启已有 PTY。

Bash／Zsh 偏好只影响新终端，安装及探针脚本固定使用 Bash。应用管理的 ZDOTDIR 提供启动配置，加载已有 `/root/.zshrc`，不覆盖该文件；自定义提示符优先。托管 `.zshenv` 禁止 Ubuntu 系统配置提前重复执行 compinit；默认 Zsh 在首次按 Tab 时初始化补全，保留目录权限检查；已有非空用户 `.zshrc` 时保留启动时初始化，兼容其 compdef 配置。带空格的项目名作为提示符数据显示，不注册为 Zsh 命名目录，实际工作目录仍为项目 UUID 路径。

AI 可按项目权限列出、读取、新建、输入、中断及关闭终端，与手动会话共享名额。任务结束不自动关闭服务；Ctrl+C 中断前台程序，关闭会话或停止全部终止归属进程。返回列表和 Activity 重建保留会话，应用进程被回收后不保证继续运行或恢复滚动历史。

网页服务应在可见终端前台运行，例如 `python3 -m http.server 8000 --bind 127.0.0.1`。应用可将本机 HTTP(S) 地址交给系统浏览器；自动候选仅含主机和端口，子路径需手填。默认回环访问不等于局域网开放，候选地址不证明服务可用。

## 设置、恢复与兼容

未配置用户默认使用清华 Ubuntu 软件源与 `223.5.5.5,119.29.29.29` DNS；已有保存选项保持不变。软件源、IPv4 DNS 写入 guest 配置；附加 PATH 及 HTTP／SOCKS5 代理用于新进程。代理接受 `http://host:port` 与 `socks5://[user:password@]host:port`（HTTP 不支持凭据，SOCKS5 凭据可省略），作用于环境镜像下载与 AI 请求。SOCKS5 凭据通过 Android Keystore 加密保存在本机、界面以打码形式显示、不写入日志；应用请求（HttpURLConnection／OkHttp 经 `java.net.Proxy.Type.SOCKS`）经 JDK SOCKS 认证层使用该凭据，并实测把目标域名发送给代理（SOCKS5 ATYP=3）、由代理侧解析；guest 进程在 HTTP 下导出 `http_proxy`／`https_proxy` 等变量，SOCKS5 下只导出 `socks5://` 形式的 `ALL_PROXY`（**配置认证时该变量包含凭据，对 guest 进程可见**），guest 工具是否在本机解析域名取决于工具（curl／git 对 `socks5://` 本机解析，仅 `socks5h://` 才由代理解析，本项目不导出 socks5h）；apt 等只识别 HTTP 代理的工具不会走 SOCKS5。缓存清理仅删除环境下载缓存，不删除项目、已装工具或持久硬链接目录。

取消 APT 可能留下 dpkg 未配置状态，需确认后执行 `dpkg --configure -a` 并重检；取消不等于回滚已安装包。暂不提供自动环境升级、重装迁移或任意 rootfs 管理。

前台服务提供运行通知和停止入口，但不能保证系统永不回收。最低 Android 版本、新系统前台服务行为、不同厂商与工具链仍需设备覆盖。发布门槛见[发布流程](RELEASING.md)，进程与权限实现约束见[架构](ARCHITECTURE.md)。

## 绑定本地项目

项目库的“绑定本地目录”使用图形化文件夹浏览器，逐级进入包含源码的目录并点击“使用此文件夹”，无需手工输入路径，不复制文件。目录枚举在 IO 调度器进行，符号链接不作为可浏览项目。编辑器、AI 文件工具和项目进程解析同一个根；PRoot 中仍以稳定 UUID 挂载在 `/workspace/<uuid>`，目录名含空格和中文无需手动转义。禁止系统目录、其他应用私有目录、符号链接、重叠绑定及含 PRoot 绑定分隔符的路径。

Android 11 及以上通过系统设置单独申请“管理所有文件”访问（不是只授权所选文件夹）；Android 8–10 使用存储权限。拒绝授权不影响既有私有项目。该权限不是 AI 审批，也不提供 root 或云盘访问能力；Google Play 分发还需独立符合相应政策。

共享存储通常不支持完整 POSIX 权限、符号链接或直接执行文件；npm、Git、原生构建等工程可能受限。应用不会把绑定失败静默改成导入。需要完整 Linux 文件语义时使用应用私有工作区。移除绑定项目保留原文件，私有会话、草稿等仍按项目生命周期管理。

图形化选择不把 SAF URI 推断成 Linux 路径；只提供文档 URI 的来源仍应导入副本。
