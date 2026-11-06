# Responses 缓存标识默认值验证

2026-11-05，POCO F5 / Android 13 / ARM64。截图来自实际原生兼容设置组件，使用内存测试配置；360dp 宽度、130% 字体。未展示密钥、真实项目或私人会话。

| 状态 | 日间 | 夜间 |
| --- | --- | --- |
| 默认开启发送稳定缓存标识 | [日间](service-options-Responses-default-day.png) | [夜间](service-options-Responses-default-night.png) |
| 切换到手动配置后关闭 | [日间](service-options-Responses-day.png) | [夜间](service-options-Responses-night.png) |

`AiServiceOptionsUiDeviceTest` 真实点击预设/手动切换，断言默认开启、用户点击后关闭，并分别保存亮暗截图。内容可滚动；验证范围是该组件的窄屏、大字体、开关和安全区，不包括完整模型编辑页的键盘流程。截图元数据与 SHA-256 见 [screenshots.json](screenshots.json)。

相关配置和传输设备测试验证：缺少缓存设置时使用新默认值、明确保存的关闭值继续保留、同一项目/会话标识保持稳定、不同项目或会话不同、关闭后不发送字段、无会话的连接探针不发送字段、`store=false` 保持不变。其他协议不因此增加 Responses 字段。

## 真实服务对照

Issue #64 的真实服务实验使用相同公开固定文本，每组内请求正文散列完全一致。每种模式连续请求三次，模型为 `gpt-6.1-sol`。所有请求成功；测试不会修改已有模型配置或用户项目。

| 设置 | 输入 Token / 次 | 三次缓存 Token |
| --- | ---: | --- |
| Responses 不带固定缓存标识 | 17,457 | 0 / 0 / 0 |
| Chat Completions | 13,245 | 未提供 / 13,056 / 13,056 |
| Responses 带固定缓存标识 | 17,457 | 0 / 17,280 / 17,280 |

Responses 缓存数直接来自 `usage.input_tokens_details.cached_tokens`，应用解析值与服务原始值一致；未提供计数不视为零。该结果只说明本次服务上的重复请求对照，不保证其他服务、模型或任务的缓存命中及费用。未取得服务端路由日志，不断言具体后台机制。

`AiResponsesCacheLiveDeviceTest` 必须显式传入 `allowRealModelApi=true` 才会调用设备当前配置的付费模型；CI 默认跳过。仅在主应用包且现有工作空闲时运行，导出计数和公开请求散列，不导出配置凭据、服务地址、请求头或响应正文。

本次实际验证：应用 551 项、终端 145 项 JVM 测试通过；6 项配置/传输/日夜组件设备测试通过，补拍默认开启截图后再跑 2 项日夜用例通过。Debug APK / AndroidTest 构建、Lint 与仓库检查通过。真实对照为 1 项测试、9 次请求通过，不将组件截图作为商业服务执行证据。
