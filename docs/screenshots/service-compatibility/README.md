# 服务兼容设置截图

设备：POCO F5，Android 13，ARM64。测试使用内存配置，宽度 360dp、字体 130%，不包含密钥或用户会话。

2026-11-05 更新：新配置默认使用服务预设，底层字段折叠在「高级兼容设置」。默认入口：[日间](service-options-ChatCompletions-preset-day.png)、[夜间](service-options-ChatCompletions-preset-night.png)。以下截图展示展开并切换为手动配置后的控件，较长页面已滚动到本次操作的位置。

| 协议 | 日间 | 夜间 |
| --- | --- | --- |
| Chat Completions | [日间](service-options-ChatCompletions-day.png) | [夜间](service-options-ChatCompletions-night.png) |
| Responses | [日间](service-options-Responses-day.png) | [夜间](service-options-Responses-night.png) |
| Anthropic | [日间](service-options-Anthropic-day.png) | [夜间](service-options-Anthropic-night.png) |
| Gemini | [日间](service-options-Gemini-day.png) | [夜间](service-options-Gemini-night.png) |

截图由 `AiServiceOptionsUiDeviceTest` 的实际原生组件生成。交互测试覆盖开关、选择、只读状态及窄屏滚动；截图不能证明完整模型编辑页、键盘或全部设备兼容性。

本次设备验证共 19 项通过：`AiServiceOptionsUiDeviceTest`、`AiServiceOptionsConfigDeviceTest`、`AiServiceTransportDeviceTest`、`AiModelProbeProgressDeviceTest`。包括默认隐藏底层字段、切换手动、分开显示连接与工具验证、思考设置未验证提示、旧字段兼容及预设的实际请求映射；网络用例使用本地 TLS 测试服务，没有调用商业模型。

测试结果区截图使用内存中的模拟状态，不代表真实服务能力；补图后单独复跑结果区用例通过：

| 测试状态 | 日间 | 夜间 |
| --- | --- | --- |
| 连接成功、工具未通过 | [日间](service-probe-failed-day.png) | [夜间](service-probe-failed-night.png) |
| 连接与工具通过、思考设置未验证 | [日间](service-probe-passed-day.png) | [夜间](service-probe-passed-night.png) |
