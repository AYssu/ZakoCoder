# 工作台体验改进验收

日期：2026-11-02。基线：`8f06746`。证据对应本 PR 的产品实现；截图使用隔离测试数据，不包含真实用户会话或凭据。

## 实际行为

- YOLO 按项目确认、保存并自动允许工具；能力包、系统授权、SSH 主机信任和屏幕目标边界继续有效。普通和高权限行为保留。
- 用量页增加按小时／日的请求、Token、工具调用趋势与模型分布；继承现有筛选。点击柱形或左右按钮定位时段；缺失用量不伪造。
- 项目库常驻搜索，突出当前项目、终端状态及文件入口；长名称和路径可换行。
- 供应商预设仅显示名称，协议及地址仍单独显示。MCP 独立凭证栏默认遮蔽，自动添加 Bearer 前缀，保留高级请求头；更换来源清理凭据。
- 设置新增应用更新：正式／Beta 渠道、自动／手动检查、提示、下载、取消、校验、系统安装和就绪状态恢复。开发版本明确提示不能比较版本。

## 检查与测试

- `python3 scripts/check_repository.py`、`git diff --check`：通过。
- Gradle `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`：通过；使用隔离包名及 Beta 版本参数进行设备验证。
- `:app:testDebugUnitTest`：353 项通过；`:terminal-emulator:testDebugUnitTest`：145 项通过。
- Android 13 ARM64 真机，合批执行后定向复测：35 项不同设备测试中，34 项通过，1 项按条件跳过，无剩余失败。跳过项是开发版本提示分支，当前安装的是具备 SemVer 的 Beta 测试包。
- 覆盖 YOLO 实际控制器审批、等待中策略切换、撤销、能力包边界、持久化与历史审批来源；普通／高权限及 MCP、屏幕选择回归。
- 覆盖 MCP 凭证编辑、清空、跨来源清理、表单重开；完整首页搜索、设置导航、用量筛选；图表点击与指标切换。
- 更新覆盖 HTTPS 实际流取消、摘要／大小错误清理、包名／签名／版本拒绝，以及同签名 APK 经本机 HTTPS 测试服务下载、Ready、控制器重建、FileProvider URI、就绪后篡改恢复可下载状态。
- 公开 `v0.1.0-beta.2` APK 在主机下载，SHA256SUMS 与 APK 签名校验通过。此项不等于手机公网下载验证。
- 系统安装手工检查：仅独立测试包，从 `0.1.0-beta.1` / `1001` 经应用更新入口进入来源授权页，测试授权后返回应用，自动恢复系统安装器；确认后 `dumpsys package` 显示 `0.1.0-beta.2` / `1002`。来源授权通过测试命令临时设置，未声称测试了各厂商授权开关的点击流程。安装来源与后台启动临时权限已恢复原值，未覆盖安装正式应用。

首次运行发现的 MCP 测试重开竞态、取消测试未消费网络异常，以及清理完成前断言的问题均已修正并定向复测。未以首次失败日志作为通过证据。

## 界面证据

40 张真实设备截图；完整页面及图表检查使用 360dp、130% 字体。完整首页和用量页通过实际应用导航打开，使用隔离项目与本机账本。其余组件状态夹具渲染生产组件，表中明确标注；它们不代表网络或安装已经成功。

| 位置／状态 | 日间 | 夜间 |
| --- | --- | --- |
| 完整项目库 | [查看](day-home-full.png) | [查看](night-home-full.png) |
| 完整项目搜索 | [查看](day-home-search.png) | [查看](night-home-search.png) |
| 完整用量面板 | [查看](day-usage-full.png) | [查看](night-usage-full.png) |
| 模型筛选 | [查看](day-usage-model-filter.png) | [查看](night-usage-model-filter.png) |
| 筛选结果 | [查看](day-usage-filtered.png) | [查看](night-usage-filtered.png) |
| 趋势：请求（组件夹具） | [查看](day-trend-requests.png) | [查看](night-trend-requests.png) |
| 趋势：Token（组件夹具） | [查看](day-trend-tokens.png) | [查看](night-trend-tokens.png) |
| 趋势：工具（组件夹具） | [查看](day-trend-tools.png) | [查看](night-trend-tools.png) |
| 模型分布（组件夹具） | [查看](day-model-distribution.png) | [查看](night-model-distribution.png) |
| 绑定项目长内容（组件夹具） | [查看](day-home-bound.png) | [查看](night-home-bound.png) |
| 副本项目卡片（组件夹具） | [查看](day-home-managed.png) | [查看](night-home-managed.png) |
| 权限预设 | [查看](permissions-day.png) | [查看](permissions-night.png) |
| YOLO 确认 | [查看](permissions-yolo-confirm-day.png) | [查看](permissions-yolo-confirm-night.png) |
| YOLO 生效 | [查看](permissions-yolo-enabled-day.png) | [查看](permissions-yolo-enabled-night.png) |
| MCP 凭证表单 | [查看](mcp-bearer-day.png) | [查看](mcp-bearer-night.png) |
| 供应商名称（列表组件夹具） | [查看](provider-names-day.png) | [查看](provider-names-night.png) |
| 发现更新（组件状态夹具） | [查看](updates-day-available.png) | [查看](updates-night-available.png) |
| 下载进度（组件状态夹具） | [查看](updates-day-downloading.png) | [查看](updates-night-downloading.png) |
| 就绪安装（组件状态夹具） | [查看](updates-day-ready.png) | [查看](updates-night-ready.png) |
| 下载失败（组件状态夹具） | [查看](updates-day-failed.png) | [查看](updates-night-failed.png) |

## 审查与限制

独立代码审查指出的失效就绪包无法重新下载、开发版本误报最新及设置切换丢失结果已修复；独立视觉复核后将非选中柱提高至 75% 主色，并补完整导航截图。完整夜间页面包含系统栏；部分单组件夹具的系统栏沿用设备主题，不代表完整夜间页面。

未覆盖多厂商安装器、Android 其他版本、横屏、TalkBack、超过 130% 字体、键盘弹出状态，以及手机公网 GitHub 下载不稳定情况。控制器重建证明状态可恢复，不能等同于全部进程死亡场景已验证。签名轮换与其他 CPU 架构更新不在本次范围。
