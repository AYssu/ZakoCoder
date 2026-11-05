# 助手移动布局截图

Android 13 ARM64 真机截图，包含日间与夜间主题。截图展示范围如下，静态画面不代表帧率或其他设备兼容性。

| 场景 | 日间 | 夜间 | 来源与范围 |
| --- | --- | --- | --- |
| 会话末尾、输入区与两行统计 | [日间](conversation-day.png) | [夜间](conversation-night.png) | 保留的本机测试会话；可核对左对齐、字号与消息动作位置。 |
| 流式输出中的动作显隐 | [日间](streaming-day.png) | [夜间](streaming-night.png) | 本机测试会话；当前输出未完成时不显示其复制与更多动作，历史动作保留。 |
| 侧栏导航 | [日间](drawer-day.png) | [夜间](drawer-night.png) | 真实应用页面；展示五个目的地、选中态和品牌承底。 |
| 实时工具参数 | [日间折叠](tool-preview-day.png) | [夜间展开](tool-preview-night.png) | 本地组件测试；没有执行图中的命令或写入文件。 |
| 请求与接收阶段 | [Thinking](thinking-day.png) | [接收回复](receiving-night.png) | 本地组件测试；静态图仅展示文字样式，扫光变化由对应设备测试验证。 |
| 文件变更汇总 | [日间](changes-card-day.png) | [夜间](changes-card-night.png) | 本地检查点组件测试，展示真实测试文件差异统计及增删比例。 |
| 文件变更列表 | [日间](changes-sheet-day.png) | [夜间](changes-sheet-night.png) | 同一组件的原生底部 Sheet，普通字重标题、路径及浅色增删统计。 |
| 文件行内差异 | [日间](changes-inline-diff-day.png) | [夜间](changes-inline-diff-night.png) | 展开同一测试文件，逐行红绿背景、增删符号及完整审阅入口。 |
| 问答等待选择 | [日间](question-waiting-day.png) | [夜间](question-waiting-night.png) | 本地组件测试，选项和自定义回答入口，未回答时禁用提交。 |
| 自定义回答 | [日间](question-custom-day.png) | [夜间](question-custom-night.png) | 本地组件测试，展开编辑、清空与提交；夜间字体比例 1.3。 |
| 已回答回执 | [日间](question-answered-day.png) | [夜间](question-answered-night.png) | 本地组件测试，默认折叠回执与继续任务入口。 |
| 展开回答详情 | [日间](question-answer-expanded-day.png) | [夜间](question-answer-expanded-night.png) | 本地组件测试，原问题和保存答案完整可读。 |
| 日志跟随输出 | [日间](log-following-day.png) | [夜间](log-following-night.png) | 本地组件测试，固定高度日志视口和跟随状态。 |
| 日志暂停跟随 | [日间](log-paused-day.png) | [夜间](log-paused-night.png) | 本地组件测试，手动上滑后暂停，可点击回到最新。 |

工具预览、阶段状态、文件变更和安装日志／提问截图使用组件测试数据，不作为真实模型或实际安装的执行证明。侧栏截图来自较早同一导航实现，背景会话字号不用于判断最终聊天排版。除上述界面证据外，不包含用户凭据、接口请求或参考项目研究材料。

本轮 UI 独立分支已完成 Debug／AndroidTest 构建、Lint 与 463 项单元测试；真机定向 UI 批次 10 项及日夜变更复拍各 1 项通过。截图仅覆盖所列状态，不将测试数据作为真实安装或付费模型执行记录。
