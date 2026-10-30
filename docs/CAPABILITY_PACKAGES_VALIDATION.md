# 能力包验收记录

日期：2026-10-30。基线 `56d8608`；本地功能分支 `feat/android-screen-testing`。本记录覆盖两个独立能力包及其 AI 接线，屏幕后端此前的验证见 [屏幕测试验收](SCREEN_TESTING_VALIDATION.md)。

## 隔离与方法

Android 13 / ARM64 真机。使用独立测试宿主，不覆盖日常应用。SSH 使用开发机上独立端口的临时 OpenSSH 服务及专为本次测试生成的密钥，通过 ADB reverse 连接；不读取用户已有凭据，不更改系统 SSH 服务或用户 authorized_keys。每项远程测试仅操作 UUID 命名的自有文件、配置与 tmux 任务。

AI 测试使用本地 HTTPS 确定性协议服务，不使用用户模型密钥。测试核对实际 HTTP 请求、真实工具输出及持久历史，不将假服务器回复当作真实 SSH 或屏幕操作的证据。这证明工具接线与权限行为，不证明商业模型的自主规划能力。

## 已执行结果

| 检查 | 实际结果 |
| --- | --- |
| 原生 Debug 与设备测试包构建、Lint | 通过；JSch 使用版本目录，原始 BSD 声明随 APK 提供 |
| 应用单元测试 | 226 项通过，包括 9 项 SSH 安全与 UTF-8 分页测试 |
| 能力包模型协议设备测试 | 4 项通过：默认请求不携带可选 schema / 专属说明；独立开启；未启用调用拒绝；关闭重开不能复用旧审批 |
| 能力开关存储设备测试 | 4 项通过：项目隔离、重建后状态、新租约、立即撤销、损坏拒绝及 AtomicFile 备份恢复 |
| 真实 SSH 设备测试 | 8 项通过：首次指纹确认、退出码、SFTP 精确修改与摘要、中文分页、并发修改拒绝、链接与越界拒绝、输出限制、超时取消、tmux 重连与配置租约、凭据加密与端点更换 |
| AI 屏幕协议与审批回归 | 12 项通过；已显式启用屏幕包，维持禁止规则、临时截图保留和任务租约检查 |
| AI → SSH 真实闭环 | 1 项通过：审批前远程文件不存在；批准后实际执行写入；SFTP 读回内容与 SHA-256，下一轮模型请求及持久历史包含实际结果 |
| AI → 屏幕真实闭环 | 1 项通过：开启包后启动、读取节点、真实点击、断言、截图回传，结束撤销目标授权 |
| 独立批量只读审查 | 原审批配置竞争、分页摘要、tmux 登记及备份恢复问题已修复；最终复审未发现新增关键阻塞项 |

本批真机集成共 30 项。终端模块未变，复用此前 145 项已通过结果，没有因每次编辑重复全量测试。界面渲染另行记录，不混入真实传输计数。

构建命令：

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest
```

设备测试使用本地 Gradle init 脚本替换 applicationId 实现宿主隔离；交付 APK 不使用该脚本，包名仍为 `dev.zakocoder.app`。仅测试脚本时序修正后，只重编设备测试 APK 并重跑界面用例。

## 限制

- 本轮实测 Android 13；其他平台的密钥算法、无障碍与 Root 兼容性仍需对应真机验证。UI 支持密码和私钥，本次 SSH 服务实际验证私钥认证，没有另行开启密码服务。
- 远程工作目录不是 Shell 权限沙箱；获批命令具有 SSH 用户本身的权限。远程副作用不被本地项目检查点恢复。
- 取消关闭所属连接；不能保证服务器已结束脱离连接的普通进程。tmux 任务按设计留在远端，后续可查看或明确关闭。
- SFTP 修改核对当前摘要并尽力使用同目录临时文件及重命名；最后一次校验与替换之间仍可能受到其他客户端写入的竞争，不宣称完整原子比较交换。
- 屏幕截图保留原有目标与分享授权约束，Android 11–13 瞬时覆盖等边界见屏幕测试文档。

## 界面验收与截图

`CapabilityPackagesUiDeviceTest` 日夜两项均通过，共 30 张真实原生界面截图，360dp 视口、130% 字号。已核对滚动可达、默认关闭、密码／私钥切换、凭据保存后清空、停止按钮、指纹确认及变更后的禁用状态。首轮测试暴露页面组合提交前误编辑上一场景的测试时序问题，已隔离场景状态并等待实际提交，保留启用与凭据清空断言；最后仅重编并重跑这两项测试。截图补拍让主机指纹阻断和清除操作同屏可见。

截图使用合成配置和纯 UI 状态，不包含真实密钥、用户项目或用户会话；它们证明渲染和回调，真实连接由上方 SSH 测试独立证明。文件为无损 WebP，仅裁去系统状态栏与导航栏；尺寸、主题、原始截图及归档文件摘要见 [截图清单](assets/capability-packages/screenshots.json)。截图与对应功能源码一并提交；PR 正文以提交固定链接关联本次归档。

| 场景 | 日间 | 夜间 |
| --- | --- | --- |
| 默认关闭与项目范围 | [查看](assets/capability-packages/light-packages-default-off-360-font130.webp) | [查看](assets/capability-packages/dark-packages-default-off-360-font130.webp) |
| 两个独立能力包 | [查看](assets/capability-packages/light-ssh-enabled.webp) | [查看](assets/capability-packages/dark-ssh-enabled.webp) |
| 密码配置 | [查看](assets/capability-packages/light-ssh-password-configured.webp) | [查看](assets/capability-packages/dark-ssh-password-configured.webp) |
| 私钥配置 | [查看](assets/capability-packages/light-ssh-private-key.webp) | [查看](assets/capability-packages/dark-ssh-private-key.webp) |
| 首次确认指纹 | [查看](assets/capability-packages/light-ssh-first-host-confirmation.webp) | [查看](assets/capability-packages/dark-ssh-first-host-confirmation.webp) |
| 指纹变化阻断 | [查看](assets/capability-packages/light-ssh-host-key-changed.webp) | [查看](assets/capability-packages/dark-ssh-host-key-changed.webp) |
| 连接中停止 | [查看](assets/capability-packages/light-ssh-connecting-cancel.webp) | [查看](assets/capability-packages/dark-ssh-connecting-cancel.webp) |
| 验证通过与 tmux | [查看](assets/capability-packages/light-ssh-connected-tmux.webp) | [查看](assets/capability-packages/dark-ssh-connected-tmux.webp) |
| 开源许可证 | [查看](assets/capability-packages/light-ssh-bsd-license.webp) | [查看](assets/capability-packages/dark-ssh-bsd-license.webp) |

本轮共 32 项真机用例通过（30 项集成、2 项界面）；先前未修改的屏幕后端专项覆盖保留在其独立记录，不重复计入本轮。临时无障碍设置和测试应用后台启动 app-op 已恢复；临时 SSH 服务已停止，ADB reverse 与合成测试密钥已移除。Debug APK 已校验签名及正式包名，未覆盖用户日常应用。

## 权限、流式输出与上下文修订验收

本次修订使用同一基线，移除权限提示中设备品牌、管理器名称及测试宿主标识，不改变授权边界。SSH 密码字段的标签变量更名，以避免 UI 标签被误判。GitGuardian 后续仍指向 PR 旧提交中的该标签表达式；这是历史扫描记录，改名不能清除，需在其控制台标记该项误报后重扫。未忽略整个规则或伪造通过状态。

统一完成 Debug、设备测试包、Lint 和应用单元测试：**236 项单元测试通过**。产品不变后，因超预算测试仍沿用旧字符假设，将合成回复从 30k 调整为 60k ASCII，确保该回复自身确实超过输入预算；仅重编测试包和定向重跑该用例，没有削弱一次请求、零摘要、工具计数及恢复断言。

本次 **36 项不同的真机用例通过**：上下文 9、Token 预算与迁移 3、多模态续接 1、能力包模型协议 4、屏幕审批 12、日夜界面 6、AI → 实际屏幕闭环 1。首次后端批次被测试启动器提前切换界面测试中断，后续能力包与审批用例另起完整批次通过；中断不计为通过，也不重复累计用例。

- 实际本地 HTTPS 控制器请求验证：1M 窗口下超过 96 条消息、60 轮／600k 字符历史不会提前总结，最早消息仍发送，重启保留 61 轮；近阈值的引号及反斜杠代码可压缩，不发生整段 JSON 二次转义放大。
- 平滑输出验证突发回复逐步显示并最终完整保留，单元测试覆盖 Unicode 常见组合及有界追赶；截图包含实时中间态和完成后的 Markdown。不宣称覆盖所有设备的帧率或真实提供商的分词、延迟表现。
- 此前窗口 UI 在 360dp、130% 字号下验证五组预设、只修改容量、自定义 1M 和自动压缩开关。当批上下文设置与平滑输出分别提供 6 张和 [4 张](assets/smooth-streaming/screenshots.json)日夜截图；上下文预设已由后续修订截图替换；权限界面 26 张同步重拍。新增设置的软键盘完整交互未单独验证。
- 真实屏幕闭环再次完成启动、节点读取、点击、断言、截图回传及授权撤销，已更新证据。临时无障碍配置及测试宿主后台启动设置均恢复；没有覆盖日常应用。

模型窗口来源、本地估算与真实用量区别、旧配置迁移及独立资源边界见[模型上下文](MODEL_CONTEXT.md)。未调用真实付费模型做容量或视觉语义验收；原 SSH 实际传输实现未变，复用上一批已通过结果。

请求详情截图补充：仅扩充上述日夜两项 UI 用例，重编测试包并定向通过；同屏核对本地输入估算／窗口／输出预留与合成服务实际输入、输出、缓存用量，新增两张截图。产品源码未变，未重复执行全量测试。

## 模型请求重连与近期型号修订

2026-10-31，在上述修订基础上新增当前模型请求的有界重试；不重放任务、工具或已经批准的远端副作用。暂态连接错误、读超时、缺失流结束标记和明确的暂态 HTTP 状态最多尝试三次，等待 1 / 2 秒加少量抖动；尊重不超过 30 秒的 `Retry-After`。永久认证、证书、格式和输出限制错误不会自动重试。等待可取消，新尝试真正收到内容前保留原部分输出；每次请求的失败、等待及关联编号按既有有界规则保存。请求可能重复计费，不宣称断点续传或完整计费审计，详见[模型请求重连](AI_WORKFLOW.md#模型请求重连)。

近期型号参考移除旧 GPT 示例，改为 DeepSeek V4.1 Flash / V4 Pro、GPT-6.1 Sol / Terra、GPT-6 Astra / Luna、Gemini 4 Argon 和 Grok 4.7 八项，按厂商分组，展示 Model ID 与精确容量。Sol / Terra 的官方 Responses 工具协议要求在对应条目明确提示；当批尚只支持 Chat Completions，后续四协议修订见下文。容量预设不承诺原生接口或兼容网关已适配，官方来源列于[模型上下文](MODEL_CONTEXT.md)。大上下文的估算、摘要材料构造及 JSON 测量转到工作线程，避免这些扫描直接占用界面线程；不因此宣称所有性能问题已消除。

本轮 UI 采用 `mobile-ui-design-kit` 的 360dp、间距与语义层级约束，保留原生 Compose 与现有颜色体系。上下文配置提供 [12 张日夜截图](assets/context-budget/screenshots.json)，重试状态与逐次请求详情提供 [4 张日夜截图](assets/request-retry/screenshots.json)，均为合成数据的真机渲染。按整张模型条目检查滚动可见性，包括 Model ID、容量与协议提示；未完成软键盘弹起后的完整交互专项。

本轮统一 Debug / 设备测试包构建、Lint 与 **238 项应用单元测试通过**。初批 39 项设备测试中 37 项通过，两项失败保留记录后分别定位：慢流夹具的 `throttleBody` 也限速读取请求体，默认 10 秒夹具读取超时发生在响应头之前；将该夹具改为生产相同的 120 秒空闲超时、保持无总请求截止，并加强为从首事件起实际持续输出超过 125 秒，仍核对全部 3,000 字符、3,001 事件及成功结束。另一项真实 Ubuntu sed / Python 用例在隔离宿主缺少所需运行时；补充显式前置检查后跳过，未安装或复制用户环境，不计为通过。

最终按不同用例去重：**43 项真机测试通过、1 项前置条件不满足而跳过**。通过项为重试传输 6、重试控制器 3、上下文 9、诊断 8、DeepSeek 协议 5、TLS 兼容 2、能力包协议 4、多模态续接 1、日夜 UI 4、实际 AI → 屏幕闭环 1。新增重试验证包括请求快照一致、HTTP / 网络错误、服务端等待、失败次数耗尽、部分输出替换、永久错误拒绝、取消、工具不重放及历史恢复。原屏幕审批、SSH 和未改动终端验证复用前述结果，不重复计入本批。

实际屏幕闭环重新完成并更新证据。测试结束已恢复并核对临时无障碍设置，测试宿主后台启动 app-op 恢复默认；正式包名与签名核对通过，交付 Debug APK 未安装覆盖日常应用。未使用付费模型、未实测提供商真实上下文容量；隔离宿主 Ubuntu sed / Python 集成用例仍待具备环境后单独执行。GitGuardian 历史 UI 标签误报仍需外部控制台处理，与 GitHub Actions 的构建／仓库／标题检查分别报告。

## 四类模型协议与配置界面修订

2026-10-31，补齐 Responses、Anthropic Messages、Gemini GenerateContent，与原 Chat Completions 共四类显式协议。协议层独立实现，共用现有工具审批、取消、重试和日志，不增加 SDK、后端或默认活动模型。新增 11 个常用服务预设；参数及未实现范围见[模型协议](MODEL_PROTOCOLS.md)。旧配置默认 Chat，不按模型名自动选择协议，不在失败时切换服务。

统一 Debug / 设备测试包构建、Lint、**238 项应用单元测试通过**。首批 Lint 发现替换后的 11 个无引用旧字符串资源，清理后原检查通过，未关闭规则。独立批量审查发现的关闭思考与推理强度冲突、原生流内暂态错误缺少重试、Gemini 文本碎片重复复制、非法模型名未完成错误日志均已修复，并由定向用例覆盖。

新增 **43 项协议与集成真机测试通过**：Responses wire 7、Responses transport 4、Responses Controller 1、Anthropic wire 7、Gemini wire 12、共享原生 transport 6、原生 Controller 2、协议配置持久化 4。使用本地 HTTPS 服务与独立项目目录，验证实际路径、认证头、流事件、工具结果、分页、Token 用量、持久会话和签名数据；没有使用商业模型账号。

- Responses 使用 `store: false`，完整本机历史及加密 reasoning 续接，不依赖服务端 response ID。完整 `response.completed` 前不执行工具；HTTP 200 内明确的 server_error / rate_limit 与 HTTP 暂态错误一样有界重试，未知错误不盲目重试。
- Anthropic、Gemini 实际控制器完成 read_file 工具往返、任务结束、历史落盘、重建及再次续聊；签名原样重放、工具只执行一次，时间线和请求详情不含签名或不透明思考数据。Gemini 无原生调用 ID 的场景保留本地映射，不伪造服务端 ID。
- 原生图片转换由独立用例核对，诊断移除图片编码；未实测商业模型的视觉语义。细粒度 Gemini 正文和公开思考各 10,000 个碎片可聚合为有限重放片段，保留签名边界；这不是所有设备帧率的性能承诺。
- 配置验证旧字段缺省、四协议保存与重载、未知协议拒绝、跨来源凭据保护；无效 Gemini 模型路径在网络前返回完整的配置错误记录。

旧协议定向回归 **32 项通过、1 项跳过**，覆盖 Chat 请求重试、DeepSeek、诊断、上下文与多模态历史。包含从首事件持续输出超过 125 秒的真实本地 HTTPS 回复，核对全部内容及结束状态。跳过项为需要 Ubuntu sed / Python 的集成用例，隔离测试宿主没有安装运行时；没有复制用户环境，不计为通过。产品未变后只重编截图测试包，不再重跑后端及单元测试。

本批按不同用例计 **82 项真机测试通过、1 项跳过**（43 新协议 + 32 旧协议回归 + 7 UI/helper），重复截图运行不重复计数。协议与供应商选择采用 `mobile-ui-design-kit`，协议页实际渲染生产模型编辑器与列表，提供 [20 张日夜截图](assets/model-protocol/screenshots.json)；上下文分组列表的 [12 张](assets/context-budget/screenshots.json)和增加协议字段的请求详情 [4 张](assets/request-retry/screenshots.json)同步更新，共 36 张。验证 360dp 视口、130% 字号、四协议切换、完整请求路径、供应商列表滚动、选后模型不变且不发网络、只读状态和协议标签。首次截图捕获过早、面板仍在动画中，且独立测试根容器缺少生产级安全区；修正测试等待、可见边界检查与测试容器安全区后仅重编测试 APK、定向重拍三项协议用例，不沿用旧图。截图均为合成数据，已归档原始捕获和无损文件摘要；软键盘完整交互未单独验证。

设备临时无障碍配置已按测试前快照恢复并核对，两个独立测试包的后台启动 app-op 已恢复默认。正常交付 APK 已校验 `dev.zakocoder.app` 包名和签名，未安装覆盖日常应用。最终独立静态审查未发现新增阻塞项。商业提供商调用、其他 Android 设备及未安装 Ubuntu 的用例仍属于明确限制；GitGuardian 旧提交误报与 Actions 结果分别处理。
