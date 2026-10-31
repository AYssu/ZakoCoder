# 独立 Android 原生运行时

本目录从保留在仓库内的源码编译 Android ARM64 或 x86_64 PRoot 和配套 loader，不下载或使用 OperitTerminalCore 的两个二进制。可将整个 `native-runtime/` 目录单独复制出去构建，不需要 Android 应用、Gradle 或上游 Git 仓库在线可用。

## 构建与检查

构建宿主为 Linux x86_64，准备 Python 3.12+、GNU Make、patch、binutils（readelf）、POSIX shell 和 Android NDK **28.2.13676358 / r28c**。NDK 通过 Android SDK Manager 安装，默认编译目标为 Android API 26、`arm64-v8a`；实验性 x86_64 目标通过显式 `--abi x86_64` 选择，见下文。源码及依赖已全部保留，工具链准备完成后编译无需网络。NDK 本身仍是外部工具链，不声称消除了所有外部依赖。

在本目录执行：

```sh
python3 build.py --verify-sources
python3 build.py --ndk /path/to/android-sdk/ndk/28.2.13676358
python3 checks/verify.py
python3 -m unittest discover -s checks -p 'test_*.py' -v
python3 bundle_sources.py
```

输出为 `build/jniLibs/arm64-v8a/libzakocoder_proot.so` 和 `libzakocoder_loader.so`。

| 文件 | 大小（字节） | SHA-256 |
| --- | ---: | --- |
| `libzakocoder_proot.so` | 326936 | `e7daf3d5389253950284bcb18b8fb0e5f0f38bca15bd8bfa4d275e3a1420e7ef` |
| `libzakocoder_loader.so` | 18248 | `32ac7e2a23ad4170e92c4578f642c65b19fad3830bd0a1c6f37a60e880cc97e7` |

这是可执行 ELF，用 `.so` 名称使 Android 在 APK 安装时提取到可执行的 nativeLibraryDir，不是供 JNI 加载的共享库。

`build/build.log` 保存实际编译命令及编译器输出，`build/build-record.json` 保存输入指纹与输出摘要。`--force` 会使用新的解包树重编译；`--build-dir /another/directory` 可用于验证不同目录构建一致性。源码、补丁或构建配方变更使缓存失效；本地输出内容被改动也会重建。归档校验先于解包和编译执行，没有预编译下载或未校验的本地覆盖入口。

`checks/verify.py` 同时核对 `artifacts.lock.json` 的固定摘要、目标 ABI 对应的 ELF 类型、Android linker64、系统动态依赖白名单、无可执行栈和 16 KiB LOAD 对齐。修改构建输入后，必须重新审查并更新独立产物锁、应用的两份 runtime 清单、设备验证记录；不得仅关闭摘要检查。

## 固定源码与适配

完整原始源码压缩包保留在 `sources/`，没有删去版权头或上游许可。准确下载地址、版本/提交和 SHA-256 见 `toolchain.lock.json`。

| 组件 | 固定版本 | 用途与许可 |
| --- | --- | --- |
| termux/proot | `b27d81a68d765f3462e807f4a14e01a03f8e89ea` | PRoot 及同一源码树的 `src/loader`；源文件允许 GPL-2.0-or-later。 |
| Samba talloc | 2.5.0 | 内存分配；库为 LGPL-3.0-or-later，归档内构建工具保留自己的许可。 |
| termux/libandroid-shmem | v0.7，提交 `7f0bd7e25dbdd146265aff7c6a890029e374622d` | Android 共享内存兼容；BSD-3-Clause。 |

PRoot 保留上游 seccomp、fake root、link2symlink、kill-on-exit 等实现，不重新实现其逻辑。启用上游 `PROOT_WITH_LIBANDROID_SHMEM`，静态链接 talloc（含其 libreplace 对象）和 libandroid-shmem，运行时只依赖 Android 系统库。loader 使用上游 freestanding/static 构建规则并单独提供，应用通过 `PROOT_LOADER` 指定 APK 中的文件；构建时故意不设置可用的宿主回退目录。

本地补丁在 `patches/`，原始归档保持未修改：

- `0001-shmem-private-tmp.patch`：不使用 Termux `_PATH_TMP`，改用必须为绝对路径的 `PROOT_TMP_DIR`，检查路径长度，并在创建链接失败时返回错误，避免不可写目录无限重试。
- `0002-proot-string-header.patch`：为使用 strcmp/memset 的源文件补充 string.h，适配现代 Clang。

`talloc-cross-answers.txt` 参考 termux/termux-packages **`b9229f0be3ebdcad3f99ec1860e5512d32efeb02`** 的 `packages/libtalloc/build.sh`，指定 Linux ARM64、小端和 API 26 的交叉编译运行检查结果；其余头文件/链接检查由 NDK 实际编译完成。PRoot 构建选项参考同提交 `packages/proot/build.sh`，shmem 版本来自 `packages/libandroid-shmem/build.sh`。上游规定各包构建脚本/补丁跟随对应包许可，构建基础设施为 Apache-2.0；完整声明保存于 `licenses/Termux-packages-LICENSE.md`。本项目编写的构建和检查脚本使用仓库 Apache-2.0 许可；第三方源码不改变许可。

PRoot 的 GPL-2.0-or-later 源码允许选择后续版本；本目录把静态链接 LGPL-3.0-or-later talloc 的 PRoot 组合制品按 **GPL-3.0-or-later** 分发，loader 源码保留 GPL-2.0-or-later。`licenses/` 保留 PRoot COPYING、GPL v3、talloc LGPL v3、shmem BSD 与参考构建脚本声明；APK 同样包含实际运行组件的许可。应用自有 Kotlin/Compose 代码继续使用 Apache-2.0。

公开提供二进制时，应在同一分发位置提供 `bundle_sources.py` 生成的完整对应源码包及摘要（包含原始源码、补丁、配方、交叉编译答案和许可），保留实际采用的工具链版本与设备验证记录；不能只指向可变的上游分支。talloc 静态链接的重新构建输入在源码包中，不要求使用者依赖某个远端预编译文件。

## 接入应用和设备验证

应用的 `prepareRuntime` 调用 `scripts/prepare_runtime.py`，由本子项目构建，确认独立锁与应用清单一致、校验 ELF 和摘要后复制到忽略的 `runtime-artifacts/` 打包目录。每次构建均校验，已存在的旧下载制品会被自行编译产物替换。Ubuntu 镜像、用户项目和持久硬链接目录不迁移、不清空。

设备检查使用应用现有 Android instrumentation，并增加 `NativeRuntimeArtifactDeviceTest` 检查安装后的实际摘要、持久硬链接、符号链接、tar、SQLite、Python 子进程/线程/套接字、Node 和离线 npm 缓存操作。现有测试覆盖启动矩阵、退出码、工作目录、PTY、Activity 重建、取消子进程和 MCP stdio。安装器测试在独立私有子树覆盖损坏镜像、取消、重试安装及新环境执行；不得清除用户环境来测试。

设备检查固定使用独立包名 `dev.zakocoder.app.runtimecheck`，通过 `-PruntimeVerification=true` 构建；测试 Provider authority 同样独立。runner 的 `--provision` 会在独立包安装 Ubuntu、Python 和 Node，`--install` 会先核对包名与原生文件摘要，不覆盖原包。也可用 `--apk-root` 指向临时源码副本的构建输出，避免与其他开发会话共享构建目录。运行命令和实际结果见 [VALIDATION.md](VALIDATION.md)。仅安装/测试本地 Debug 包，不构成正式发行授权。Release 仍等待维护者对分发材料和设备覆盖的审查。

当前构建配方仅验收 Linux x86_64 宿主；手机内部的社区 ARM64 NDK 不是这套固定工具链，不能直接据旧文档宣称可在手机内构建当前应用。最低 Android 版本、其他内核、16 KiB 页设备及各 ABI 的运行行为需要独立验证；不能把静态 16 KiB 对齐检查当成该页大小设备运行验收。

## x86_64 兼容性测试运行时

默认构建仍为 `arm64-v8a`；其固定摘要未改变。为了在 x86_64 Android 模拟器验证系统兼容性，可显式构建同一份固定源码：

```sh
python3 build.py --ndk /path/to/android-sdk/ndk/28.2.13676358 --abi x86_64
python3 checks/verify.py build/x86_64/jniLibs/x86_64 --abi x86_64
python3 ../scripts/prepare_runtime.py --ndk /path/to/android-sdk/ndk/28.2.13676358 --abi x86_64
```

x86_64 使用 NDK API 26 的原生编译器，不包含 QEMU，不在 ARM64 制品中混入宿主可执行文件；交叉编译答案仅将 `uname machine` 改为 `x86_64`，其余与 Android 64 位小端检查相同。独立产物锁的 `architectures.x86_64` 与应用两份清单对应，ELF 机器类型、Android linker64、依赖白名单、无可执行栈、16 KiB LOAD 对齐及逐文件摘要均按目标 ABI 验证。构建缓存和源树按 ABI 隔离。

| x86_64 文件 | SHA-256 |
| --- | --- |
| `libzakocoder_proot.so` | `5e0b2b085965661adbc0f701005ca7d63fdcf9dcb79e603bc7fe84c82354d63a` |
| `libzakocoder_loader.so` | `feab2863c28683ffa4e028976b8417d8dbb3c1e3d631c862f4097efdafcb5537` |

x86_64 Ubuntu 为同一 proot-distro v4.18.0 的 `ubuntu-noble-x86_64-pd-v4.18.0.tar.xz`。官方摘要来源为 [固定插件提交 a582103](https://github.com/termux/proot-distro/blob/a58210313f1bb8b09f2ed4d8de2ab9eef0e28483/distro-plugins/ubuntu.sh)，实际下载后校验为 `3a769bce23985effb504140d43b6ddd73dac9e261d1932894afa31de81e45414`，压缩大小 67,761,056 字节，常规文件总大小 262,217,053 字节。清单保留 ARM64 顶层配置，在 `architectures.x86_64` 显式指定该镜像；不能将已有 ARM64 根目录直接切换架构。

此运行时只支持基础环境兼容性验证，不代表 x86_64 Android JDK、SDK、NDK 开发工具包已适配。设备执行结果应单独记录，原生构建与静态检查不能替代设备验证。

应用默认只打包 ARM64。兼容性测试通过 `-PcompatibilityAbis=x86_64` 构建单 ABI APK，或通过 `-PcompatibilityAbis=arm64-v8a,x86_64` 显式打包两种 ABI；PackageManager 选择安装架构后，应用据对应 `nativeLibraryDir` 选择同架构清单与 Ubuntu 镜像。完整命令、系统覆盖边界及手动 CI 见[Android 兼容性说明](../docs/ANDROID_COMPATIBILITY.md)。这里的 x86_64 是 Android 应用运行时目标，不代表在 Ubuntu 中提供 x86_64 Android SDK／NDK 工具包。
