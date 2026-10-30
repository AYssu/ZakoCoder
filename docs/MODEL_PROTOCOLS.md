# 模型协议与常用服务

模型配置将「请求协议」「服务地址」「模型」分别保存。选择服务预设只填写地址与协议，不填写模型或凭据，不会自动连接、保存或猜测模型能力。已有配置默认为 Chat Completions；切换协议不删除历史或参数，不能兼容的参数会在表单与请求层明确拒绝。

## 四类通用协议

| 协议 | 相对于基础地址的生成路径 | 认证 | 工具及续接 |
| --- | --- | --- | --- |
| Chat Completions | `/chat/completions` | Bearer | `tool_calls` / `tool`；保留服务公开返回的 `reasoning_content`。 |
| Responses | `/responses` | Bearer | `function_call` / `function_call_output`；完整输出项和加密 reasoning 在本机历史中保留。 |
| Anthropic Messages | `/messages` | `x-api-key`、`anthropic-version` | `tool_use` / `tool_result`；保留原始 thinking、签名及 redacted thinking 内容块。 |
| Gemini GenerateContent | `/models/{model}:streamGenerateContent?alt=sse` | `x-goog-api-key` | `functionCall` / `functionResponse`；保留原始 parts、thoughtSignature 及原生调用 ID 对应关系。 |

所有模型地址仍要求 HTTPS，禁止重定向。Gemini 的密钥放在请求头，不放 URL；模型可填写普通 ID 或 `models/` 前缀 ID，拒绝其他路径结构。基础地址不包含生成路径；Chat、Responses、Messages 与 `/models` 的常见完整路径会在保存时归一化。支持自定义兼容网关，但必须选择网关实际提供的协议。错误时不会擅自切换协议或发送到其他供应商。

模型发现分别读取 OpenAI / Anthropic 的 `data[].id` 或 Gemini 的 `models[].name`。原生 Gemini 过滤不支持 generateContent 的模型；Anthropic、Gemini 按协议分页，有页数与总项数限制。没有列表权限或服务不提供列表时，可以手填模型。

## 流、工具与日志

四类协议共用现有请求归属、权限审批、取消、代理、重试及本机日志。Responses 必须收到 `response.completed`，Anthropic 必须收到完整 `message_stop`，Gemini 必须收到成功的 `STOP`；截断、未闭合工具参数和安全阻断不会变成可执行工具。未知的服务内置工具明确失败，不冒充应用内工具。暂态重试只替换尚未完成的模型请求，不重放已经执行的工具。

Responses 使用 `store: false` 和完整本机历史，不依赖 `previous_response_id`。各原生协议的签名或加密思考仅用于原协议续接，历史恢复后仍可保留；切换到其他协议时只发送可转换的公开正文与工具结果，移除专属元数据。上下文压缩仍是有损过程，不能保证保留被压缩轮次的原始思考或签名。

界面只展示服务明确公开的思考或摘要，不解密或编造隐藏推理。诊断移除加密 reasoning、签名、redacted blobs 和图片编码；它们不进入请求详情预览。原始协议历史保存在应用私有目录，属于会话数据。用量使用服务返回字段：Anthropic 的输入包含新输入、缓存创建及缓存读取；Gemini 的输出包含候选输出及返回的思考 Token；缺字段保持未知，不用字符数补造计费用量。

## 参数差异

- Chat Completions 保留原有显式参数行为，具体支持由服务决定；DeepSeek 保留专有推理续接和探测兼容。
- Responses 不接受 seed、frequency_penalty、presence_penalty；关闭思考不能同时指定推理强度。
- Anthropic 默认输出预算为 8192 Token；显式启用手动思考时预算为 `min(4096, max_tokens - 1)`，且至少 1024。此模式不与 temperature、top_p 或强制工具并用；探测在启用思考时使用自动工具选择，仍检查实际返回的探针调用。温度上限 1，不接受 seed 或两种 penalty；关闭思考不能同时指定 effort。支持的 effort 是否适用于所选模型由服务决定。
- Gemini 映射原生 generationConfig，包含 seed 和两种 penalty；当前推理强度支持低／中／高，Max 不猜测映射。关闭思考使用原生 budget 0，模型不支持关闭时由服务明确拒绝。当前未启用增量 functionCall 参数选项，未知部分参数事件明确拒绝。

## 常用预设与边界

提供 OpenAI Responses、OpenAI Chat、Anthropic Claude、Google Gemini、DeepSeek、通义、硅基流动、Kimi、智谱、OpenRouter、xAI 共 11 个入口。API Key 由用户自行配置，跨来源保留凭据仍受既有保护；预设不是认证配置，也不代表每个模型、网关、付费套餐都已实测。

本轮不包含 Codex OAuth、浏览器登录、OpenCode 多协议自动路由、Ollama 原生 `/api/chat`、手机本地模型引擎或局域网明文模型连接。这些与四类通用云端协议是不同需求。

实现采用独立 Kotlin 编解码，参考公开协议和本地 Operit 类型划分，没有复制其产品实现或新增 SDK。协议依据：

- [OpenAI Responses 迁移](https://developers.openai.com/api/docs/guides/migrate-to-responses)、[函数调用](https://developers.openai.com/api/docs/guides/function-calling)、[推理上下文](https://developers.openai.com/api/docs/guides/reasoning)。
- [Anthropic Messages](https://docs.anthropic.com/en/api/messages)、[官方 TypeScript SDK Messages schema](https://github.com/anthropics/anthropic-sdk-typescript/blob/main/src/resources/messages/messages.ts)。
- [Gemini GenerateContent](https://ai.google.dev/api/generate-content)、[函数调用](https://ai.google.dev/gemini-api/docs/function-calling)、[thought signatures](https://ai.google.dev/gemini-api/docs/thought-signatures)。

各供应商地址依据在 `AiProviderPresets.kt` 逐项记录。设备测试使用本地 HTTPS 协议夹具；真实商业服务、视觉理解与不同模型的参数支持需要对应账号验收，不能由夹具结果替代。
