# 发布流程

仓库提供两个手动入口：**Build Debug APK** 保留开发测试包下载；**Publish Beta** 用于维护者确认后的签名预发布。Beta 使用不可调试的 Release 构建及 R8，默认沿用仓库现有 CI Debug 证书，可选配置自己的签名；默认方式不是正式长期发布签名。GitHub Release 必须标记为 **Pre-release**。常规提交和 PR 不自动发布。

当前准备版本为 `0.1.0-beta.1`。新增 Beta 流程不代表已经发布，也不解除稳定版发行门槛。运行时源码与许可证、Release 构建、最终签名和设备检查必须分别有证据；尚未执行的项目见下方清单。

## 手动发布 Beta

1. 选择已完成审查和相关 CI 的提交，核对 [CHANGELOG](../CHANGELOG.md)、[Beta 测试说明](BETA_TESTING.md)及[兼容性结果](ANDROID_COMPATIBILITY_RESULTS.md)。
2. 确认已有 Actions Secret `ZAKOCODER_DEBUG_KEYSTORE_BASE64` 可用。默认沿用它签署 Beta，无需新增 Secret；也可完整配置下方可选签名。所选密钥缺失或无效时失败，不临时生成替代密钥。
3. 在 `main` 上打开 **Actions → Publish Beta → Run workflow**：`version` 填 `0.1.0-beta.1`，`version_code` 首次建议 `1001`，后续严格递增；`target_ref` 为 `main` 或其祖先的完整提交 SHA。只有来自 `main` 的可信工作流可发布；历史 SHA 还必须保持 `.github/workflows/` 树与当前 `main` 完全一致，才允许自动创建标签。已有标签或 Release 会被拒绝，不覆盖历史附件。
4. 工作流校验仓库、执行 Release 构建及相关检查，先生成未签名 APK，再使用所选密钥签名。QA 通过后创建草稿 Release，上传并逐项下载回读校验 SHA-256，最后设为预发布且不标为 Latest。
5. 工作流完成签名 R8 QA 的设备检查和制品一致性校验后，下载实际发布的 APK 与摘要复核。发布页面列出实际通过项、已知限制和未验证项，不能把 Debug 矩阵或 x86 QA 写成公开 ARM64 APK 真机验收。

本流程默认仅打包 **ARM64**。不在 Beta 中附带实验性 x86_64 工具链。构建参数为 `-PbetaRelease=true -PbetaVersion=0.1.0-beta.1 -PbetaVersionCode=1001`，未签名输出为后续签名的输入；不在 Gradle 中保存密钥。完整步骤以 [publish-beta.yml](../.github/workflows/publish-beta.yml) 和 `app/build.gradle.kts` 为准。

### 本次 Beta 签名

默认只需要已有的 `ZAKOCODER_DEBUG_KEYSTORE_BASE64`，内容为 CI 专用 Debug keystore 的 Base64；使用 `androiddebugkey` 别名和 Android 默认 Debug 口令。Beta 的构建类型仍是 **Release + R8，`debuggable=false`**。默认复用 Debug 证书时，公开构建记录必须明确说明，不将其描述为正式长期签名。

可选签名有两个入口：

- **本地**：预留忽略路径 `.local/signing/release.jks`，也可用 `ZAKOCODER_RELEASE_KEYSTORE` 指定路径；由 `scripts/sign_beta_apk.py` 的 `--apk`、`--output`、`--apksigner`、`--zipalign` 传入文件与工具。密钥不存在时使用默认 Debug 签名；指定密钥存在时，必须同时提供 `ZAKOCODER_RELEASE_STORE_PASSWORD`、`ZAKOCODER_RELEASE_KEY_ALIAS`、`ZAKOCODER_RELEASE_KEY_PASSWORD`。配置不全或签名失败直接报错，不静默回退。
- **CI**：可选完整配置 `ZAKOCODER_RELEASE_KEYSTORE_BASE64`、`ZAKOCODER_RELEASE_STORE_PASSWORD`、`ZAKOCODER_RELEASE_KEY_ALIAS`、`ZAKOCODER_RELEASE_KEY_PASSWORD` 四个 Secrets，整组存在时优先使用；均未配置则沿用已有 Debug Secret，部分配置必须报错，避免意外换签名。

CI 签名材料只在 Secrets 和临时 runner 中使用，不写入聊天、Issue、PR、源码、缓存或制品。工作流校验最终证书，公开证书 SHA-256，并在结束时删除 keystore。CI Debug 密钥仍需保留仓库外备份；更换 Secret 会改变后续签名，Secret 本身不是备份。

默认模式下，使用同一仓库 CI 签名、同一 applicationId 且 versionCode 更高的 Beta 可以覆盖对应 Debug 安装；本地 Android Studio 或其他机器的 Debug 密钥可能不同，必须先核对证书。签名不一致时先确认源码和数据备份，不默认卸载或清数据。

后续稳定版若改用正式发布密钥，必须先说明覆盖升级限制并制定迁移方案；不能承诺当前 Debug 证书签名的安装可直接升级。正式长期签名的建立不作为本次 Beta 的新增配置前提。

### 发布附件与诊断材料

每个 Beta 的同一发布位置应提供：

- `ZakoCoder-<version>-arm64.apk`、`SHA256SUMS.txt`、`BUILD.json`、`BUILD.txt`，包含提交、版本、versionCode、构建范围和公开签名证书摘要。
- 与二进制对应的 `native-runtime-sources.tar.gz` 及摘要，包含原始源码、补丁、构建配方和许可。
- `LICENSE.txt`、`THIRD_PARTY.md`、`licenses.tar.gz`、`runtime-validation.tar.gz`、`release-validation.tar.gz`，提供许可证及实际构建／验证记录。不能只链接到可变上游分支代替对应源码。

R8 `mapping.txt` 单独存入 R8 诊断 Actions Artifact，保留 90 天，用于还原堆栈，不作为 Release 附件。**公开仓库的 Actions Artifact 不是私密存储：任何有仓库读取权限且登录的用户都可能下载。**该制品不得包含密钥、凭据或用户数据。候选包 Artifact `beta-candidate-<runid>-<attempt>` 保留 7 天，其中 `public/` 为待发布材料，`qa/ZakoCoder-release-qa-multiabi.apk` 为 ARM64 + x86_64 测试包；QA APK不上传 Release，独立验收证据保留 14 天。维护者应在过期前另行安全保管 mapping。APK 与公开附件均不得含签名材料、用户配置、测试凭据或生成的会话数据。PRoot／loader 分发义务以[第三方材料](../THIRD_PARTY.md)和[独立运行时](../native-runtime/README.md)为准。

## Beta 发布检查

以下区分本轮已完成的本地候选检查与仍待发布的门槛。最终资源清理后的本地重建及 API 35 R8 QA 已通过；干净 checkout 已完成 Release 构建，全部 DEX、资源和 assets 与已验收候选一致；原生 PRoot／loader 的独立摘要检查通过。GitHub Actions 尚未实际运行。每次发布需保留对应提交与制品证据。

- [x] 最终本地 Release R8 编译、Release Lint、应用 280 项、终端 145 项及脚本 58 项测试、AndroidTest 编译通过。
- [x] 最终 API 35 真实 R8 QA 完成项目创建、编辑保存、重启及配置持久化，4 张日夜截图通过。
- [x] 干净 checkout 的仓库检查、58 项脚本测试及 Release 构建通过；无 `example/` 或本地资料依赖。
- [ ] GitHub Actions 首次端到端运行通过（PR 合并后手动触发）。
- [x] 固定运行时源码、补丁、工具链和 ELF／摘要检查及 14 项原生回归通过，对应源码包和许可证已完整收集。
- [x] 最终本地候选签名 APK 的 applicationId、版本、versionCode、ABI、证书和 SHA-256 已核验；调试开关、测试 CA、测试 Provider 不进入发布包。CI 会重新构建并使用其实际密钥重复验收。
- [x] API 35 x86_64 设备安装**与公开包采用同一证书签名的 R8 QA 包**并通过本地界面冒烟；QA 与公开 ARM64 APK 的全部 DEX、resources.arsc、res 与 assets 字节一致，共有原生制品、版本身份与签名证书一致；架构差异单独记录。
- [x] 首个 Beta 没有可比旧 Beta，本次覆盖升级明确记录为未验证；未引用不同签名的 Debug 升级替代。
- [x] 报告清楚区分已有 Debug Ubuntu／PTY／HTTP 基线与本次 Release QA，未运行的链路不标记通过。
- [ ] 公开附件、单独的 R8 诊断 Artifact、安装说明、已知限制和数据边界已由维护者确认；诊断 Artifact 不作为秘密存储。

已完成的 Debug 系统基线见[兼容性结果](ANDROID_COMPATIBILITY_RESULTS.md)：Android 11–17 AOSP x86_64，含 Android 12L；不代表最终 Release APK、所有 ARM64 厂商设备或真实 16 KiB 页设备均通过。Android 8 的完整开发环境与模型网络尚未通过验收，不列入首批 Beta 验收范围。

首批 Beta 允许在上述检查通过后带明确限制分发：**最终公开 ARM64 Release APK 的真机安装、同签名升级、Ubuntu／终端／HTTP 冒烟仍未完成**。既有真机安装需先核对证书，不能为了验收直接覆盖或卸载用户包。API 35 x86 QA 与同代码／同签名校验能验证 R8 风险的一部分，不能替代这些 ARM64 证据。

稳定版仍需补齐 ARM64 最终包真机与升级验证，并由维护者独立评估设备范围、分发材料和遗留问题。Beta 通道不能用于绕过稳定版门槛。

## 本地 Release QA 界面证据

Android 15 / API 35、官方 x86_64 模拟器，使用最终 ARM64 + x86_64 Release QA APK；公开 ARM64 候选与其代码、资源、共有原生制品及签名一致。图片只含测试脚本新建的临时项目、占位配置和无密钥的 `example.invalid` 地址。它们不是预装项目或真实模型交互。

| 页面 | 日间 | 夜间 |
| --- | --- | --- |
| 编辑与保存 | [截图](assets/beta-release/editor-day.png) | [截图](assets/beta-release/editor-night.png) |
| 模型配置持久化 | [截图](assets/beta-release/models-day.png) | [截图](assets/beta-release/models-night.png) |

CI 的每次候选会生成自己的截图及绑定制品摘要的报告；本地证据不替代上游工作流成功记录，也不代表 ARM64 Release 已通过真机完整验收。

## 手动打包测试 APK

**Actions → Build Debug APK → Run workflow** 保留原入口。成功后在 Artifacts 下载 ARM64 Debug APK、`SHA256SUMS.txt`、`BUILD.txt`、自编译运行时及对应源码包；保留 14 天，需登录 GitHub。在解压目录执行 `sha256sum -c SHA256SUMS.txt` 校验。

该入口与默认 Beta 签名共用 `ZAKOCODER_DEBUG_KEYSTORE_BASE64` Secret：CI 专用 Debug keystore、`androiddebugkey` 别名及 Android 默认 Debug 口令。缺失时失败，不生成替代签名；它不得指向正式发布密钥。CI 在临时 runner 恢复并删除，不上传或缓存 keystore。

同一份 CI Debug 签名且版本递增的包可以相互覆盖，亦与使用默认签名的 Beta 保持签名身份；不保证与本地 Android Studio 签名兼容。Debug 工作流不创建版本标签或 GitHub Release，不替代完整 PR 测试和 Beta 门槛。

## 版本、数据与事故处理

采用 `MAJOR.MINOR.PATCH`，开发版加 `-dev`，预发布加 `-alpha.N`／`-beta.N`。每次公开 Android 包的 `versionCode` 严格递增。应用更新不自动替换已安装的 Ubuntu；源码导出不是会话、设置与密钥的完整备份。

持久格式或协议不兼容时需说明迁移、旧数据读取及失败恢复，不能靠清数据或强制降级处理。发现数据损坏、泄密或恶意制品时停止分发，保留脱敏证据，说明影响版本、临时措施与恢复方案；泄漏密钥需撤销和轮换。修复后按同一门槛重新验证。

管理员仍需维护主分支保护、实际 CI 必需检查及安全报告入口。只有维护者明确授权并触发发布流程，才创建标签与预发布；本地检查通过不等于发布授权。
