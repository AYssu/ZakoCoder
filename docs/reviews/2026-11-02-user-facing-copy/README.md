# 用户文案与说明层级验收

日期：2026-11-02。产品及测试源码：`b6b1c60996115bf3fc7f7397864b9fbb45efbbf6`。截图归档提交仅增加本目录材料。

## 实际调整

| 区域 | 修改后的行为 | 保留的信息 |
| --- | --- | --- |
| 关于与版本 | 设置、关于及主界面显示实际安装版本；移除维护者发布验收红字；产品介绍只说明用途 | 组件版本、许可及来源；原许可和 JSch 入口保留，新增全部原始许可文件的选择、全文阅读与复制 |
| 环境与终端 | 离线包说明不固定 ARM64 文件名；新终端说明跟随用户选择的 Shell；项目导入明确为副本；克隆说明包含私有 HTTPS | 架构、版本及完整性校验；Ubuntu/Git 依赖 |
| 模型、协议与上下文 | 技术说明改为可展开帮助，推理强度中文化；修正频率/存在惩罚翻译；容量核对日期移入详情 | 服务实际限制、本地估算、输出预留、90% 默认阈值、摘要损失及关闭压缩后超限会结束任务；底层协议值未改变 |
| MCP 与请求用量 | 简化凭据、导入及空状态，统计口径按需展开 | 导入默认关闭、同名不覆盖、autoApprove 不授予权限、缺失值不当零、缓存不重复计费、重试与待审批工具计数范围 |
| 项目、Git 与编辑器 | 验证及 Git 限制按需展开；无上游不显示获取刷新提示；缓冲、截断、封存等术语改为直观表达 | 本地提交与推送区别、禁用钩子/签名、退出码并非完整验收、磁盘快照可能过时及检查范围限制 |
| 权限、能力包与更新 | 精简常驻说明；明确能力按当前工具权限执行；电池优化状态、Root、安装包表述统一 | YOLO 的规则替换/持久生效/不可撤销操作影响；系统授权独立；后台可能终止；更新架构、签名、版本递增及校验限制 |
| 网络代理 | 常驻说明保留格式及 HTTP 不支持密码，详细使用范围与凭据风险可展开 | Keystore、本机保存、ALL_PROXY 明文环境变量暴露、SOCKS5 对 apt 的限制 |

原始 CommonMark 源码许可保留，另随包补充当前 0.31.0 Maven JAR 内的 Atlassian 版权通知；未新增或升级依赖。

## 验证结果

- `python3 scripts/check_repository.py`、`git diff --check`、提交标题检查通过。
- 集中执行 `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest`。App 353 项单元测试通过；终端库未改，145 项结果为 Gradle UP-TO-DATE。
- 独立审查后的中文强度显示与 Git 提示调整，执行受影响构建、Lint 和 `ModelOptionsDraftTest` 5 项，全部通过；许可资产补充只重新打包并验证许可入口。
- `:app:assembleDebugAndroidTest` 通过；最终 Lint 0 errors / 0 warnings，依赖更新提示和已有 Gradle 弃用提示不属于本次变更。
- 真机 9 个测试类共 17 个不同测试通过；小范围修正后仅重跑受影响类，不反复执行全量回归。

```sh
ANDROID_HOME=/home/sking/Android/Sdk ./gradlew \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
  -PruntimeVerification=true -PbetaRelease=true \
  -PbetaVersion=0.1.0-beta.3 -PbetaVersionCode=1003 --console=plain
```

测试类：`CopyHelpUiDeviceTest`、`UserFacingSettingsDeviceTest`、`CopyPresentationPagesDeviceTest`、`CopyEnvironmentPagesDeviceTest`、`AppPermissionsUiDeviceTest`、`CapabilityPackagesUiDeviceTest`、`AiYoloPermissionUiDeviceTest`、`ScreenTestUiDeviceTest`、`AiComposerUsageUiDeviceTest`。均通过 `androidx.test.runner.AndroidJUnitRunner` 分批在设备执行。

## 设备与证据边界

- Android 13 ARM64 真机，隔离测试包 `dev.zakocoder.app.runtimecheck`，360dp 宽、130% 字体，日夜两种主题。
- `0.1.0-beta.3 / 1003` 仅为本次本地验证版本，用于证明界面读取真实版本，不代表已发布 Beta.3。主应用数据未清除。
- 设置/关于/代理截图走真实应用导航，读取实际包版本及随包许可。其他截图使用生产组件与明确的隔离状态：空白模型草稿、空用量、临时项目、无上游 Git、关闭的 LSP、权限状态及本地模拟流。
- 更新截图为 `UpdateContent` 的 Beta.2 静态状态，不能作为实际联网更新、下载、签名检查或安装成功的证据。
- 权限与屏幕测试截图只展示状态和回调，不请求 Root、安装 APK 或改变用户的系统授权。MIUI 测试 Activity 后台启动临时 AppOp 已恢复为原始 ignore。
- 部分组件宿主的系统栏配色不代表完整应用导航；正文、操作区及夜间语义色已实际查看。可滚动长正文不要求一张截图展示全文，测试还验证展开/收起与关键风险文案。
- 组件可见性断言只能证明出现；结合人工截图检查判断排版，不宣称截图等同于网络、工具执行和跨厂商兼容性验收。部分隔离 Workbench 的长期协程随仪器测试进程结束释放，未改产品生命周期。
- 原始 PNG 共 76 张，尺寸与摘要见 [screenshots.json](screenshots.json)。下表按位置配对；不含真实密钥、用户工程或真实会话。

## 日夜截图

| 位置 / 状态 | 日间 | 夜间 |
| --- | --- | --- |
| 开源组件说明展开 | <img src="day-about-components.png" width="180" alt="开源组件说明展开日间" /> | <img src="night-about-components.png" width="180" alt="开源组件说明展开夜间" /> |
| 关于与真实版本 | <img src="day-about-version.png" width="180" alt="关于与真实版本日间" /> | <img src="night-about-version.png" width="180" alt="关于与真实版本夜间" /> |
| 导入与私有 Git 说明 | <img src="day-add-project.png" width="180" alt="导入与私有 Git 说明日间" /> | <img src="night-add-project.png" width="180" alt="导入与私有 Git 说明夜间" /> |
| 项目能力包 | <img src="day-capability-packages.png" width="180" alt="项目能力包日间" /> | <img src="night-capability-packages.png" width="180" alt="项目能力包夜间" /> |
| 真实助手组件流式测试状态 | <img src="day-composer-active-streaming.png" width="180" alt="真实助手组件流式测试状态日间" /> | <img src="night-composer-active-streaming.png" width="180" alt="真实助手组件流式测试状态夜间" /> |
| 真实助手组件完成状态 | <img src="day-composer-completed-large-usage.png" width="180" alt="真实助手组件完成状态日间" /> | <img src="night-composer-completed-large-usage.png" width="180" alt="真实助手组件完成状态夜间" /> |
| 语言服务按需开启说明 | <img src="day-editor-language-help.png" width="180" alt="语言服务按需开启说明日间" /> | <img src="night-editor-language-help.png" width="180" alt="语言服务按需开启说明夜间" /> |
| 环境管理与离线导入 | <img src="day-environment-management.png" width="180" alt="环境管理与离线导入日间" /> | <img src="night-environment-management.png" width="180" alt="环境管理与离线导入夜间" /> |
| Git 无上游与提交限制 | <img src="day-git-commit-help.png" width="180" alt="Git 无上游与提交限制日间" /> | <img src="night-git-commit-help.png" width="180" alt="Git 无上游与提交限制夜间" /> |
| 说明组件默认收起（隔离展示） | <img src="day-help-collapsed.png" width="180" alt="说明组件默认收起（隔离展示）日间" /> | <img src="night-help-collapsed.png" width="180" alt="说明组件默认收起（隔离展示）夜间" /> |
| 预算说明组件（隔离展示） | <img src="day-help-context-expanded.png" width="180" alt="预算说明组件（隔离展示）日间" /> | <img src="night-help-context-expanded.png" width="180" alt="预算说明组件（隔离展示）夜间" /> |
| MCP 说明组件（隔离展示） | <img src="day-help-mcp-expanded.png" width="180" alt="MCP 说明组件（隔离展示）日间" /> | <img src="night-help-mcp-expanded.png" width="180" alt="MCP 说明组件（隔离展示）夜间" /> |
| 统计说明组件（隔离展示） | <img src="day-help-statistics-expanded.png" width="180" alt="统计说明组件（隔离展示）日间" /> | <img src="night-help-statistics-expanded.png" width="180" alt="统计说明组件（隔离展示）夜间" /> |
| 许可全文 | <img src="day-license-fulltext.png" width="180" alt="许可全文日间" /> | <img src="night-license-fulltext.png" width="180" alt="许可全文夜间" /> |
| 原始许可列表 | <img src="day-license-list.png" width="180" alt="原始许可列表日间" /> | <img src="night-license-list.png" width="180" alt="原始许可列表夜间" /> |
| 新终端 Shell 说明 | <img src="day-new-terminal.png" width="180" alt="新终端 Shell 说明日间" /> | <img src="night-new-terminal.png" width="180" alt="新终端 Shell 说明夜间" /> |
| 悬浮入口说明 | <img src="day-overlay-settings.png" width="180" alt="悬浮入口说明日间" /> | <img src="night-overlay-settings.png" width="180" alt="悬浮入口说明夜间" /> |
| 模型栏中文推理强度 | <img src="day-page-composer-model.png" width="180" alt="模型栏中文推理强度日间" /> | <img src="night-page-composer-model.png" width="180" alt="模型栏中文推理强度夜间" /> |
| 上下文预算与压缩说明 | <img src="day-page-context-details.png" width="180" alt="上下文预算与压缩说明日间" /> | <img src="night-page-context-details.png" width="180" alt="上下文预算与压缩说明夜间" /> |
| 上下文设置 | <img src="day-page-context-options.png" width="180" alt="上下文设置日间" /> | <img src="night-page-context-options.png" width="180" alt="上下文设置夜间" /> |
| MCP 导入权限说明 | <img src="day-page-mcp-import.png" width="180" alt="MCP 导入权限说明日间" /> | <img src="night-page-mcp-import.png" width="180" alt="MCP 导入权限说明夜间" /> |
| 模型高级配置与中文推理强度 | <img src="day-page-model-options.png" width="180" alt="模型高级配置与中文推理强度日间" /> | <img src="night-page-model-options.png" width="180" alt="模型高级配置与中文推理强度夜间" /> |
| 更新安全说明（静态状态） | <img src="day-page-update-details.png" width="180" alt="更新安全说明（静态状态）日间" /> | <img src="night-page-update-details.png" width="180" alt="更新安全说明（静态状态）夜间" /> |
| 用量统计口径展开 | <img src="day-page-usage-details.png" width="180" alt="用量统计口径展开日间" /> | <img src="night-page-usage-details.png" width="180" alt="用量统计口径展开夜间" /> |
| 用量空状态 | <img src="day-page-usage-empty.png" width="180" alt="用量空状态日间" /> | <img src="night-page-usage-empty.png" width="180" alt="用量空状态夜间" /> |
| 后台运行说明 | <img src="day-permissions-background.png" width="180" alt="后台运行说明日间" /> | <img src="night-permissions-background.png" width="180" alt="后台运行说明夜间" /> |
| 特殊权限 | <img src="day-permissions-special.png" width="180" alt="特殊权限日间" /> | <img src="night-permissions-special.png" width="180" alt="特殊权限夜间" /> |
| 系统权限 | <img src="day-permissions-system.png" width="180" alt="系统权限日间" /> | <img src="night-permissions-system.png" width="180" alt="系统权限夜间" /> |
| YOLO 确认 | <img src="day-permissions-yolo-confirm.png" width="180" alt="YOLO 确认日间" /> | <img src="night-permissions-yolo-confirm.png" width="180" alt="YOLO 确认夜间" /> |
| YOLO 生效状态 | <img src="day-permissions-yolo-enabled.png" width="180" alt="YOLO 生效状态日间" /> | <img src="night-permissions-yolo-enabled.png" width="180" alt="YOLO 生效状态夜间" /> |
| 工具权限 | <img src="day-permissions.png" width="180" alt="工具权限日间" /> | <img src="night-permissions.png" width="180" alt="工具权限夜间" /> |
| 代理凭据风险展开 | <img src="day-proxy-security.png" width="180" alt="代理凭据风险展开日间" /> | <img src="night-proxy-security.png" width="180" alt="代理凭据风险展开夜间" /> |
| 代理简短说明 | <img src="day-proxy-summary.png" width="180" alt="代理简短说明日间" /> | <img src="night-proxy-summary.png" width="180" alt="代理简短说明夜间" /> |
| 安装包授权说明 | <img src="day-screen-apk-approval.png" width="180" alt="安装包授权说明日间" /> | <img src="night-screen-apk-approval.png" width="180" alt="安装包授权说明夜间" /> |
| Root 权限说明 | <img src="day-screen-root-guidance.png" width="180" alt="Root 权限说明日间" /> | <img src="night-screen-root-guidance.png" width="180" alt="Root 权限说明夜间" /> |
| 屏幕测试配置 | <img src="day-screen-test.png" width="180" alt="屏幕测试配置日间" /> | <img src="night-screen-test.png" width="180" alt="屏幕测试配置夜间" /> |
| 设置实际页面 | <img src="day-settings.png" width="180" alt="设置实际页面日间" /> | <img src="night-settings.png" width="180" alt="设置实际页面夜间" /> |
| 任务验证说明 | <img src="day-validation-help.png" width="180" alt="任务验证说明日间" /> | <img src="night-validation-help.png" width="180" alt="任务验证说明夜间" /> |
