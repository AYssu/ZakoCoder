# 只读子代理与任务预算界面证据

2026-11-06，23049RAD8C / Android 13 / ARM64；360dp、130% 字体，实际 Compose 界面截图。功能提交及 APK 摘要见 [screenshots.json](screenshots.json)，通过结果及受控对比见 [verification.json](verification.json)。后续证据提交仅归档图片与文档，不改变应用代码。

| 位置 / 状态 | 日间 | 夜间 |
| --- | --- | --- |
| 任务与自动分工，开关运行中锁定及任务用量 | [日间](agent-settings-day.png) | [夜间](agent-settings-night.png) |
| 两个助手实际运行，展开对话工具卡的进度 | [日间](delegation-running-day.png) | [夜间](delegation-running-night.png) |
| 请求诊断，区分两个助手的请求 | [日间](delegation-requests-day.png) | [夜间](delegation-requests-night.png) |
| 常用服务预设，区分两个 OpenAI 协议 | [日间](provider-presets-day.png) | [夜间](provider-presets-night.png) |
| 选择预设后显示协议、地址与默认兼容配置 | [日间](protocol-defaults-day.png) | [夜间](protocol-defaults-night.png) |
| 高级兼容设置，默认配置恢复说明 | [日间](compatibility-help-day.png) | [夜间](compatibility-help-night.png) |

来源：`AiAgentSettingsUiDeviceTest`、`AiDelegationEvidenceUiDeviceTest`、`AiProtocolUiDeviceTest`、`AiServiceOptionsUiDeviceTest`。全部使用隔离临时目录、合成配置及本地 TLS 模型响应；未调用真实付费服务，未展示个人会话、项目或密钥。

运行态截图来自实际 `AiController`：主代理分派 scout/reviewer，两个助手分别真实读取临时 answer.txt；等待两个延迟响应时，测试展开既有 `AiToolCard`、展示既有 `AiRequestsPanel`，随后断言父任务完成、共 6 次模型请求及 3 次工具调用。这些是既有组件的验收截图，不表示已实现独立子代理面板。

已复核窄屏、大字体、内容安全区、开关锁定及组件内滚动。新增开关和进度展示没有键盘输入；本轮未单独扩展模型编辑页的键盘遮挡验收，不能由这些组件截图推断完整应用的所有键盘场景。

复测：`ANDROID_HOME=/path/to/sdk python3 scripts/test_agent_optimizations.py --serial DEVICE_SERIAL --build`。完整原始日志在本地忽略目录 `build/agent-validation/pr-final-arm64-run/`，公开归档仅保留不含设备序列号与个人路径的摘要。
