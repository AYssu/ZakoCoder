# 按需语言服务与 HTTP 地址验证

验证日期：2026-11-01 至 2026-11-02。范围为本次实现，不表示已发布。

## 实际行为

- 编辑器语言服务默认关闭，升级前没有该选项的设备也默认关闭。用户从底部 LSP 入口开启后，才检测与连接当前文档对应服务；关闭、切换文件、离开编辑器释放原连接。设置单独保存，不被普通环境设置的旧快照覆盖。
- 新增 Java、Kotlin、C/C++、Go、Rust、Dart 可选工具包。仅安装不自动开启；Dart 复用 Flutter SDK，Kotlin 当前仅支持 Ubuntu ARM64。
- 修正 Sora 初始化能力和工作区名称兼容、文档同步范围及旧诊断位置处理。支持服务器声明的拉取诊断；失败与无变化响应均受三秒请求间隔约束。
- 模型和 MCP 地址均允许 HTTP／HTTPS 域名、IPv4／IPv6 及可选端口。保留 URL 格式、端口、嵌入凭据校验和跨源认证保护，HTTP 不加密。

## 验证

`runtimeVerification=true` 的独立 Debug 测试应用，Android 13 ARM64；不覆盖正式应用的数据和模型配置。

| 检查 | 结果 |
| --- | --- |
| 仓库结构、XML、文档链接及忽略规则；git diff --check | 通过 |
| assembleDebug、assembleDebugAndroidTest、lintDebug | 通过 |
| 定向 JVM 测试 | 33 项通过：LSP 注册、安装安全、进程 PID、JDK 选择恢复、AI 和 MCP 地址策略等 |
| 最终真机批次 | 28 项通过：开关与持久化、日夜界面、AI HTTP、MCP 导入与连接、Kotlin／Rust 真实服务 |
| 补充真机批次 | 17 项通过：MCP 错误界面与 LSP 帧协议回归 |
| 其他新增语言真实服务 | 本阶段已验证 Java、C、C++、Go、Dart 的初始化、未保存诊断、真实补全、修复后清空及释放连接 |

Kotlin 同样验证未保存诊断、真实补全、修复后清空与释放，但使用离线导入的最小 JVM 工作区；没有构建系统的独立 Kotlin 文件只初始化成功，不能宣称有完整语义支持。未覆盖真实 Gradle／Android 工程导入、多设备和全部依赖组合。

HTTP 公网域名与 IPv6 由格式校验及 Android 明文策略测试覆盖，实际传输使用本机隔离 HTTP 服务；没有连接真实公网明文供应商。MCP 仍验证拒绝重定向、跨源旧 SSE 投递地址和敏感内容脱敏。

测试期间临时放行独立测试包的 MIUI 后台 Activity 启动，完成后恢复原来的 ignore；未更改正式包设置。

## 截图

使用生产 Compose 组件与应用配色。工具包列表使用明确的“未检测”状态，状态面板使用 Idle，不把界面夹具当成运行成功证据。日夜组件截图不替代全应用导航或多设备验收；系统状态栏沿用测试 Activity 配置。

| 页面 | 日间 | 夜间 |
| --- | --- | --- |
| 语言服务工具包 | [列表](multilsp-day.png) · [下半部](multilsp-day-2.png) | [列表](multilsp-night.png) · [下半部](multilsp-night-2.png) |
| 默认关闭 | [关闭详情](multilsp-day-off-sheet.png) | [关闭详情](multilsp-night-off-sheet.png) |
| 手动开启 | [开启详情](multilsp-day-on-sheet.png) | [开启详情](multilsp-night-on-sheet.png) |
| MCP 地址错误 | [格式错误](mcp-light-import-Url.png) | [格式错误](mcp-dark-import-Url.png) |
| MCP JSON 错误 | [JSON 错误](mcp-light-import-JsonSyntax.png) | [JSON 错误](mcp-dark-import-JsonSyntax.png) |
| MCP 连接错误 | [HTTP 401](mcp-light-connection-401.png) | [HTTP 401](mcp-dark-connection-401.png) |
