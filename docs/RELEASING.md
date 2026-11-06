# 发布流程

仓库提供两个手动入口：**Build Debug APK** 保留开发测试包下载；**Publish Release** 用于维护者确认后的签名正式版或预发布。发布入口使用不可调试的 Release 构建及 R8，Debug 入口保留可调试构建，默认沿用仓库现有 CI Debug 证书，可选配置自己的签名；默认方式不是独立的长期正式发布密钥。无后缀的正式版设为 Latest，Beta／RC 必须标记为 **Pre-release**，不设为 Latest。常规提交和 PR 不自动发布。

本次正式版为 `0.1.0`（versionCode `1005`），候选版为 `0.1.0-rc.1`（`1004`）；以实际发布结果为准。维护者已反馈完成 RC 验证并授权转正式版；未提供设备、场景和日志明细的部分不补写为自动化或专项验证通过。运行时源码与许可证、Release 构建、最终签名和设备检查分别记录，已有兼容性限制继续保留。

## 首个正式版的发布决定

本次不新增产品功能，正式发布后优先处理问题修复、兼容适配、性能和交互优化。维护者选择继续沿用现有 CI Debug 证书，以保持现有同签名安装的升级身份；这不是建立了独立长期生产密钥。后续更换签名时必须另行评估覆盖升级并公开迁移方案，不以卸载或清除用户数据作为默认做法。

正式版仍按同一工作流执行仓库检查、Release + R8 构建、签名、设备 QA、源码和附件一致性检查。维护者的 RC 验证反馈不代替当前构建的实际 CI 结果，也不扩大已公开的设备和工程支持范围。

## RC 验收阶段

RC 是准备转正式版的候选版本，必须标记 Pre-release，不设为 Latest。本阶段原则上不新增功能，主要进行问题修复、设备和工程适配、性能与交互优化，以及最终签名包的安装、升级和数据保留验收。有变更则递增 RC 序号与 versionCode，不覆盖既有标签和附件；验收通过后再决定正式版，不按时间自动转正。

发布入口接受正式版 `MAJOR.MINOR.PATCH`、`MAJOR.MINOR.PATCH-beta.N` 与 `MAJOR.MINOR.PATCH-rc.N`；沿用既有 `betaRelease/betaVersion/betaVersionCode` 构建参数名以保持兼容。根据版本后缀决定正式版或预发布，构建与制品门槛均检查实际版本。RC 使用现有签名选择规则，不自动生成或更换证书；沿用 CI Debug 证书时仍须明确标注，正式发布前明确签名选择与升级限制。

RC 需重点反馈：首次安装和覆盖升级后的项目/会话/草稿保留，环境安装与终端服务，AI 长任务/取消/恢复，文件审阅与恢复，Git 和按需 LSP，以及后台权限和不同厂商适配。缺陷通过 Issues 模板反馈，附版本、机型、复现和脱敏日志，不发送密钥或私人源码。

## 手动发布正式版或预发布版本

1. 选择已完成审查和相关 CI 的提交，核对 [CHANGELOG](../CHANGELOG.md)、[Beta 测试说明](BETA_TESTING.md)及[兼容性结果](ANDROID_COMPATIBILITY_RESULTS.md)。
2. 确认已有 Actions Secret `ZAKOCODER_DEBUG_KEYSTORE_BASE64` 可用。默认沿用它签署发布包，无需新增 Secret；也可完整配置下方可选签名。所选密钥缺失或无效时失败，不临时生成替代密钥。
3. 在 `main` 上打开 **Actions → Publish Release → Run workflow**：`version` 填 `0.1.0`，`version_code` 本次为 `1005`，后续严格递增；`target_ref` 为 `main` 或其祖先的完整提交 SHA。只有来自 `main` 的可信工作流可发布；历史 SHA 还必须保持 `.github/workflows/` 树与当前 `main` 完全一致，才允许自动创建标签。已有标签或 Release 会被拒绝，不覆盖历史附件。
4. 工作流校验仓库、执行 Release 构建及相关检查，先生成未签名 APK，再使用所选密钥签名。QA 通过后创建草稿 Release，上传并逐项下载回读校验 SHA-256，最后按版本后缀发布：正式版标为 Latest，Beta／RC 设为预发布且不标为 Latest。
5. 工作流完成签名 R8 QA 的设备检查和制品一致性校验后，下载实际发布的 APK 与摘要复核。发布页面列出实际通过项、已知限制和未验证项，不能把 Debug 矩阵或 x86 QA 写成公开 ARM64 APK 真机验收。

本流程构建四个独立的单 ABI 安装包，Debug 手动构建入口也使用同一矩阵：

| 版本 | ARM64 真机 / ARM64 模拟器 | x86_64 模拟器 / 设备 |
| --- | --- | --- |
| Lite | `ZakoCoder-<版本>-lite-arm64-v8a.apk` | `ZakoCoder-<版本>-lite-x86_64.apk` |
| Full | `ZakoCoder-<版本>-full-arm64-v8a.apk` | `ZakoCoder-<版本>-full-x86_64.apk` |

Lite 按需下载 Ubuntu、可选安装独立代理内核插件；Full 在主 APK 内集成代理内核服务和对应架构的 Ubuntu 安装包，不内嵌插件 APK。Full 首次启动会初始化 Ubuntu，已有环境不被覆盖；开发工具包和模型服务仍由用户配置。x86_64 发行不等于手机专用 Android SDK / NDK 工具链已适配。

四个 APK 使用相同包名、版本号、versionCode 和证书，允许用户主动覆盖切换；升级不应清除项目、配置或 Ubuntu。应用内更新仅选择当前 Lite / Full 与安装架构相符的包，不自动跨版切换。历史 `ZakoCoder-<版本>-arm64.apk` 仅可供 Lite ARM64 更新使用。公开附件为这四个 APK 和一份覆盖四包的 `SHA256SUMS.txt`，验证记录和源码归档保留在 Actions，发布正文提供固定提交的完整源码与许可证入口。

CI 仍强制从源码重建两种 ABI 的 PRoot / loader 和 Xray 并比对锁定摘要；本地默认复用仓库已验证的原生产物。发布还检查实际 APK 的单 ABI、edition 标记、Ubuntu 文件长度和 SHA-256、内核摘要、无嵌套插件 APK、版本身份及相同签名。Lite / Full 的实际 x86_64 Release 包分别运行设备 UI smoke；同版 ARM64 包需通过代码/资源一致性比较，架构专属资源另按锁验证。这些检查不替代 ARM64 厂商真机和完整工具链验收。

本地可指定 `-PdistributionEdition=lite|full`（默认 Lite）与 `-PcompatibilityAbis=arm64-v8a|x86_64`，保留 `:app:assembleDebug` / `:app:assembleRelease` 任务名。Full 构建首次需要获取校验后的 Ubuntu 包；离线缓存存在时复用。构建参数为 `-PbetaRelease=true -PbetaVersion=0.1.0 -PbetaVersionCode=1005`，未签名输出为后续签名的输入；不在 Gradle 中保存密钥。完整步骤以 [publish-beta.yml](../.github/workflows/publish-beta.yml) 和 `app/build.gradle.kts` 为准。

### 可选代理内核插件

Lite 主 APK 不包含 Xray，Full 已集成，无需安装独立插件。**Publish Release** 成功后自动调用 [Publish Kernel Plugin](../.github/workflows/release-kernel-plugin.yml)，构建 ARM64 与 x86_64 两个单 ABI 插件 Release，并沿用主程序签名配置。工作流下载刚发布的主 APK，校验摘要、签名与版本；只有插件与它同证书、插件 versionCode 与该主 APK 一致、内核 SHA-256 与构建锁一致时才允许发布。

现有版本缺少插件时，可在 `main` 的 **Actions → Publish Kernel Plugin → Run workflow** 单独补发，`main_release_tag` 填已有主程序标签（例如 `v0.1.1`），无需重新发布主 APK。插件源码取该次可信 main 工作流提交，记录在插件 `BUILD.json` 和正文；不会改写主程序版本标签。

应用当前下载地址固定为 `kernel-plugin-v1` 发布页下的 `kernel-plugin-arm64-v8a.apk` 与 `kernel-plugin-x86_64.apk`，按设备架构选择。该页是接口 v1 的更新通道，只有更高 versionCode 才更新附件；重复或降级发布会在上传前被拒绝。它不标为 Latest，不改变主程序 Latest。除 APK 和摘要外，保留构建身份及包含原始许可证的 Go 对应源码包，上传后回读校验；主程序发布正文会加入插件下载入口。

用户可在代理设置中选择在线安装，或手动下载这个 APK 后选择本地安装；两种方式复用包名、签名、版本与兼容校验，最后由系统安装器确认。直连及手动 HTTP／SOCKS5 不依赖插件。MIUI 等系统可能还需允许插件自启动／关联启动。Debug 测试插件与正式主程序的证书可能不同，不能混用。

### 发布签名

默认只需要已有的 `ZAKOCODER_DEBUG_KEYSTORE_BASE64`，内容为 CI 专用 Debug keystore 的 Base64；使用 `androiddebugkey` 别名和 Android 默认 Debug 口令。发布包的构建类型仍是 **Release + R8，`debuggable=false`**。默认复用 Debug 证书时，公开构建记录必须明确说明，不将其描述为正式长期签名。

可选签名有两个入口：

- **本地**：预留忽略路径 `.local/signing/release.jks`，也可用 `ZAKOCODER_RELEASE_KEYSTORE` 指定路径；由 `scripts/sign_beta_apk.py` 的 `--apk`、`--output`、`--apksigner`、`--zipalign` 传入文件与工具。密钥不存在时使用默认 Debug 签名；指定密钥存在时，必须同时提供 `ZAKOCODER_RELEASE_STORE_PASSWORD`、`ZAKOCODER_RELEASE_KEY_ALIAS`、`ZAKOCODER_RELEASE_KEY_PASSWORD`。配置不全或签名失败直接报错，不静默回退。
- **CI**：可选完整配置 `ZAKOCODER_RELEASE_KEYSTORE_BASE64`、`ZAKOCODER_RELEASE_STORE_PASSWORD`、`ZAKOCODER_RELEASE_KEY_ALIAS`、`ZAKOCODER_RELEASE_KEY_PASSWORD` 四个 Secrets，整组存在时优先使用；均未配置则沿用已有 Debug Secret，部分配置必须报错，避免意外换签名。

CI 签名材料只在 Secrets 和临时 runner 中使用，不写入聊天、Issue、PR、源码、缓存或制品。工作流校验最终证书，公开证书 SHA-256，并在结束时删除 keystore。CI Debug 密钥仍需保留仓库外备份；更换 Secret 会改变后续签名，Secret 本身不是备份。

默认模式下，使用同一仓库 CI 签名、同一 applicationId 且 versionCode 更高的发布包可以覆盖对应 Debug 安装；本地 Android Studio 或其他机器的 Debug 密钥可能不同，必须先核对证书。签名不一致时先确认源码和数据备份，不默认卸载或清数据。

后续稳定版若改用正式发布密钥，必须先说明覆盖升级限制并制定迁移方案；不能承诺当前 Debug 证书签名的安装可直接升级。维护者已明确选择本次正式版继续沿用现有签名，不将正式版名称解释为已更换长期密钥。

### 发布附件与诊断材料

每个主应用版本仅上传五个自定义 Release 附件：

- `ZakoCoder-<version>-lite-arm64-v8a.apk`、`ZakoCoder-<version>-lite-x86_64.apk`：轻量版。
- `ZakoCoder-<version>-full-arm64-v8a.apk`、`ZakoCoder-<version>-full-x86_64.apk`：内置 Ubuntu 和内核的完整包。
- `SHA256SUMS.txt`：覆盖以上四个 APK 的 SHA-256 校验摘要。

发布正文面向用户说明安装、升级及已知限制；提交、versionCode、证书和构建验证信息放在可折叠的构建详情中。自动版本检查使用隐藏的元数据注释，不额外上传 `BUILD.txt`／`BUILD.json`。

发布页须清楚指出本次不可变提交与版本标签，并链接其 GitHub **Source code (zip / tar.gz)** 完整归档。归档中的 `native-runtime/` 已包含固定原始 `sources/`、`patches/`、构建脚本、工具链／产物锁、交叉编译输入及 `licenses/`，用于取得与 APK 对应的完整运行时源码和重建材料。发布前核对归档确实包含这些内容；不能用可变上游分支或限期 Actions Artifact 代替长期可得的对应源码。许可证与版权声明保留在 APK 和源码中，详见[第三方材料](../THIRD_PARTY.md)。

内部候选仍生成完整源码包、许可证、构建记录和验证材料，用于分发前校验；它们留在 Actions，不作为用户 Release 附件。候选 Artifact `beta-candidate-<runid>-<attempt>` 保留 7 天，解压目录为 `candidate/`，其中四个最终签名 APK 同时用于后续校验；不再另造多架构 QA 包。发布前另存 `beta-release-records-<runid>-<attempt>` 14 天，包含 BUILD 记录、独立源码包、运行时／Release 验证及许可归档；独立冒烟证据同样保留 14 天。内部完整摘要清单不作为用户版 `SHA256SUMS.txt` 上传。

R8 `mapping.txt` 单独存入 R8 诊断 Actions Artifact，保留 90 天，不作为 Release 附件。**公开仓库的 Actions Artifact 不是私密存储：有仓库读取权限且登录的用户仍可能下载。**所有制品均不得含密钥、凭据或用户数据，维护者应在过期前另行保管诊断材料。

## 历史 Beta 本地验收记录

以下保留首个 Beta 发布前的本地验收记录与当时待执行项，不代表本次正式版的当前检查状态；当前结果以对应 Actions 运行与发布记录为准。最终资源清理后的本地重建及 API 35 R8 QA 已通过；干净 checkout 已完成 Release 构建，全部 DEX、资源和 assets 与已验收候选一致；原生 PRoot／loader 的独立摘要检查通过。当时 GitHub Actions 尚未实际运行。每次发布需保留对应提交与制品证据。

- [x] 最终本地 Release R8 编译、Release Lint、应用 280 项、终端 145 项及脚本 58 项测试、AndroidTest 编译通过。
- [x] 最终 API 35 真实 R8 QA 完成项目创建、编辑保存、重启及配置持久化，4 张日夜截图通过。
- [x] 干净 checkout 的仓库检查、58 项脚本测试及 Release 构建通过；无 `example/` 或本地资料依赖。
- [ ] GitHub Actions 首次端到端运行通过（PR 合并后手动触发）。
- [x] 固定运行时源码、补丁、工具链和 ELF／摘要检查及 14 项原生回归通过，内部对应源码包和许可证已完整收集；发布仍须核对不可变源码归档可取得。
- [x] 最终本地候选签名 APK 的 applicationId、版本、versionCode、ABI、证书和 SHA-256 已核验；调试开关、测试 CA、测试 Provider 不进入发布包。CI 会重新构建并使用其实际密钥重复验收。
- [x] API 35 x86_64 设备安装**与公开包采用同一证书签名的 R8 QA 包**并通过本地界面冒烟；QA 与公开 ARM64 APK 的全部 DEX、resources.arsc、res 与 assets 字节一致，共有原生制品、版本身份与签名证书一致；架构差异单独记录。
- [x] 首个 Beta 没有可比旧 Beta，本次覆盖升级明确记录为未验证；未引用不同签名的 Debug 升级替代。
- [x] 报告清楚区分已有 Debug Ubuntu／PTY／HTTP 基线与本次 Release QA，未运行的链路不标记通过。
- [ ] 两个用户附件、不可变源码归档入口、单独的 R8 诊断 Artifact、安装说明及已知限制已由维护者确认；诊断 Artifact 不作为秘密存储。

已完成的 Debug 系统基线见[兼容性结果](ANDROID_COMPATIBILITY_RESULTS.md)：Android 11–17 AOSP x86_64，含 Android 12L；不代表最终 Release APK、所有 ARM64 厂商设备或真实 16 KiB 页设备均通过。Android 8 的完整开发环境与模型网络尚未通过验收，不列入首批 Beta 验收范围。

首批 Beta 允许在上述检查通过后带明确限制分发：**最终公开 ARM64 Release APK 的真机安装、同签名升级、Ubuntu／终端／HTTP 冒烟仍未完成**。既有真机安装需先核对证书，不能为了验收直接覆盖或卸载用户包。API 35 x86 QA 与同代码／同签名校验能验证 R8 风险的一部分，不能替代这些 ARM64 证据。

上述 ARM64 专项证据的历史缺口不因版本名称改变而自动关闭。本次维护者反馈已完成 RC 验证并授权正式发布，但未提供的细节仍不声明为专项检查通过；设备范围和遗留限制继续如实公开。

## 本地 Release QA 界面证据

Android 15 / API 35、官方 x86_64 模拟器，使用最终 ARM64 + x86_64 Release QA APK；公开 ARM64 候选与其代码、资源、共有原生制品及签名一致。图片只含测试脚本新建的临时项目、占位配置和无密钥的 `example.invalid` 地址。它们不是预装项目或真实模型交互。

| 页面 | 日间 | 夜间 |
| --- | --- | --- |
| 编辑与保存 | [截图](assets/beta-release/editor-day.png) | [截图](assets/beta-release/editor-night.png) |
| 模型配置持久化 | [截图](assets/beta-release/models-day.png) | [截图](assets/beta-release/models-night.png) |

CI 的每次候选会生成自己的截图及绑定制品摘要的报告；本地证据不替代上游工作流成功记录，也不代表 ARM64 Release 已通过真机完整验收。

## 手动打包测试 APK

**Actions → Build Debug APK → Run workflow** 保留原入口。成功后在 Artifacts 下载 Lite／Full × ARM64／x86_64 四个 Debug APK、`SHA256SUMS.txt`、`BUILD.txt`、自编译运行时及对应源码包；保留 14 天，需登录 GitHub。在解压目录执行 `sha256sum -c SHA256SUMS.txt` 校验。

该入口与默认 Beta 签名共用 `ZAKOCODER_DEBUG_KEYSTORE_BASE64` Secret：CI 专用 Debug keystore、`androiddebugkey` 别名及 Android 默认 Debug 口令。缺失时失败，不生成替代签名；它不得指向正式发布密钥。CI 在临时 runner 恢复并删除，不上传或缓存 keystore。

同一份 CI Debug 签名且版本递增的包可以相互覆盖，亦与使用默认签名的 Beta 保持签名身份；不保证与本地 Android Studio 签名兼容。Debug 工作流不创建版本标签或 GitHub Release，不替代完整 PR 测试和 Beta 门槛。

## 版本、数据与事故处理

采用 `MAJOR.MINOR.PATCH`，开发版加 `-dev`，预发布加 `-alpha.N`／`-beta.N`／`-rc.N`。发布工作流接受正式版、Beta 与 RC，暂不接受 Alpha。每次公开 Android 包的 `versionCode` 严格递增。应用更新不自动替换已安装的 Ubuntu；源码导出不是会话、设置与密钥的完整备份。

持久格式或协议不兼容时需说明迁移、旧数据读取及失败恢复，不能靠清数据或强制降级处理。发现数据损坏、泄密或恶意制品时停止分发，保留脱敏证据，说明影响版本、临时措施与恢复方案；泄漏密钥需撤销和轮换。修复后按同一门槛重新验证。

管理员仍需维护主分支保护、实际 CI 必需检查及安全报告入口。只有维护者明确授权并触发发布流程，才创建标签与正式版或预发布；本地检查通过不等于发布授权。
