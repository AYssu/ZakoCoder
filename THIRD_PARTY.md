# 第三方代码、制品与许可

ZakoCoder 自有代码使用根目录 Apache-2.0。第三方许可证不因本项目许可证改变，`example/` 不属于构建或发行内容。

## 可复用终端基础库

来源：https://github.com/termux/termux-app ，固定提交 `8629e632fcb95da272221be327db653fb24befe9`。仅包含 `terminal-emulator/src` 和 `terminal-view/src`，不包含 Termux 应用模块或 Operit 业务界面。

上游 LICENSE.md 明确将这两个库列为 Apache-2.0 例外。各目录的 `UPSTREAM-LICENSE.md`、`LICENSE-APACHE-2.0` 和源码版权头保留原声明。改动记录见 [third_party/README.md](third_party/README.md)：本仓库 Gradle/NDK 配置，以及使用 ParcelFileDescriptor 公共 API 管理 PTY 描述符，避免 Android 隐藏字段反射。

## 终端字体

JetBrains Mono Regular **2.304**，官方来源 https://github.com/JetBrains/JetBrainsMono ，标签 `v2.304` 对应固定提交 `cd5227bd1f61dff3bbd6c814ceaf7ffd95e947d9`，上游路径 `fonts/ttf/JetBrainsMono-Regular.ttf`。原始字体未修改，273,900 字节，SHA-256 `a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f`。

SIL Open Font License 1.1；原始版权与完整许可证保存在 APK 的 `assets/licenses/JetBrainsMono-OFL.txt`，摘要 `30f0c136e3c88e422d0791acd97238870f9054a9729bc34cf2ff0d4ed8cac4ad`。字体本体在 `assets/fonts/JetBrainsMono-Regular.ttf`，随 APK 离线提供，不在运行时下载。终端按单元格绘制，不启用编程连字；不依赖 Nerd Font 图标。关于页列出字体与许可。

## Sora 原生代码编辑器

官方来源 https://github.com/Rosemoe/sora-editor ，使用 Maven Central 的 `io.github.Rosemoe.sora-editor:editor:0.23.6` 和 `language-textmate:0.23.6`，源码标签 `0.23.6` 对应提交 `b1777d0d465b42515739ffe529b4ec1634ac2175`。不修改上游库，不引用 example。App 通过 AndroidView 适配，词法规则及主题为本项目自写。

| 制品 | SHA-256 |
| --- | --- |
| editor-0.23.6.aar | `d6d87ac499b64d4f1e89e41f355d343dc44674f38e37181f4bd58a5cb71a5360` |
| editor-0.23.6-sources.jar | `b3573309e8e3e4d1006c6a0da7aa94fa0712e87d1d256650a5e39349850c78ce` |
| language-textmate-0.23.6.aar | `909576591348f999b318ef601568a208550ee1c0cae375d70626f70f2b7c63ba` |
| language-textmate-0.23.6-sources.jar | `ca06b725576c3de059d0625d7971803c08046cd4907b3235d88b82baa2d4311b` |

Sora 声明 LGPL-2.1（源码头允许后续版本），原始许可证保存在 APK `assets/licenses/SoraEditor-LGPL-2.1.txt`。language-textmate 内含 TM4E，源文件保留 EPL-2.0 头，完整 EPL 保存在 `assets/licenses/TM4E-EPL-2.0.txt`。对应源码可从以上固定提交及 Maven 同版本 `-sources.jar` 获取，原版权头未改变。公开发行需随本项目构建输入提供依赖对应源码和可重新构建/链接的方法，不将本项目 Apache-2.0 当作第三方代码许可；当前 Release 仍因 PRoot 验证制品阻止。

TextMate 传递依赖包括 Gson 2.13.1（Apache-2.0）、JCodings 1.0.63、Joni 2.2.6（MIT）、SnakeYAML Engine 2.9（Apache-2.0）、JDT annotations 2.3.100（EPL-2.0）。来源及许可随 Maven POM/JAR 保留；不接入语言服务器或远端补全。新引入的 AndroidX/Kotlin 依赖继续遵循各自声明。

## PRoot 验证制品：不用于公开发行

当前调试构建使用固定验证制品，正式发行前需自行编译替换。来源 https://github.com/AAswordman/OperitTerminalCore ，提交 `e4442bc6a047b6165bf59103721ad143149c620d`。固定 ARM64 PRoot、loader 及 SHA-256 见 [runtime/artifacts.json](runtime/artifacts.json)。构建脚本从该固定提交下载到忽略的 `runtime-artifacts/`，每次构建校验，不读取本地参考源码。

来源仓库声明 LGPL-3.0；PRoot 上游为 GPL-2.0。该声明不能替代每个原生制品准确的对应源码、补丁、工具链与构建说明，当前尚未确认完整对应关系。因此仅用于本地调试验证：Release 任务直接阻止，CI 不上传 APK。正式发行必须固定并公开自编译输入和构建流程、履行实际组件许可证及对应源码义务，重跑真机验证；不能仅修改包名或改一个许可证标签即发行。来源许可证副本保留在 APK assets/licenses。

## Ubuntu 基础系统

来源：https://github.com/termux/proot-distro/releases/tag/v4.18.0 ，资源 `ubuntu-noble-aarch64-pd-v4.18.0.tar.xz`；确切大小、摘要、解包前缀见制品清单。不是本项目编译的操作系统。Ubuntu 24.04 各软件包遵循各自许可，在环境 `/usr/share/doc/<package>/copyright` 中保留；APT 从用户选定的 Ubuntu 官方或清华镜像获取签名软件包。环境包不提交源码仓库、不内置 APK；公开分发镜像时需单独落实所有包的许可和源码提供义务。

## Ubuntu 内的 npm 兼容工具

用户显式安装 Node.js 工具包时，APT 安装 Node.js/npm、curl 与 CA 证书，然后下载官方 npm **10.9.4**，来源 https://registry.npmjs.org/npm/-/npm-10.9.4.tgz ，npm/cli 标签 `v10.9.4`，发布元数据 gitHead `d1df352bb709485616188ee084991c628ea8630e`；SHA-256 `4bfba8a0c823024d1926ec9d97a37a00eb60fd2adf44b3d34a686fc32e8f51e4`。Artistic-2.0，包内 LICENSE 及 bundled dependencies 自有版权/许可原样保留。仅在设备运行环境下载，不内置 APK、不复制到自有源码。

此版本使用 cacache 19，绕过当前 PRoot 与系统 npm 9/cacache 17 缓存硬链接的兼容失败；不是声称最新 npm。官方 engines 为 `^18.17.0 || >=20.5.0`，当前 Ubuntu Node 18.19.1 真机完成安装/构建验证。安装到 `/usr/local/lib/zakocoder/npm-10.9.4`，npm/npx 启动链接放 `/usr/local/bin`；保留 `/usr/bin/npm`，不覆盖用户自定义启动文件。摘要错误、下载失败或自定义启动器冲突会终止安装，不使用未经校验制品。运行环境范围见 [运行环境](docs/RUNTIME.md)。

## Maven、构建与其他依赖

精确版本统一见 `gradle/libs.versions.toml`。

- Kotlin、Coroutines、AndroidX、Compose：Apache-2.0 等各自上游声明，通过 Maven 仓库解析。
- Apache Commons Compress（Apache-2.0）：XZ/TAR 安全解包；XZ for Java：上游公开领域声明及附属许可，保留依赖内声明。
- JUnit 4（EPL-1.0）和 AndroidX Test：仅测试依赖，不作为用户项目预置数据。
- Gradle Wrapper：标准启动脚本和 jar，原始版权声明保留，Gradle 8.13 分发配置 SHA-256。

新增或更新第三方材料需记录组件、精确版本/提交、官方来源、许可、修改、摘要和分发方式；不得删除声明。对二进制对应源码不明确的组件，不能将“未提交 Git”当作无需许可审查的理由。

## AI 网络与测试

HTTP/SSE 传输使用官方 Maven Central `com.squareup.okhttp3:okhttp:4.12.0`，来源 https://github.com/square/okhttp/tree/parent-4.12.0 ，Apache-2.0，未修改上游代码。传递依赖 `com.squareup.okio:okio-jvm:3.6.0`，来源 https://github.com/square/okio/tree/parent-3.6.0 ，Apache-2.0。APK 随附 `assets/licenses/OkHttp-Apache-2.0.txt`、`Okio-Apache-2.0.txt` 和上游 PublicSuffix NOTICE，JAR 内其他声明保留。测试专用 `mockwebserver:4.12.0`、`okhttp-tls:4.12.0` 仅进入 androidTest，不把测试 CA、服务或配置打入生产应用。

| Maven 制品 | SHA-256 |
| --- | --- |
| okhttp-4.12.0.jar | `b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0` |
| okio-jvm-3.6.0.jar | `67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412` |

Chat Completions 请求、SSE delta 和工具关联按公开协议独立实现，未复制 Operit 业务代码，未引入其 Agent、脚本引擎或插件模块。协议参考 OpenAI 官方 SDK schema： https://github.com/openai/openai-python/blob/main/src/openai/types/chat/chat_completion_chunk.py 。本项目没有嵌入该 SDK 源码或运行依赖。

## Markdown

CommonMark Java `org.commonmark:commonmark:0.24.0` 与 `commonmark-ext-gfm-tables:0.24.0`，官方来源 https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.24.0 ，标签提交 `9a395abec74f96b48cfd45f681507b72dc31d9c4`，未修改上游。BSD-2-Clause，原始版权及完整许可随 APK `assets/licenses/CommonMark-BSD-2-Clause.txt` 提供，Maven JAR 上游声明保留。只解析 AST，Compose 渲染由本项目实现，不引入 WebView 或网络图片加载。

| Markdown 制品 | SHA-256 |
| --- | --- |
| commonmark-0.24.0.jar | `679338e0b7fc15c02d275d598654b01a149893bc28a87992e90123c8d06af25b` |
| commonmark-ext-gfm-tables-0.24.0.jar | `b54dc332f931e6d07c2766144c087b08f3693677e368151a67020b4e95bb4b99` |

## 内置手机 Skill 指令

APK `assets/skills/` 的 13 个中文指令文件是 ZakoCoder 自有实现，版本 `1.0.0-zakocoder`，按本项目 Apache-2.0 许可提供。它们按用户选定的任务用途重新编写，不分发电脑端同名 Skill 原文、第三方参考 checklist、脚本、hook 或插件。未引入新第三方代码、库或云服务。参考材料标识、精确 SHA-256 及实际 APK 输入摘要见 [来源清单](docs/builtin-skills-provenance.json)，行为及权限边界见 [AI 任务工作流](docs/AI_WORKFLOW.md)。同名表示用途，不宣称是上游官方手机版或完整移植。
