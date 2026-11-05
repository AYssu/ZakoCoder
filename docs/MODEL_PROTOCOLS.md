# 模型协议与常用服务

模型配置将「请求协议」「服务地址」「模型」分别保存。选择服务预设只填写地址与协议，不填写模型或凭据，不会自动连接、保存或猜测模型能力。已有配置默认为 Chat Completions；切换协议不删除历史或参数，不能兼容的参数会在表单与请求层明确拒绝。

## 四类通用协议

| 协议 | 相对于基础地址的生成路径 | 认证 | 工具及续接 |
| --- | --- | --- | --- |
| Chat Completions | `/chat/completions` | Bearer | `tool_calls` / `tool`；保留服务公开返回的思考及原协议的 `reasoning_details`。 |
| Responses | `/responses` | Bearer | `function_call` / `function_call_output`；完整输出项和加密 reasoning 在本机历史中保留。 |
| Anthropic Messages | `/messages` | `x-api-key`、`anthropic-version` | `tool_use` / `tool_result`；保留原始 thinking、签名及 redacted thinking 内容块。 |
| Gemini GenerateContent | `/models/{model}:streamGenerateContent?alt=sse`；非流式为 `:generateContent` | `x-goog-api-key` | `functionCall` / `functionResponse`；保留原始 parts、thoughtSignature 及原生调用 ID 对应关系。 |

模型地址支持 HTTP / HTTPS 域名或 IP，可指定端口，不限制本机、内网或公网，也不通过 DNS 解析判断地址位置。地址必须具有有效主机和端口，不允许 URL 内嵌凭据、查询参数或片段。HTTP 连接不加密。切换协议、主机或有效端口必须重新填写或清除原密钥和自定义请求头；所有协议均禁止重定向。Gemini 的密钥放在请求头，不放 URL；模型可填写普通 ID 或 `models/` 前缀 ID，拒绝其他路径结构。基础地址不包含生成路径；Chat、Responses、Messages 与 `/models` 的常见完整路径会在保存时归一化。支持自定义兼容网关，但必须选择网关实际提供的协议。错误时不会擅自切换协议或发送到其他供应商。

模型发现分别读取 OpenAI / Anthropic 的 `data[].id` 或 Gemini 的 `models[].name`。原生 Gemini 过滤不支持 generateContent 的模型；Anthropic、Gemini 按协议分页，有页数与总项数限制。没有列表权限或服务不提供列表时，可以手填模型。

## 流、工具与日志

四类协议共用现有请求归属、权限审批、取消、代理、重试及本机日志。Responses 必须收到 `response.completed`，Anthropic 必须收到完整 `message_stop`，Gemini 必须收到成功的 `STOP`；截断、未闭合工具参数和安全阻断不会变成可执行工具。未知的服务内置工具明确失败，不冒充应用内工具。暂态重试只替换尚未完成的模型请求，不重放已经执行的工具。

Responses 分别处理公开的 `reasoning_text` 和 `reasoning_summary_text`，按各自索引核对增量和最终快照；同一思考项只展示首个非空通道，避免正文与摘要重复。生命周期、结构通知和已支持的元信息事件不会结束任务；未知输出或工具语义仍明确拒绝。兼容工具事件只带 `call_id` 或输出索引的情况，必须唯一定位；显式身份矛盾、重复调用和参数不一致仍失败。只有 `call_id` 的工具输出项可以原样续接，不伪造服务端 ID。

Chat 优先使用 `reasoning_content`，缺省时兼容 `reasoning` 文本字段。`reasoning_details` 按索引合并并保留在原协议的本机会话中，受条数和容量限制；签名或加密内容不转成普通正文，不进入请求详情日志。切换协议时不发送该元数据，也不修改历史原件。用量字段优先使用协议标准字段，再读取已支持的兼容别名；显式零值保留，未提供保持未知。

Responses 使用 `store: false` 和完整本机历史，不依赖 `previous_response_id`。各原生协议的签名或加密思考仅用于原协议续接，历史恢复后仍可保留；切换到其他协议时只发送可转换的公开正文与工具结果，移除专属元数据。上下文压缩仍是有损过程，不能保证保留被压缩轮次的原始思考或签名。

界面只展示服务明确公开的思考或摘要，不解密或编造隐藏推理。诊断移除加密 reasoning、签名、redacted blobs 和图片编码；它们不进入请求详情预览。原始协议历史保存在应用私有目录，属于会话数据。用量使用服务返回字段：Anthropic 的输入包含新输入、缓存创建及缓存读取；Gemini 的输出包含候选输出及返回的思考 Token；缺字段保持未知，不用字符数补造计费用量。

## 参数差异

- Chat Completions 保留原有显式参数行为，具体支持由服务决定；DeepSeek 保留专有推理续接和探测兼容。
- Responses 不接受 seed、frequency_penalty、presence_penalty；关闭思考不能同时指定推理强度。
- Anthropic 默认输出预算为 8192 Token；显式启用手动思考时预算为 `min(4096, max_tokens - 1)`，且至少 1024。此模式不与 temperature、top_p 或强制工具并用；探测在启用思考时使用自动工具选择，仍检查实际返回的探针调用。温度上限 1，不接受 seed 或两种 penalty；关闭思考不能同时指定 effort。支持的 effort 是否适用于所选模型由服务决定。
- Gemini 映射原生 generationConfig，包含 seed 和两种 penalty；当前推理强度支持低／中／高，Max 不猜测映射。关闭思考使用原生 budget 0，模型不支持关闭时由服务明确拒绝。当前未启用增量 functionCall 参数选项，未知部分参数事件明确拒绝。

## 服务兼容与缓存

模型配置的连接区域默认展示「按服务预设配置」，底层参数折叠在「高级兼容设置」，仅在连接异常或服务商要求时手动调整。新建配置启用预设；旧配置缺少 `usePreset` 时保留手动模式及全部原值，不自动改写密钥、上下文或历史。切换模型或协议保留设置，实际请求只应用当前协议对应的选项。

预设按完整的已知服务地址和协议匹配，不根据模型名或密钥猜测。官方 DeepSeek 使用 `thinking`、通义兼容接口使用 `enable_thinking`、OpenRouter 使用 `reasoning`；官方 OpenAI Chat 使用 `max_completion_tokens` 和独立的 `reasoning_effort`，不发送独立思考开关。其他 Chat 地址使用通用预设，不发送额外思考控制参数；自定义中转需要这些参数时应按服务说明手动调整。预设保留用户填写的输出上限、采样值和推理强度，但是否发送受所选格式控制；预设不是能力探测，也不保证所有模型兼容。自动模式默认启用 Responses 稳定缓存标识，Anthropic 主动缓存仍默认关闭；其他兼容字段按预设计算，切回手动时以当前预设值作为可编辑起点。

- 四类协议均可显式关闭流式，接收完整 JSON。完整响应仍须通过完成状态、工具身份、参数和容量校验；损坏的 SSE 不会自动切换模式或协议。非流式需收到并验证完整响应后才向会话交付内容。
- 「发送采样参数」关闭时省略 `temperature` 和 `top_p`，保留表单原值。Chat 另可关闭 `stream_options` 用量请求、选择 `max_tokens`／`max_completion_tokens`，以及 `thinking` 对象、`enable_thinking` 开关、`reasoning` 对象或不发送思考参数。
- Responses 可以分别关闭思考摘要与加密推理请求。新配置、缺少缓存标识设置的配置和自动预设默认发送稳定缓存标识；已保存的手动关闭值继续保留。旧配置若保存了关闭值，可在「高级兼容设置」中开启「发送稳定缓存标识」，或切换到服务预设。开启后，同一会话的普通请求使用同一散列标识，压缩请求使用独立后缀；标识不含项目路径、消息正文或密钥。新会话使用不同标识，重试保持不变。连接测试没有项目会话，不附加此标识。该选项只提供缓存路由提示，不保证命中，也不会开启服务端会话保存（`store` 仍为 `false`）；服务不接受该字段时，可切换手动兼容设置并关闭。
- Anthropic 可选择关闭、5 分钟或 1 小时提示缓存，通过显式 `cache_control` 请求自动缓存。该选项不改变本机会话的保存时长。

这些选项须按服务能力选择；兼容网关不一定接受全部字段。主动缓存仅是请求意图，不保证命中、缓存时长或费用，实际用量仍以服务返回为准。关闭加密推理请求不会删除历史已保存的原协议数据。

连接测试分别显示连接成功响应和工具调用格式验证结果。HTTP 成功但未返回预期探针调用时，仅连接通过，不能宣称工具可用。测试不验证思考开关、推理强度或输出上限是否实际生效；即使返回公开思考内容也不能据此宣称控制参数生效。测试说明适用于流式和完整 JSON，不会增加额外商业模型请求。

## 模型上下文参考值

上下文窗口预设和新选择模型的默认值共用本机已知模型表：DeepSeek Flash／V4 Pro 与 Gemini 4 Argon 为 1,000,000 Token，GPT 6.1 Sol／Terra、GPT 6 Astra／Luna 为 1,050,000 Token，Grok 4.7 为 500,000 Token。只匹配表中完整模型 ID，允许大小写、首尾空白和 `provider/model` 路由前缀；未知子型号、日期后缀或未来版本不会沿用相似名字的数值，未知模型默认 128,000 Token。

这些是配置参考，不是网关实际配额的探测结果；用户应按服务提供的限制调整。共享表不会迁移已保存配置，也不会覆盖用户手填的窗口值。

## 常用预设与边界

提供 OpenAI Responses、OpenAI Chat、Anthropic Claude、Google Gemini、DeepSeek、通义、硅基流动、Kimi、智谱、OpenRouter、xAI 共 11 个入口。API Key 由用户自行配置，跨来源保留凭据仍受既有保护；预设不是认证配置，也不代表每个模型、网关、付费套餐都已实测。

本轮不包含 Codex OAuth、浏览器登录、OpenCode 多协议自动路由、Ollama 原生 `/api/chat`、手机本地模型引擎。这些与四类通用云端协议是不同需求。

实现采用独立 Kotlin 编解码，没有新增供应商 SDK。协议依据：

- [OpenAI Responses 迁移](https://developers.openai.com/api/docs/guides/migrate-to-responses)、[函数调用](https://developers.openai.com/api/docs/guides/function-calling)、[推理上下文](https://developers.openai.com/api/docs/guides/reasoning)。
- [Anthropic Messages](https://docs.anthropic.com/en/api/messages)、[官方 TypeScript SDK Messages schema](https://github.com/anthropics/anthropic-sdk-typescript/blob/main/src/resources/messages/messages.ts)。
- [Gemini GenerateContent](https://ai.google.dev/api/generate-content)、[函数调用](https://ai.google.dev/gemini-api/docs/function-calling)、[thought signatures](https://ai.google.dev/gemini-api/docs/thought-signatures)。

各供应商地址依据在 `AiProviderPresets.kt` 逐项记录。设备回归使用本地 HTTPS 协议夹具，覆盖四类协议、失败用量记录和历史续接。当前设备保存的 Responses 服务已通过流式与非流式连接及工具探针测试；其他真实商业服务、视觉理解与不同模型的参数支持仍需对应账号验收，不能由夹具结果替代。
