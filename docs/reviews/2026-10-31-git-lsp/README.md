# Git 与编辑器 LSP 界面验收截图

本归档对应随附提交，供界面变更审阅；截图不是对全部 Git 服务商、语言服务器或项目类型的兼容性承诺。

## 环境与来源

- Android 13 ARM64 真机；应用内容以 360dp 宽度、130% 字体比例分别呈现日间和夜间主题。
- Git 页面由 `GitWorkflowUiDeviceTest` 渲染实际产品 Compose 组件，仓库状态、分支、冲突和日志来自固定测试 fixture；不连接真实远端，也不修改用户仓库。长日志中的 `Bearer [REDACTED]` 是已脱敏的固定测试文本。
- 克隆及凭据页面由 `GitCredentialsUiDeviceTest` 渲染实际产品组件；使用独立测试存储、`example.invalid` 地址和虚构用户名、令牌占位值。令牌输入以密码样式展示，截图不包含用户凭据。
- LSP 各阶段页面由 `EditorLspUiDeviceTest` 使用固定状态 fixture 渲染，失败详情包含虚构错误和脱敏标记。
- `EditorLspIntegrationUiDeviceTest` 的日间、夜间两项真实集成用例使用临时 Python 文件和本机 Ubuntu 中的 Python 语言服务器，连接实际 `CodeEditorPage` 与 Sora `LspLanguage`；等待真实诊断到达并验证编辑器诊断容器，再分别拍摄编辑器和状态面板。临时文件含有意设置的未定义变量，非用户项目。
- 键盘截图保留实际 IME；测试滚动到完整的克隆／保存按钮，断言按钮未裁切且处于 IME 上方。长日志截图断言可见日志区域及固定暂存／提交底栏互不覆盖。
- 系统状态栏、导航栏和输入法由测试设备提供，可能保持系统自身配色。截图不含真实用户项目、会话或密钥。

## 审阅结论与范围

日夜截图已人工检查，未发现内容重叠、正文撕裂、暗色不可读或关键确认缺失。键盘开启时克隆与保存按钮完整可见；长日志在列表视口内裁切、继续滚动查看，固定底栏保持可见。真实 Python 编辑器显示诊断波浪线及一致的诊断计数。

此归档包含界面状态和上述 Python 集成证据；固定 fixture 截图不代表网络操作或失败恢复已完成端到端验证。其他功能的运行验证以随附提交的测试记录为准。

## 截图索引

每行提供相同场景的日间与夜间原始 PNG。

### Git 工作流

| 场景 | 日间 | 夜间 |
| --- | --- | --- |
| 分支管理 | [日间](screenshots/git-workflow/day-branches.png) | [夜间](screenshots/git-workflow/night-branches.png) |
| 取消后重新检查状态 | [日间](screenshots/git-workflow/day-cancelled-rechecked.png) | [夜间](screenshots/git-workflow/night-cancelled-rechecked.png) |
| 提交表单 | [日间](screenshots/git-workflow/day-commit.png) | [夜间](screenshots/git-workflow/night-commit.png) |
| 冲突列表 | [日间](screenshots/git-workflow/day-conflicts.png) | [夜间](screenshots/git-workflow/night-conflicts.png) |
| 失败详情 | [日间](screenshots/git-workflow/day-failure.png) | [夜间](screenshots/git-workflow/night-failure.png) |
| 长日志与固定底栏 | [日间](screenshots/git-workflow/day-long-output.png) | [夜间](screenshots/git-workflow/night-long-output.png) |
| 分支、远端和显式网络操作 | [日间](screenshots/git-workflow/day-overview.png) | [夜间](screenshots/git-workflow/night-overview.png) |
| 拉取确认 | [日间](screenshots/git-workflow/day-pull-confirm.png) | [夜间](screenshots/git-workflow/night-pull-confirm.png) |
| 推送确认 | [日间](screenshots/git-workflow/day-push-confirm.png) | [夜间](screenshots/git-workflow/night-push-confirm.png) |
| 标记解决确认 | [日间](screenshots/git-workflow/day-resolve-confirm.png) | [夜间](screenshots/git-workflow/night-resolve-confirm.png) |
| 运行进度与取消 | [日间](screenshots/git-workflow/day-running.png) | [夜间](screenshots/git-workflow/night-running.png) |
| SSH 身份与 known_hosts 说明 | [日间](screenshots/git-workflow/day-ssh-auth-guidance.png) | [夜间](screenshots/git-workflow/night-ssh-auth-guidance.png) |
| 超时与状态未知 | [日间](screenshots/git-workflow/day-timeout-unknown.png) | [夜间](screenshots/git-workflow/night-timeout-unknown.png) |

### 克隆与凭据

| 场景 | 日间 | 夜间 |
| --- | --- | --- |
| 键盘开启时克隆按钮 | [日间](screenshots/git-credentials/day-clone-private-keyboard.png) | [夜间](screenshots/git-credentials/night-clone-private-keyboard.png) |
| 私有仓库克隆 | [日间](screenshots/git-credentials/day-clone-private.png) | [夜间](screenshots/git-credentials/night-clone-private.png) |
| 公开仓库克隆 | [日间](screenshots/git-credentials/day-clone-public.png) | [夜间](screenshots/git-credentials/night-clone-public.png) |
| 键盘开启时保存按钮 | [日间](screenshots/git-credentials/day-credential-keyboard.png) | [夜间](screenshots/git-credentials/night-credential-keyboard.png) |
| 移除凭据确认 | [日间](screenshots/git-credentials/day-credential-remove-confirm.png) | [夜间](screenshots/git-credentials/night-credential-remove-confirm.png) |
| 已保存凭据状态 | [日间](screenshots/git-credentials/day-credential-saved.png) | [夜间](screenshots/git-credentials/night-credential-saved.png) |

### LSP 状态与安装

| 场景 | 日间 | 夜间 |
| --- | --- | --- |
| 检测语言服务器 | [日间](screenshots/editor-lsp/day-checking.png) | [夜间](screenshots/editor-lsp/night-checking.png) |
| 紧凑 LSP 状态 | [日间](screenshots/editor-lsp/day-compact.png) | [夜间](screenshots/editor-lsp/night-compact.png) |
| 失败详情 | [日间](screenshots/editor-lsp/day-failure.png) | [夜间](screenshots/editor-lsp/night-failure.png) |
| 安装前确认 | [日间](screenshots/editor-lsp/day-install-confirm.png) | [夜间](screenshots/editor-lsp/night-install-confirm.png) |
| 缺少语言服务器 | [日间](screenshots/editor-lsp/day-missing.png) | [夜间](screenshots/editor-lsp/night-missing.png) |
| 语言服务已连接 | [日间](screenshots/editor-lsp/day-ready.png) | [夜间](screenshots/editor-lsp/night-ready.png) |

### 真实 Python 编辑器集成

| 场景 | 日间 | 夜间 |
| --- | --- | --- |
| 真实编辑器诊断标记 | [日间](screenshots/editor-lsp-integration/day-editor-real-diagnostics.png) | [夜间](screenshots/editor-lsp-integration/night-editor-real-diagnostics.png) |
| 真实语言服务状态与诊断计数 | [日间](screenshots/editor-lsp-integration/day-status-real-diagnostics.png) | [夜间](screenshots/editor-lsp-integration/night-status-real-diagnostics.png) |

共 54 张截图，保留原始文件内容。
