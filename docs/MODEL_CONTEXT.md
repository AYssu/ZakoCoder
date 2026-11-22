# 模型上下文与容量

在模型编辑表单的“思考、上下文与高级配置”中设置上下文窗口。可以选择常用容量、按具体模型填入参考窗口，或手动输入。窗口设置只管理应用本地预算，不会更换当前模型、服务地址或凭据，也不会让网关获得它不支持的容量。

## 输入预算与压缩

- 窗口以 Token 计，支持 8,192–2,097,152，未收录模型默认 1,000,000（1M），已识别型号使用其推荐值；已保存的手动值保留。
- 可用输入预算 = 上下文窗口 − 输出预留。显式最大输出 Token 用作预留；未填写时预留窗口的四分之一与 8,192 中较小者。预留是本地预算，未填写最大输出时不会额外发送 `max_tokens`。
- 自动压缩默认在输入估算超过可用输入预算的 90% 时触发，可调整为 50–95%。例如默认配置预留 8,192，可用输入为 991,808 Token，默认压缩线约 892,627 Token。
- 检查发生在下一次请求之前，不按固定对话轮数、消息数或旧 96k 字符限制提前总结；一轮完成也不会单独触发隐藏总结请求。
- 关闭自动压缩后，超过输入预算会停止并保留已有进度。输出预留大于或等于窗口时会提示配置错误。

总结由当前模型生成，会产生真实请求与用量。总结有损，保留当前需求与最近完整工具组；不产生新授权，不证明操作成功，也不自动重放副作用。总结失败或压缩后仍无法容纳当前需求、规则与工具定义时会停止。总结长度仍按字符限制，范围 2,000–12,000，默认 6,000；这不是总结的 Token 数。

## 估算与计费用量

上下文展示“约 … Token”，是应用本地估算。预算包含系统说明、历史、当前任务、项目规则／Skill、工具定义及协议开销；每个图片引用另加 4,096 Token 的本地预留。估算按 ASCII 约三字符一个 Token、非 ASCII 码点约两个 Token 计算，不调用提供商专用 tokenizer，不是任何模型的精确分词结果；视觉预留也不代表实际视觉费用。

请求详情中的输入、输出、缓存与每轮汇总只使用服务返回的 `usage`。服务未返回时显示未知，不用上下文估算补造计费统计。实时字符／字节曲线仍明确标注单位。提供商分词、图片算法、隐藏协议开销与网关限制可能不同，因此本地判断能容纳不保证服务一定接受。

## 具体模型参考窗口

自动识别表已于 2026-11-22 按官方资料核对。新增或切换模型时，确切 Model ID 命中下表即带入对应窗口；支持大小写不同、外层空白及 `openai/gpt-6.2-sol` 这类斜杠分隔的供应商路由。不会按品牌前缀、相似名称或任意日期后缀推断容量。未命中继续使用 1,000,000 Token 回退。

| 厂商 | 精确 Model ID（同一行容量相同） | Token 窗口 | 官方来源 |
| --- | --- | ---: | --- |
| OpenAI | `gpt-6.2-sol`、`gpt-6.2-astra` | 1,050,000 | [Sol](https://developers.openai.com/api/docs/models/gpt-6.2-sol) · [Astra](https://developers.openai.com/api/docs/models/gpt-6.2-astra) |
| OpenAI | `gpt-6.1-sol`、`gpt-6.1-terra`、`gpt-6-astra`、`gpt-6-luna` | 1,050,000 | [模型目录](https://developers.openai.com/api/docs/models)及各型号说明；保留既有参考 |
| DeepSeek | `deepseek-flash`、`deepseek-v4-pro`、`deepseek-v4-flash`、`deepseek-v4-flash-vision-exp` | 1,000,000 | [模型与价格](https://api-docs.deepseek.com/quick_start/pricing/)；后两个为官方仍接受的旧别名 |
| Google | `gemini-4-carbon`、`gemini-4-neon`、`gemini-4-hydrogen`、`gemini-4-argon` | 1,048,576 | [Carbon](https://aistudio.google.com/docs/models/gemini-4-carbon) · [Neon](https://aistudio.google.com/docs/models/gemini-4-neon) · [Hydrogen](https://aistudio.google.com/docs/models/gemini-4-hydrogen) · [Argon](https://aistudio.google.com/docs/models/gemini-4-argon) |
| Google | `gemini-3.8-flash`、`gemini-3.6-flash`、`gemini-3.5-flash-lite`、`gemini-3.1-flash-lite` | 1,048,576 | [3.8 Flash](https://aistudio.google.com/docs/models/gemini-3.8-flash)及对应型号说明 |
| Anthropic | `claude-fable-5-2`、`claude-fable-5-1`、`claude-fable-5`、`claude-opus-5-5`、`claude-opus-5`、`claude-sonnet-5-5`、`claude-sonnet-5`、`claude-haiku-5-5` | 1,000,000 | [模型总表](https://platform.claude.com/docs/en/models/overview)及对应型号说明 |
| Anthropic | `claude-opus-4-8`、`claude-opus-4-7`、`claude-opus-4-6`、`claude-sonnet-4-6` | 1,000,000 | [模型总表](https://platform.claude.com/docs/en/models/overview) |
| Anthropic | `claude-opus-4-5`、`claude-sonnet-4-5`、`claude-haiku-4-5`、`claude-sonnet-4-5-20250929`、`claude-haiku-4-5-20251001` | 200,000 | [模型总表](https://platform.claude.com/docs/en/models/overview) · [模型 ID 与版本](https://platform.claude.com/docs/en/about-claude/models/model-ids-and-versions) |
| xAI | `grok-4.7`、`grok-4.6`、`grok-4.5`、`grok-4.5-latest`、`grok-build-latest` | 500,000 | [4.7](https://docs.x.ai/developers/grok-4-7) · [4.6](https://docs.x.ai/developers/models/grok-4.6) · [4.5 及别名](https://docs.x.ai/developers/models/grok-4.5) |
| xAI | `grok-4.3`、`grok-4.3-latest` | 1,000,000 | [4.3 及别名](https://docs.x.ai/developers/models/grok-4.3) |
| Z.AI | `glm-5.3`、`glm-5.3-flash`、`glm-5.3-flashx`、`glm-5.3-flashx-legal`、`glm-5.2` | 1,000,000 | [模型总表](https://docs.z.ai/guides/overview/overview) · [Flash / FlashX](https://docs.z.ai/guides/vlm/glm-5.3-flash) |
| Z.AI | `glm-5.1`、`glm-5`、`glm-4.7`、`glm-4.6` | 200,000 | [模型总表](https://docs.z.ai/guides/overview/overview) |
| Qwen | `qwen4-max`、`qwen3.8-flash`、`qwen3.7-plus`、`qwen3.7-plus-2026-05-26`、`qwen3.6-plus` | 1,000,000 | [Qwen4 Max](https://www.alibabacloud.com/help/en/model-studio/qwen4-max) · [3.8 Flash](https://www.alibabacloud.com/help/en/model-studio/qwen3-8-flash) · [3.7 Plus 及快照](https://www.alibabacloud.com/help/en/model-studio/qwen3-7-plus) · [模型总表](https://docs.qwencloud.com/developer-guides/getting-started/text-generation-models) |
| Kimi | `kimi-k3.1-pro`、`kimi-k3.1-pro-highspeed`、`kimi-k3` | 1,048,576 | [模型列表](https://platform.kimi.com/docs/models) · [上下文说明](https://platform.kimi.com/docs/pricing/chat) |
| Kimi | `kimi-k2.7-code`、`kimi-k2.7-code-highspeed`、`kimi-k2.6` | 262,144 | [模型列表](https://platform.kimi.com/docs/models) · [上下文说明](https://platform.kimi.com/docs/pricing/chat)；仅为仍使用这些型号的已有配置识别容量 |
| MiniMax | `MiniMax-M3.1-Flash`、`MiniMax-M3` | 1,000,000 | [文本模型表](https://platform.minimax.io/docs/models/text/models-intro) |

以上是本地容量参考，不代表同品牌所有型号、账户权限或网关均支持此容量，也不自动添加供应商的可调用模型。Gemini 4 的共享上下文与 Gemini 3.x 的输入上限均按表中数值保守扣除输出预留。已保存配置不因识别表更新被静默重写；重新打开或重选相同模型仍保留手动值，切换至另一模型才重新带入推荐窗口。

界面的“按模型选择”与自动识别使用同一份完整目录，按厂商分组并可滚动；每行显示确切 Model ID 和精确 Token 容量，官方别名与快照分别列出，不再维护单独的八项列表。常用容量入口独立保留。选择任何一项都只更新本地容量，不会切换已配置的模型。

**协议边界：** GPT-6.2 Sol / Astra、GPT-6.1 Sol / Terra 的官方工具调用要求 Responses API；配置中可显式选择 Responses，支持原生流式与工具续接；容量参考不等于该模型或网关已完成真实服务验收。通过兼容网关使用时，应确认该网关实际支持当前协议及工具调用；容量预设不会自动修改模型、端点或协议；服务地址预设是另一独立入口，见[协议说明](MODEL_PROTOCOLS.md)。

界面容量缩写 K = 1,000，M = 1,000,000；具体模型预设同时显示完整数值。预设只填入窗口，不自动选择输出长度或请求参数。

## 旧配置与保留边界

旧配置没有 `contextWindowTokens` 时，原默认 `maxContextChars=96000` 继续映射为原有 128,000 Token，不重写历史预算；其他旧字符值按整数除以三估算，并限制在新窗口范围。已保存的自动压缩开关、总结长度及压缩阈值保持不变，因此旧 80% 不会被静默改为 90%。已存在的新 Token 字段优先。缺失 Token 及旧字符预算字段的新配置才使用 1M 回退。旧字符字段仍作为兼容元数据保存，不再触发运行时压缩。

大窗口不会取消设备资源边界：

| 本地对象 | 独立上限 |
| --- | --- |
| 最终 UTF-8 请求体 | 32 MiB，包括实际图片编码；编码前消息与工具也接受同一安全上限检查 |
| 单会话 JSON 文件 | 64 MiB |
| 连续协议上下文 | 10,000 轮；每轮 100,000 条消息；合计 24 × 1024 × 1024 个 UTF-16 码元 |
| 时间线及工具记录 | 沿用[产品范围](PRODUCT.md)的有界保留规则，与完整协议上下文不同 |

达到设备安全容量会明确报告本地限制，不伪装成模型窗口不足，也不为了绕过限制无限分配内存。较大历史写出后不保证旧版应用能够读取，降级前应保留备份。此文档描述实现约束，实际测试结果以对应验收记录为准。
