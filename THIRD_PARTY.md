# 第三方代码、制品与许可

ZakoCoder 自有代码使用根目录 Apache-2.0。第三方许可证不因本项目许可证改变，`example/` 不属于构建或发行内容。

## 可复用终端基础库

来源：https://github.com/termux/termux-app ，固定提交 `8629e632fcb95da272221be327db653fb24befe9`。仅包含 `terminal-emulator/src` 和 `terminal-view/src`，不包含 Termux 应用模块或 Operit 业务界面。

上游 LICENSE.md 明确将这两个库列为 Apache-2.0 例外。各目录的 `UPSTREAM-LICENSE.md`、`LICENSE-APACHE-2.0` 和源码版权头保留原声明。改动记录见 [third_party/README.md](third_party/README.md)：本仓库 Gradle/NDK 配置，以及使用 ParcelFileDescriptor 公共 API 管理 PTY 描述符，避免 Android 隐藏字段反射。

## 终端字体

JetBrains Mono Regular **2.304**，官方来源 https://github.com/JetBrains/JetBrainsMono ，标签 `v2.304` 对应固定提交 `cd5227bd1f61dff3bbd6c814ceaf7ffd95e947d9`，上游路径 `fonts/ttf/JetBrainsMono-Regular.ttf`。原始字体未修改，273,900 字节，SHA-256 `a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f`。

SIL Open Font License 1.1；原始版权与完整许可证保存在 APK 的 `assets/licenses/JetBrainsMono-OFL.txt`，摘要 `30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad`。字体本体在 `assets/fonts/JetBrainsMono-Regular.ttf`，随 APK 离线提供，不在运行时下载。终端按单元格绘制，不启用编程连字；不依赖 Nerd Font 图标。关于页列出字体与许可。

## Sora 原生代码编辑器

官方来源 https://github.com/Rosemoe/sora-editor ，使用 Maven Central 的 `io.github.Rosemoe.sora-editor:editor:0.23.6` 、`language-textmate:0.23.6` 和 `editor-lsp:0.23.6`，源码标签 `0.23.6` 对应提交 `b1777d0d465b42515739ffe529b4ec1634ac2175`。不修改上游库，不引用 example。App 通过 AndroidView 适配，词法规则及主题为本项目自写。

| 制品 | SHA-256 |
| --- | --- |
| editor-0.23.6.aar | `d6d87ac499b64d4f1e89e41f355d343dc44674f38e37181f4bd58a5cb71a5360` |
| editor-0.23.6-sources.jar | `b3573309e8e3e4d1006c6a0da7aa94fa0712e87d1d256650a5e39349850c78ce` |
| editor-lsp-0.23.6.aar | `1476ed87220d03827ac056380e41548c6e66b88b0e26fb80f7eafbceebede3cc` |
| language-textmate-0.23.6.aar | `909576591348f999b318ef601568a208550ee1c0cae375d70626f70f2b7c63ba` |
| language-textmate-0.23.6-sources.jar | `ca06b725576c3de059d0625d7971803c08046cd4907b3235d88b82baa2d4311b` |

Sora 声明 LGPL-2.1（源码头允许后续版本），原始许可证保存在 APK `assets/licenses/SoraEditor-LGPL-2.1.txt`。language-textmate 内含 TM4E，源文件保留 EPL-2.0 头，完整 EPL 保存在 `assets/licenses/TM4E-EPL-2.0.txt`。对应源码可从以上固定提交及 Maven 同版本 `-sources.jar` 获取，原版权头未改变。公开发行需随本项目构建输入提供依赖对应源码和可重新构建/链接的方法，不将本项目 Apache-2.0 当作第三方代码许可；Beta 的对应源码入口和设备边界按发布流程核对，稳定版仍需补齐最终真机与升级证据。

TextMate 传递依赖包括 Gson 2.13.1（Apache-2.0）、JCodings 1.0.63、Joni 2.2.6（MIT）、SnakeYAML Engine 2.9（Apache-2.0）、JDT annotations 2.3.100（EPL-2.0）。来源及许可随 Maven POM/JAR 保留；LSP 模块用于连接用户在本机 Ubuntu 按需安装的语言服务器，不使用云端补全。新引入的 AndroidX/Kotlin 依赖继续遵循各自声明。

LSP 新增传递依赖 Eclipse LSP4J 0.24.0（`org.eclipse.lsp4j` 与 `org.eclipse.lsp4j.jsonrpc`），来源 https://github.com/eclipse-lsp4j/lsp4j ，许可证 EPL-2.0，正文随 APK 中现有 `assets/licenses/TM4E-EPL-2.0.txt` 提供。对应 Maven 同版本源码包可获取源码；未修改上游制品。两个 JAR 的 SHA-256 分别为 `2caea31ffa2abd13c6f5d13489be071ad08f108eae83a34473ac72d96cf72922` 和 `7721572e849d4f359b46ede55d5604b982ff498d0d92ebc70e18029f59823c08`。

按需下载的语言服务器不打包进 APK：Python LSP Server 1.13.1（MIT，https://github.com/python-lsp/python-lsp-server ）及 Pyflakes 3.4.0（MIT，https://github.com/PyCQA/pyflakes ）；TypeScript Language Server 4.4.0（MIT，https://github.com/typescript-language-server/typescript-language-server ）与 TypeScript 5.9.3（Apache-2.0，https://github.com/microsoft/TypeScript ）。安装保留 pip/npm 分发包的许可证及依赖元数据，工具目录独立于用户工程；间接依赖由包管理器解析，不宣称完整锁定依赖树。

## PRoot 与 loader：仓库内源码自行编译

`native-runtime/` 是独立构建子项目，包含完整未修改的源码归档、固定工具链清单、独立补丁、构建脚本、产物锁及测试；无需从 OperitTerminalCore 下载二进制。来源、准确摘要和分发要求见 [子项目说明](native-runtime/README.md)、[工具链锁](native-runtime/toolchain.lock.json) 和 [验证记录](native-runtime/VALIDATION.md)。

- termux/proot 提交 `b27d81a68d765f3462e807f4a14e01a03f8e89ea`：同一源码树编译 PRoot 与 `src/loader`，源文件为 GPL-2.0-or-later，原始 COPYING 保留。
- Samba talloc 2.5.0：库为 LGPL-3.0-or-later，包含原始源码和 libreplace 构建输入；构建工具遵循归档内各自声明。
- termux/libandroid-shmem v0.7，提交 `7f0bd7e25dbdd146265aff7c6a890029e374622d`：BSD-3-Clause。
- NDK r28c `28.2.13676358`、Android API 26、ARM64；静态链接 talloc/libreplace 和 shmem，动态依赖仅为 Android 系统库。PRoot 组合制品按 GPL-3.0-or-later 分发，loader 保留 GPL-2.0-or-later；不将本项目 Apache-2.0 当作第三方代码许可。

本地修改为 shmem 私有临时目录适配、错误返回及 PRoot 缺失头文件修复，均独立保存在 `native-runtime/patches/`。talloc 交叉编译答案及 PRoot 构建选项参考 termux/termux-packages 固定提交 `b9229f0be3ebdcad3f99ec1860e5512d32efeb02`，保留参考仓库完整许可声明（包脚本随对应包许可，基础设施为 Apache-2.0）。源码归档不引用 `example/`。

APK 的 `assets/licenses/` 保留 PRoot COPYING、GPL v3、talloc LGPL v3 和 shmem BSD 原文。公开分发二进制时，发布页须清楚链接本次不可变提交／标签的 GitHub **Source code** 完整归档；其中 `native-runtime/` 保留全部原始源码、补丁、构建脚本、工具链锁、交叉编译输入与许可，提供与 APK 对应的完整重建材料。不得只给出可变上游链接，也不得以限期 Actions Artifact 代替对应源码的持续提供。用户附件可仅含 APK 与校验摘要，许可及版权声明仍在 APK 和源码保留。

`python3 native-runtime/bundle_sources.py` 生成的独立源码包继续用于内部候选校验；发布前核对 GitHub 固定归档包含完整输入。历史 Operit 来源许可副本仅作为原验证制品记录保留，不代表当前使用其二进制。Beta 按明确的发布门槛及设备限制执行，稳定版仍需维护者审查；不因运行时替换自动发行。

## Ubuntu 基础系统

来源：https://github.com/termux/proot-distro/releases/tag/v4.18.0 ，资源 `ubuntu-noble-aarch64-pd-v4.18.0.tar.xz`；确切大小、摘要、解包前缀见制品清单。不是本项目编译的操作系统。Ubuntu 24.04 各软件包遵循各自许可，在环境 `/usr/share/doc/<package>/copyright` 中保留；APT 从用户选定的 Ubuntu 官方或清华镜像获取签名软件包。环境包不提交源码仓库、不内置 APK；公开分发镜像时需单独落实所有包的许可和源码提供义务。

## Ubuntu 内的 npm 兼容工具

用户显式安装 Node.js 工具包时，APT 安装 Node.js/npm、curl 与 CA 证书，然后下载官方 npm **10.9.4**，来源 https://registry.npmjs.org/npm/-/npm-10.9.4.tgz ，npm/cli 标签 `v10.9.4`，发布元数据 gitHead `d1df352bb709485616188ee084991c628ea8630e`；SHA-256 `4bfba8a0c823024d1926ec9d97a37a00eb60fd2adf44b3d34a686fc32e8f51e4`。Artistic-2.0，包内 LICENSE 及 bundled dependencies 自有版权/许可原样保留。仅在设备运行环境下载，不内置 APK、不复制到自有源码。

此版本使用 cacache 19，绕过当前 PRoot 与系统 npm 9/cacache 17 缓存硬链接的兼容失败；不是声称最新 npm。官方 engines 为 `^18.17.0 || >=20.5.0`，当前 Ubuntu Node 18.19.1 真机完成安装/构建验证。安装到 `/usr/local/lib/zakocoder/npm-10.9.4`，npm/npx 启动链接放 `/usr/local/bin`；保留 `/usr/bin/npm`，不覆盖用户自定义启动文件。摘要错误、下载失败或自定义启动器冲突会终止安装，不使用未经校验制品。运行环境范围见 [运行环境](docs/RUNTIME.md)。

## Ubuntu 内的 Android 构建工具

仅在用户显式安装工具包、同意 Android SDK 许可后下载到设备 Ubuntu，不内置 SDK 或额外原生二进制进 APK。安装脚本为本项目实现，没有执行上游在线安装脚本，没有复制第三方产品模块。

- Google Android SDK Command-line Tools **19.0**：`https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip`，SHA-256 `7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee`；对照官方 `repository2-3.xml` 的 SHA-1 `5fdcc763663eefb86a5b8879697aa6088b041e70` 核验。SDK manager 获取 Platform 36、Build Tools 36.0.0 并校验 Google 元数据。适用 Android SDK License Agreement，安装前可打开 `https://developer.android.com/studio/terms` 阅读；原包 NOTICE、各组件声明、SDK/licenses 保留，不宣称 SDK 整体属于 Apache-2.0 或可任意再分发。
- Gradle **8.13**：官方 `https://services.gradle.org/distributions/gradle-8.13-bin.zip`，SHA-256 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`；Apache-2.0 与分发包中各依赖许可证原样保留。
- ARM64 原生工具由 `https://github.com/Commit451/android-arm-build-tools` 的 **platform-tools-36.0.0** release 提供，标签源码提交 `58f06a2e84ae5ed534b2f3b0cecaa281b801c51a`，下载前缀 `https://github.com/Commit451/android-arm-build-tools/releases/download/platform-tools-36.0.0/`。上游构建脚本／适配为 MIT，原始许可随 APK `assets/licenses/Android-arm-build-tools-MIT.txt` 保留；AAPT2/AIDL 等 AOSP 组件及所含依赖各遵循自己的 Apache-2.0、BSD、zlib 等许可，MIT 不替代这些上游许可。上游 release 声明基于 AOSP `platform-tools-36.0.0`，其标签中的默认 config 仍为 35.0.1，不能仅凭标签视为完整可复现输入证明。本项目未修改这些二进制，没有将其作为自编译制品或正式发行能力。
- Ubuntu JDK 17／C++ 运行库来自签名 APT 仓库，许可与源码信息保留于 guest `/usr/share/doc`；不会以本项目许可证替换。

| ARM64 文件 | SHA-256 |
| --- | --- |
| aapt2 | `7512ff7e381bea6fd310b6f6e347422c8fda21e07c6e3f0162742e95eb9d7f98` |
| aidl | `8c97356b8bba8f7aad44cfd408e3ee24c66a1258244b5d08af1c9249f50dd659` |
| zipalign | `e8856fb24b10095eb6e940c577ce96d89ddbfc45aa0f7eeaef1597ef68f11a12` |
| split-select | `fb7f0c3c87dbd4243d7e1277ba64ded2399389244058b6a4c969cc615360766c` |

## 可选 Flutter 开发工具包

用户显式安装后在设备 Ubuntu 中下载，不随 ZakoCoder APK 分发 Flutter SDK、Dart SDK、引擎、QEMU 或 guest 运行库：

- Flutter **3.47.9**：官方仓库 `https://github.com/flutter/flutter`，固定提交 `c197ca5199e9ee5661e865596c9c07294dfab51c`。安装以 Git tag 获取并核对完整 HEAD；引擎版本固定 `89c39edfdfae2949f6806e4a315c0c08522238b7`。Flutter 自有代码采用 BSD-3-Clause，下载后的原始 `LICENSE`、版权及附属组件声明保留。
- Dart SDK **3.13.7** 与匹配引擎由上述固定 Flutter SDK 的官方下载逻辑通过 HTTPS 获取；对应 Dart 源码提交由同一 Flutter 源码 `DEPS` 固定为 `416e33b0ffaef062a60ec966bb8c82ed2b83f128`，来源 `https://github.com/dart-lang/sdk`。Dart 自有代码 BSD-3-Clause，SDK／引擎各自附属库按原分发许可证保留。没有把 Flutter 或引擎整体宣称为只有一种许可；官方制品不宣称为本项目自编译。
- 独立 QEMU **9.2.1+ds-1ubuntu5.2**：Ubuntu ARM64 `qemu-user` 包，来源 `https://ports.ubuntu.com/ubuntu-ports/pool/universe/q/qemu/qemu-user_9.2.1+ds-1ubuntu5.2_arm64.deb`，SHA-256 `bb3ff9cb31b508bf6ce1b3f6bb4afe2b9a4d20a625a5d102ea3d78d753e5d4d0`。只提取未修改的静态 `qemu-x86_64` 到 Flutter SDK 的 `.zakocoder/qemu/`，不安装该发行版的系统包、不升级 Ubuntu 或替换系统 QEMU。二进制 SHA-256 为 `90458b484b67436a47c480fbfaad909cda316020dfb514e3ee40726521605d38`。
- QEMU 对应源码及 Ubuntu 补丁：`https://archive.ubuntu.com/ubuntu/pool/main/q/qemu/qemu_9.2.1+ds-1ubuntu5.2.dsc`，上游源码 `qemu_9.2.1+ds.orig.tar.xz` 的 SHA-256 为 `3d7e2940fee4ebc21ed1a39667050226aa2292f885115ebdd12c8e390071bdc5`，打包补丁 `qemu_9.2.1+ds-1ubuntu5.2.debian.tar.xz` 的 SHA-256 为 `c41a0537879a711ae0cc0224160ca33c650231a266bae68da51ed8e60868af66`，三者均在该目录。QEMU 主要采用 GPL-2.0-or-later，组件采用各自许可；包内完整 `copyright` 原样保留在二进制旁，`SOURCE.txt` 记录下载、对应源码与摘要，不将独立下载当作免除许可义务。
- `libc6-amd64-cross` 来自设备配置的 Ubuntu 签名 APT 仓库，版本随仓库更新，不宣称固定可复现；glibc 主要采用 LGPL-2.1-or-later。准确包版本、版权、例外与来源以 guest `dpkg-query`、`/usr/share/doc` 和 APT source 元数据为准。QEMU 和 cross glibc 仅供 guest 中 Android arm64 AOT 工具运行，未链接到 ZakoCoder APK；如另行分发这些包、环境镜像或修改后的组合，应同时核对对应源码、通知及再分发义务。
- 安装脚本、Android arm64 profile／release 的 `gen_snapshot` QEMU 启动包装、Dart 基础词法规则和 Flutter 应用模板为 ZakoCoder 自有实现；不修改或替换上游 x86_64 二进制，保留原始工具与许可。平台目录由用户执行 Flutter 自身的 `create` 命令生成，平台代码适用其上游声明。

安装方式、额外 Android SDK 许可、实际验收和目标限制见 [Flutter 开发](docs/FLUTTER.md)。固定框架与引擎提交不等于锁定整个 pub／APT／Maven 依赖树；安装与工程构建仍需网络。

## Maven、构建与其他依赖

精确版本统一见 `gradle/libs.versions.toml`。

- Kotlin、Coroutines、AndroidX、Compose：Apache-2.0 等各自上游声明，通过 Maven 仓库解析。
- Apache Commons Compress（Apache-2.0）：XZ/TAR 安全解包；XZ for Java：上游公开领域声明及附属许可，保留依赖内声明。
- JUnit 4（EPL-1.0）和 AndroidX Test：仅测试依赖，不作为用户项目预置数据。
- Gradle Wrapper：标准启动脚本和 jar，原始版权声明保留；仓库构建固定 Gradle 9.8.2，官方分发 `https://services.gradle.org/distributions/gradle-9.8.2-bin.zip`，SHA-256 `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607`。上文手机开发工具包的 Gradle 8.13 是独立安装版本，未随宿主构建升级。

新增或更新第三方材料需记录组件、精确版本/提交、官方来源、许可、修改、摘要和分发方式；不得删除声明。对二进制对应源码不明确的组件，不能将“未提交 Git”当作无需许可审查的理由。

## AI 网络与测试

HTTP/SSE 传输使用官方 Maven Central `com.squareup.okhttp3:okhttp:5.5.1`。Gradle 模块元数据在 Android 上选择 `com.squareup.okhttp3:okhttp-android:5.5.1`（AAR）；来源 https://github.com/square/okhttp/tree/parent-5.5.1 ，标签对应提交 `be5151ac3b63d3db405b9dbb62632c36cc6a657e`，Apache-2.0，未修改上游代码。传递依赖 `com.squareup.okio:okio-jvm:3.18.1`，来源 https://github.com/square/okio/tree/parent-3.18.1 ，Apache-2.0。APK 随附 `assets/licenses/OkHttp-Apache-2.0.txt`、`Okio-Apache-2.0.txt`，均与上述版本上游 `LICENSE.txt` 一致。测试专用 `com.squareup.okhttp3:mockwebserver:5.5.1`、`okhttp-tls:5.5.1` 仅进入 androidTest；保留上游仍发布的 `okhttp3.mockwebserver` 兼容 API，不把测试 CA、服务或配置打入生产应用。

Android AAR 的 `assets/PublicSuffixDatabase.list` 为上游生成的 Public Suffix List 数据，适用 MPL-2.0，保持原样打包。其字节与固定标签的 [Android 数据文件](https://github.com/square/okhttp/blob/parent-5.5.1/okhttp/src/androidMain/assets/PublicSuffixDatabase.list) 完全一致；对应可修改列表及上游通知位于同标签的 [public_suffix_list.dat](https://github.com/square/okhttp/blob/parent-5.5.1/okhttp/src/jvmTest/resources/okhttp3/internal/publicsuffix/public_suffix_list.dat) 和 [NOTICE](https://github.com/square/okhttp/blob/parent-5.5.1/okhttp/src/jvmTest/resources/okhttp3/internal/publicsuffix/NOTICE)。APK 保留通知 `assets/licenses/OkHttp-PublicSuffix-NOTICE.txt`，并随附完整 `OkHttp-PublicSuffix-MPL-2.0.txt`（来源 https://www.mozilla.org/MPL/2.0/ ）。制品中的其他声明保留。

| Maven 制品 / 上游数据 | SHA-256 |
| --- | --- |
| okhttp-android:5.5.1 的 okhttp.aar | `ce00fd7751cf7e4f28ba73a0287b3cc0c62b8b83f038e428592eeef189513ee1` |
| okio-jvm-3.18.1.jar | `b97b640557a650d15411f2be29aef558c4e422a83653cb18ba494311dbed18be` |
| AAR 内 assets/PublicSuffixDatabase.list | `c75ab827afd7ffff59a51b14729c25dbfc3b45237eb40ccf02bec7b7d44304a4` |
| 固定标签中的 public_suffix_list.dat | `986879fc41f0a5cc65f5e7d88fa68f6f0d9c366bb620418b8b8045f61a134077` |

Chat Completions 请求、SSE delta 和工具关联按公开协议独立实现，未复制 Operit 业务代码，未引入其 Agent、脚本引擎或插件模块。协议参考 OpenAI 官方 SDK schema： https://github.com/openai/openai-python/blob/main/src/openai/types/chat/chat_completion_chunk.py 。本项目没有嵌入该 SDK 源码或运行依赖。

## Markdown

CommonMark Java `org.commonmark:commonmark:0.24.0` 与 `commonmark-ext-gfm-tables:0.24.0`，官方来源 https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.24.0 ，标签提交 `9a395abec74f96b48cfd45f681507b72dc31d9c4`，未修改上游。BSD-2-Clause，原始版权及完整许可随 APK `assets/licenses/CommonMark-BSD-2-Clause.txt` 提供，Maven JAR 上游声明保留。只解析 AST，Compose 渲染由本项目实现，不引入 WebView 或网络图片加载。

| Markdown 制品 | SHA-256 |
| --- | --- |
| commonmark-0.24.0.jar | `679338e0b7fc15c02d275d598654b01a149893bc28a87992e90123c8d06af25b` |
| commonmark-ext-gfm-tables-0.24.0.jar | `b54dc332f931e6d07c2766144c087b08f3693677e368151a67020b4e95bb4b99` |

## 内置手机 Skill 指令

APK `assets/skills/` 的 13 个中文指令文件是 ZakoCoder 自有实现，版本 `1.0.0-zakocoder`，按本项目 Apache-2.0 许可提供。它们按用户选定的任务用途重新编写，不分发电脑端同名 Skill 原文、第三方参考 checklist、脚本、hook 或插件。未引入新第三方代码、库或云服务。参考材料标识、精确 SHA-256 及实际 APK 输入摘要见 [来源清单](docs/builtin-skills-provenance.json)，行为及权限边界见 [AI 任务工作流](docs/AI_WORKFLOW.md)。同名表示用途，不宣称是上游官方手机版或完整移植。

## 可选 ARM64 Android NDK

手机 Ubuntu 内可选下载 HomuHomu833/android-ndk-custom 的 GNU/Linux ARM64 r28c 制品，不打入 APK：

- 仓库：<https://github.com/HomuHomu833/android-ndk-custom>；发布标签 `r28`，当前标签指向 `3bd061d0fa8b0eb37845503b840045fdab757bf2`。标签与发布资产可被上游更新，因此安装以固定摘要为准，不声称该标签可复现对应资产。
- 文件：<https://github.com/HomuHomu833/android-ndk-custom/releases/download/r28/android-ndk-r28c-aarch64-linux-gnu.tar.xz>，127,882,848 字节；SHA-256 `3e961b2dac764b0e326b271307e7059c2ef1ce3dac5223e75302400727cc046b`。
- `source.properties` 声明 NDK `28.2.13676358`。制品中的 Clang 19 自述源自 HomuHomu833/llvm-custom 提交 `dbdd29d3ef3c16cda5c80bc6655f2f8f7f076c8a`；这是社区修改工具链，不是 Google 官方 ARM64 Linux NDK，也不是本项目自编译产物。
- 上游封装工程 MIT 声明保留在 `app/src/main/assets/licenses/Android-ndk-custom-MIT.txt`。该 MIT 不替代 NDK、LLVM、libc++、sysroot 等组件自身的许可证；下载目录中的 `NOTICE`、LLVM `NOTICE` 和其他声明随制品完整保留。APT 提供的 CMake、Ninja、Python 和运行库使用 Ubuntu 包自身的版权材料。
- 上游包含 ARM64 host 识别及兼容目录链接，项目未复制其产品代码。当前验收范围为 Ubuntu ARM64 中的 CMake/JNI Debug 构建；不据此宣称任意 NDK 项目或 `ndk-build` 已验证。Release 分发材料与设备覆盖审查仍然有效。

## 图片方向元数据

图片导入使用 Google Maven 的 `androidx.exifinterface:exifinterface:1.4.2` 读取 EXIF 方向，随后由本项目重新编码 JPEG，不保留来源元数据。官方来源 https://developer.android.com/jetpack/androidx/releases/exifinterface#1.4.2 ，源码对应同版本 Google Maven sources JAR；未修改上游代码，Apache-2.0。AAR SHA-256：`6bb2b68682a2023c55f35f941444ffea0157947b061e4fd7606ba935e2e77425`。通过 Gradle 解析并随 APK 分发；AndroidX 原有许可适用，不替换版权声明。

## SSH 客户端

SSH 能力包使用 Maven Central `com.github.mwiede:jsch:2.28.8`，官方来源 https://github.com/mwiede/jsch ，固定标签 `jsch-2.28.8`。原始 BSD 许可证保存在 APK `assets/licenses/JSch-BSD.txt`；未修改或复制上游实现。通过原生 Java SSH 协议连接，不依赖 Operit、QuickJS、宿主 Shell 或 Ubuntu SSH 客户端。主机指纹与认证凭据由原生配置界面管理，库的原始异常不会进入模型工具结果。
