# 请求缓存与屏幕权限入口审查

日期：2026-10-31。状态：构建、定向回归、缓存设备测试、真实模型缓存与历史恢复、屏幕权限及悬浮窗口日夜链路已完成。本记录不包含服务密钥、用户会话、模型生成的项目源码、设备标识或本地请求日志。

## 缓存问题与修复范围

原请求构造每轮原地覆盖靠前的技能／项目元数据消息。该消息同时携带当前命令与文件变更证据；命令证据中的自动采样时间每次不同，即使没有新的用户操作，也会改变这个位置之后的请求前缀。会话增长时，大部分历史仍需要重新输入，不能仅凭总请求量判断缓存是否有效。

修复冻结本任务的初始元数据，将后续动态证据改为追加状态消息。只有语义变化的分区送入模型，不重复复制全部技能与项目概览；忽略限定位置的自动采样时刻，不忽略正文、规则变化、命令退出、源文件验证、读取失败或权限变化。消息在完整工具调用／结果组之后追加，不拆散协议配对。压缩包含最新状态快照，继续保留原输入窗口、输出预留与本地容量上限；失败恢复与摘要不能授权重放副作用。

缓存统计以唯一请求 ID 去重，并区分用户轮、模型请求、协议重试和压缩请求。输入命中率使用“缓存读取 Token ÷ 输入 Token”，输出不计入分母；未知缓存用量不能当成零命中。供应商总用量可能把输入和输出合计展示，核对时应拆分。客户端没有服务器缓存的控制权，稳定前缀不保证固定命中率，更不能把字符数当成实际 Token。

本轮只修复共同请求构造，不向任意兼容端点新增缓存请求字段，也不为提高命中率自动发送额外付费请求。其他供应商缓存策略是独立兼容事项。

## 上游对比与来源

仅阅读协议设计与小范围相关实现，没有复制参考项目产品代码或将本地参考设为构建依赖。

| 来源 | 核对位置与结论 | 本轮采用范围 |
| --- | --- | --- |
| DeepSeek 官方上下文缓存文档 | 匹配已持久化的完整前缀单元；提供 `prompt_cache_hit_tokens`／`prompt_cache_miss_tokens`；构建和保留为尽力而为 | 保持请求前缀稳定，正确理解命中统计；不承诺百分比 |
| Operit 本地参考 v1.12.2，提交 `dbf71916fae9750cfdc9f9a774f5a0fee56633fb` | `OpenAIResponsesProvider.kt` 条件附加 `prompt_cache_key`；`ClaudeProvider.kt` 对工具、系统与最新内容块设置缓存断点 | 作为供应商显式适配的对比；未移植相关实现 |
| opencode 官方 `dev`，查阅时提交 `5030a55fd203a23be80eb7089cf9b84a89c3a4e4` | `packages/core/src/plugin/provider/prompt-cache-key.ts` 按已审核供应商设置兼容标记，避免把部分模型／路由支持当成全供应商支持 | 不向任意服务盲加缓存字段；当前路径已不同于旧版 `packages/opencode` |

参考链接：

- [DeepSeek：Context Caching](https://api-docs.deepseek.com/guides/kv_cache)
- [Operit：v1.12.2 参考提交](https://github.com/AAswordman/Operit/commit/dbf71916fae9750cfdc9f9a774f5a0fee56633fb)
- [opencode：供应商缓存键兼容范围](https://github.com/anomalyco/opencode/blob/5030a55fd203a23be80eb7089cf9b84a89c3a4e4/packages/core/src/plugin/provider/prompt-cache-key.ts)

上述源码对比只证明相应实现存在，不代表已实测这些项目的命中率，也不证明任意第三方网关兼容。

## 屏幕授权与悬浮入口

工具 Allow、能力包开启、Android 系统能力、项目 APK 选择与任务租约、安装确认、截图分享分开处理。Allow 只免去该工具的模型审批，禁止规则优先，不跨越剩余控制。Root 检查成功不能代替系统安装确认；关闭入口也不能被描述为撤销系统授权。

悬浮入口默认关闭，需用户显式开启并授予系统悬浮权限。只在活动测试存在时显示；入口的返回／停止不能形成新的模型执行通道。执行动作前隐藏，避免遮挡目标窗口。实际后台恢复、停止与日夜显示须使用当前构建真机验证。

## 验证状态

| 检查 | 当前记录 | 证据边界 |
| --- | --- | --- |
| 缓存前缀与状态更新代码审查 | 已完成 | 不等于供应商真实缓存命中验证 |
| 原始差异格式检查 | 已完成 | 仅静态格式检查 |
| `AiContextCacheTest` 五项 | 真机通过，5 项零失败 | 使用 Android 实际 JSON；证明前缀与状态投递契约，不是服务缓存命中率 |
| `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug` | 统一执行通过，约 54 秒 | 编译应用、设备测试及静态检查；不替代真机后端验证 |
| 最新历史／屏幕修复后的构建与 Lint | Debug、AndroidTest 与 Lint 通过 | 历史设备测试采用最终构建；屏幕实际链路采用同一屏幕代码，后续仅修改历史读取兼容与测试 |
| 全量应用单元测试 | 272 项通过，零失败 | 新缓存 JSON 测试已移至设备测试，不以 JVM Android mock 充当实际 JSON |
| 任务所有权小修后的定向回归 | `AiScreenTestPolicyTest`、`AiSummaryCorrectionTest` 及构建／Lint 通过 | 只重跑受影响检查，未把旧结果当成本次回归 |
| 真实同会话缓存与压缩恢复 | 已通过，约 72.132 秒；8 次实际模型请求包含 1 次摘要、7 次普通请求；实际完成压缩恢复及四个文件的只读操作 | 有缓存用量报告的 5 次热段请求输入合计 50,484 Token、命中 49,152 Token，命中率 97.36%；其余 3 次缓存未知，不计为零。不与旧的不同模型样本作同条件涨幅结论 |
| Allow 与分层授权设备链路 | `AiScreenTestDeviceTest` 14 项通过 | 覆盖 Allow／Ask／Deny、缺少目标租约及任务所有者取消；不是自动授予 Android 权限 |
| Root 真实设备后端 | `ScreenTestRootDeviceTest` 2 项通过，约 96.152 秒 | 真实观察、点击及断言；错误断言、旧快照和其他前台应用被拒绝 |
| 初次悬浮入口合批 | 1 项因缺少系统悬浮授权 assumption 跳过 | 保留当时结果，不计为通过；后续单独获得授权后重新验证 |
| 真实悬浮入口日间／夜间 | `ScreenTestOverlayDeviceTest` 分别 1 项通过，约 23.572／16.514 秒 | 返回宿主前台、项目匹配与导航消费均为 true；执行忙碌时隐藏，旧租约停止不能结束新租约，新租约停止生效 |
| `ScreenTestUiDeviceTest` 日间／夜间 | 真机 2 项通过；与缓存 5 项合批约 44.98 秒 | 合批共 8 项：7 项通过、1 项跳过，不能写成 8 项全通过 |
| 14 张面板日夜截图与独立视觉审阅 | 已归档，未发现阻塞显示问题 | 360dp、130% 字号；实际 Compose 面板采用固定测试状态，不证明系统悬浮窗或付费缓存链路 |
| 上下文完整设备回归及新增历史用例 | `AiContextDeviceTest` 9 项与 `AiContextHistoryDeviceTest` 3 项合批通过，22.32 秒 | 首轮 8 过 1 失败，发现历史读取拒绝工具组后受控状态消息；修复后验证多次压缩／重启、旧格式兼容及 6 类非法消息拒绝 |
| 实际付费缓存会话重启恢复 | `AcceptanceRecoveryDeviceTest#completedPaidCacheTurnRetainsAppendedEvidenceAcrossStoreReopen` 通过，0.135 秒 | 只重开本次授权验收会话，不发模型请求；四次真实文件读取、追加状态、压缩摘要及请求记录完整一致 |

真实模型验证从已有长历史触发了一次压缩。压缩后的请求实际包含 `rules`、`commands`、`changes`、`mcp` 四个分区的完整当前快照；后续连续请求保留此前 7／9／11／13／15 条消息不变，证明状态投递没有继续回写这些历史前缀。上述结果是本次服务已报告热段的实际观察，不是固定命中率承诺，也不能证明未报告用量的请求具有相同命中率。截图面板、真实模型与真实悬浮窗口分别验证，不能互相替代。

## 真实入口回归与已修复阻塞

后续真实测试不是把初次跳过或失败改成通过：曾遇到测试夹具旧启动组件阻止进入验收页面、Root 坐标动作在 20 秒预算内重复采集多份窗口／节点信息，以及点击返回后过早移除悬浮窗口导致前台导航未完成。相应修复核对目标启动组件，减少重复采集并保留动作前的快照／前台／遮挡复核，调整返回入口的窗口生命周期；之后才单独完成上述日间和夜间真实回归。

设备所有者显式授予测试宿主所需 Root 与悬浮权限。验收中存在人工或 Root 辅助将宿主／测试目标带到前台的步骤，不能据此宣称应用在所有 OEM 后台启动策略下都能自动取得前台，也不把设备 Root 管理器行为归为本应用提供的功能。真实入口结果适用于本次已授权设备与目标，其他系统版本和 OEM 后台限制仍需覆盖。模型不会代替用户批准系统权限。

维护者补充结果时应记录实际命令、退出结果、测试类及截图来源；区分状态 fixture、真实后端及真实模型请求。测试失败、系统限制与未验证事项保留，不能通过删除记录变成“通过”。

## 面板截图

以下为 `ScreenTestUiDeviceTest` 在真机渲染产品 Compose 面板的固定状态截图，共 7 组、14 张。独立视觉审查未发现内容重叠、关键操作裁切或日夜显示阻塞；其中“入口显示”等状态由 fixture 提供，不能据图声称系统悬浮窗口已经实际建立。

| 场景 | 日间 | 夜间 |
| --- | --- | --- |
| 尚需系统悬浮权限 | [日间](screenshots/light-overlay-permission-needed.png) | [夜间](screenshots/dark-overlay-permission-needed.png) |
| 已授权但入口关闭 | [日间](screenshots/light-overlay-authorized-off.png) | [夜间](screenshots/dark-overlay-authorized-off.png) |
| 等待测试目标 | [日间](screenshots/light-overlay-waiting-target.png) | [夜间](screenshots/dark-overlay-waiting-target.png) |
| 入口显示状态 | [日间](screenshots/light-overlay-visible-status.png) | [夜间](screenshots/dark-overlay-visible-status.png) |
| 窗口权限失败 | [日间](screenshots/light-overlay-failure-window_permission.png) | [夜间](screenshots/dark-overlay-failure-window_permission.png) |
| 窗口令牌失败 | [日间](screenshots/light-overlay-failure-window_token.png) | [夜间](screenshots/dark-overlay-failure-window_token.png) |
| 前台服务失败 | [日间](screenshots/light-overlay-failure-foreground_service.png) | [夜间](screenshots/dark-overlay-failure-foreground_service.png) |

## 真实系统悬浮窗口截图

以下 4 张来自已授权设备上的实际系统窗口，与前述 14 张 Compose 状态 fixture 分开。截图只记录相应瞬间；返回、隐藏和租约停止结论以上述真实设备断言为依据。

| 场景 | 日间 | 夜间 |
| --- | --- | --- |
| 收起的悬浮入口 | [日间](screenshots/overlay-day.png) | [夜间](screenshots/overlay-night.png) |
| 展开的返回／停止操作 | [日间](screenshots/overlay-day-expanded.png) | [夜间](screenshots/overlay-night-expanded.png) |
