# 自编译运行时验证记录

日期：2026-10-30。构建输入以 `toolchain.lock.json`、`patches/`、`build.py` 为准，产物以 `artifacts.lock.json` 为准。此记录不代表正式发行批准。

## 已完成的宿主验证

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

## 适用范围

没有对每个 PRoot 系统调用、所有内核、最低 Android 8、其他厂商、16 KiB 页设备或其他 ABI 作完整保证。16 KiB ELF 对齐通过不等于 16 KiB 真机已验证。构建配方当前固定 Linux x86_64 宿主，手机内社区 ARM64 NDK 构建尚未适配；不延续旧预编译路径的手机自构建已通过声明。Release 门禁保留，后续发行还需维护者审查对应源码包、许可证和实际设备覆盖。
