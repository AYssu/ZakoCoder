# 自编译运行时验证记录

更新：2026-10-31。构建输入以 `toolchain.lock.json`、`patches/`、`build.py` 为准，产物以 `artifacts.lock.json` 为准。此记录区分已完成的 Debug／原生验证与待完成的 Beta Release 验收，不代表稳定版发行批准。

## ARM64 初始宿主验证（2026-10-30）

- Linux x86_64，Android NDK r28c `28.2.13676358`，Android API 26，Python 3.14。
- 仓库目录与另一个干净目录分别从源码构建，两个文件逐字节摘要一致。
- PRoot SHA-256：`e7daf3d5389253950284bcb18b8fb0e5f0f38bca15bd8bfa4d275e3a1420e7ef`。
- loader SHA-256：`32ac7e2a23ad4170e92c4578f642c65b19fad3830bd0a1c6f37a60e880cc97e7`。
- ELF 检查通过：ARM64 PIE PRoot，Android `/system/bin/linker64`，动态依赖仅 `liblog.so`、`libandroid.so`、`libdl.so`、`libc.so`；loader 为无动态依赖的 ARM64 static ELF；二者无可执行栈，LOAD 对齐为 16 KiB。
- 源码归档摘要检查、篡改归档拒绝、错误架构/解释器/动态库/页对齐拒绝、固定产物摘要及源码包确定性检查通过（11 项）。
- 将导出的源码包解压至仓库外，不依赖主项目完成构建、ELF 校验与 11 项检查，产物摘要一致。
- 应用 Debug、AndroidTest APK、Lint、应用和终端基础库单元测试构建检查通过；独立包名使用单独临时源码副本全新构建通过。

复现宿主检查（仓库根目录，工具链已安装）：

```sh
python3 native-runtime/build.py --ndk "$ANDROID_HOME/ndk/28.2.13676358" --force
python3 native-runtime/checks/verify.py
python3 native-runtime/build.py --ndk "$ANDROID_HOME/ndk/28.2.13676358" --build-dir /tmp/zakocoder-native-repro
python3 native-runtime/checks/verify.py /tmp/zakocoder-native-repro/jniLibs/arm64-v8a
python3 -m unittest discover -s native-runtime/checks -p 'test_*.py' -v
```

## 双 ABI 补充检查（2026-10-31）

同一固定源码与 NDK r28c 已加入显式 `x86_64` 目标，默认仍为 ARM64。x86_64 PRoot／loader 的机器类型、Android linker64、依赖白名单、不可执行栈、16 KiB LOAD 对齐和固定摘要已校验；产物摘要见 `artifacts.lock.json` 的 `architectures.x86_64`。静态检查不代表真实 16 KiB 页设备运行通过。

当前原生检查共 **14 项通过**，包括两种 ABI 的参数、固定清单、错误 ELF／归档拒绝及源码分发检查。上面的 11 项是初始 ARM64 记录，不能用它替代当前检查数量；这里不把新增 x86_64 静态检查扩大为全部工具链或全部内核保证。

```sh
python3 native-runtime/build.py --ndk "$ANDROID_HOME/ndk/28.2.13676358" --abi x86_64
python3 native-runtime/checks/verify.py native-runtime/build/x86_64/jniLibs/x86_64 --abi x86_64
python3 -m unittest discover -s native-runtime/checks -p 'test_*.py' -v
```

应用默认 ARM64 Debug 构建、Lint、425 项 Kotlin/JVM 单元测试及上述 14 项原生检查通过。Android 11–17（含 12L）AOSP x86_64 已完成基础应用、真实 SAF、进程死亡恢复、递增版本覆盖安装、Ubuntu／PTY／Python HTTP 与通知矩阵，共 336 个设备／用例组合。具体结果、定向复测与限制见[兼容性记录](../docs/ANDROID_COMPATIBILITY_RESULTS.md)。该矩阵不使用 ARM 翻译层，全部设备页大小为 4096；升级比较本轮代码的 versionCode 1 → 2，不代表所有历史格式迁移。

## 设备验证

目标为 Redmi 23049RAD8C / Android 13 / ARM64。为避开其他开发会话，使用 `-PruntimeVerification=true` 生成 `dev.zakocoder.app.runtimecheck` 及 `.test` 两个独立包，拥有独立 UID、项目、设置和 Ubuntu。测试 Provider authority 同样随测试包名生成；不卸载、不清除或迁移原应用数据。

原包的最初检查通过了启动矩阵，随后受另一个 instrumentation 会话影响而停止，该批次不计为完整通过。另一次 ActivityScenario 启动被 MIUI 后台启动策略拒绝；测试 runner 现按每项测试，通过 ADB 发送匹配启动 Intent，不修改系统权限设置。首次独立环境安装中还观测到 MIUI 后台冻结（`do_freezer_trap`），切到前台后恢复；独立 Ubuntu 与 Python/Node 工具安装检查通过。旧 shell 测试遗漏项目工作目录，在进入 PRoot 前失败，已补齐测试目录。随后发现无条件重复启动 Activity 会干扰 `ActivityScenario.recreate()`，runner 改为先检查前台状态，仅补救未启动的 Activity。

最终独立包兼容性批次通过：**9/9，40.821 秒**。日志为 `build/device-results.txt`；独立环境安装为 **1/1，859.783 秒**，日志为 `build/device-provisioning.txt`。以上日志为本地验证产物，不进入源码包。随后从 `main` 建立独立运行时 PR 分支，排除并行 AI 功能改动，重新完成全新 APK/AndroidTest、lint 与单元测试构建，并在同一独立包再次通过 **9/9，41.192 秒**；APK 内两个文件逐字节等于自编译产物。覆盖：

- 实际安装文件与固定自编译摘要一致、可执行，运行时启动矩阵通过。
- 工作目录、中文与空格文件名、退出码、取消与子进程回收、PTY 中文输出、Activity 重建时终端进程保留和关闭回收。
- Python、Node 实际执行；SQLite、线程、socketpair、子进程、模拟 root 身份；离线 npm 打包与缓存。
- tar 解包、软链接、跨 PRoot 进程硬链接持久化、私有链接仓库约束、shell 配置保持及 MCP stdio。
- 损坏离线镜像拒绝、安装取消及使用同一缓存重试成功。

设备复现命令（序列号由操作者显式选择，先下载并核对清单中的固定 Ubuntu 镜像）：

```sh
./gradlew -PruntimeVerification=true :app:assembleDebug :app:assembleDebugAndroidTest
python3 native-runtime/checks/device.py --serial DEVICE_SERIAL \
  --image /path/to/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz --install --provision
```

也可在临时源码副本构建，并通过 `--apk-root /path/to/snapshot` 指向副本。runner 安装前用 aapt2 检查应用/测试包 ID 并检查 APK 内两个文件的摘要；拒绝把普通包安装到原应用。`--provision` 仅在独立包安装固定 Ubuntu 镜像及通过 APT/npm 获取 Python、Node 等工具，涉及网络；后续兼容性测试使用离线镜像与 npm 操作。测试镜像缓存仅放在独立包的专用测试目录，用完删除；已有独立 Ubuntu 留作复验。

## Beta Release 候选补充（2026-10-31）

最终本地 Release R8 编译、Release Lint、应用 280 项、终端 145 项及脚本 58 项测试、AndroidTest 编译通过；14 项原生检查及 ELF／固定摘要校验通过。API 35 的最终真实 R8 QA 包完成项目创建、编辑保存、重启及配置持久化，并取得 4 张日夜截图。QA 与 ARM64 公开候选包的全部代码／资源、共有原生制品、版本身份和签名证书一致。公开附件收集及干净树工作流本地模拟仍在收尾，GitHub Actions 尚未实际运行。

这些证据不包含最终公开 ARM64 Release 包的真机安装、同签名升级或 Ubuntu／终端／HTTP 冒烟。R8 mapping 单独作为诊断 Artifact 提供，不附到 Release；公开仓库的 Actions Artifact 仍可能被有读取权限的登录用户下载，不是密钥或用户数据存储位置。

Beta 用户附件仅保留 APK 与 `SHA256SUMS.txt`。构建详情放发布正文，完整运行时源码通过该页指向的不可变提交／标签 GitHub Source code 归档取得；固定 `sources/`、补丁、工具链与许可必须完整保留。内部独立源码包、构建和测试材料留在 Actions 校验，不以限期 Artifact 代替对应源码提供。

## 适用范围

没有对每个 PRoot 系统调用、所有内核、最低 Android 8、其他厂商、16 KiB 页设备或各 ABI 的所有行为作完整保证。16 KiB ELF 对齐通过不等于 16 KiB 真机已验证。构建配方当前固定 Linux x86_64 宿主，手机内社区 ARM64 NDK 构建尚未适配；不延续旧预编译路径的手机自构建已通过声明。稳定版门禁保留。Beta 预发布通道须经维护者确认，并按[发布流程](../docs/RELEASING.md)核对对应源码、许可证、最终签名及 R8 QA 证据并明确真机限制；不允许仅设置 Beta 参数跳过材料与验证。首批 Beta 使用不可调试的 Release + R8 构建，默认复用仓库 CI Debug 证书（可选自有签名），并非正式长期签名；自动检查使用 API 35 x86_64 同签名 R8 QA 包，校验其与公开 ARM64 包的 DEX／资源／assets 一致；最终 ARM64 Release 的真机安装、同签名升级与终端／HTTP 冒烟仍未完成，须列入预发布限制，不引用 QA 或 Debug 矩阵冒充完成。稳定版需补齐相关实际证据。
