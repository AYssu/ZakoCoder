# 贡献指南

欢迎提交问题、文档、设计和代码。当前处于原型阶段，公共接口与数据格式尚未稳定；修改已有行为仍需明确说明兼容性，不应静默丢失用户数据。

## 开始贡献

1. 阅读 README、产品与架构文档，确认目标功能的实现状态。
2. 搜索现有 Issue/PR，避免重复工作。小修复可直接发 PR；新模块、依赖、权限、数据格式和明显交互变化，先开设计 Issue。
3. 在 Issue 中描述问题、预期行为、替代方案、验收条件；维护者确认范围后再开展较大实现。
4. 从默认分支创建工作分支，一个 PR 解决一个明确问题。
5. 完成相关验证，更新文档，提交 PR。未完成的工作使用 Draft。

默认分支为 `main`。本地研究材料、个人配置和构建产物不参与提交。

## 分支命名

使用 `<type>/<简短英文主题>`，有关联 Issue 时可用 `<type>/<issue编号>-<主题>`。

允许类型：`feat`、`fix`、`docs`、`refactor`、`test`、`build`、`ci`、`chore`。例如 `feat/42-terminal-session`、`fix/workspace-permission`。不要在分支名中加入个人信息、密钥或空格。

采用主干开发：`main` 保存可构建的代码，功能和修复从最新 `main` 创建短期分支，通过 PR 合并；暂不设长期 `develop` 分支。外部贡献者先 Fork，在自己的仓库建立分支，再向本仓库 `main` 发起 PR。依赖更新由 Dependabot 使用自身分支命名。

```sh
git switch main
git pull --ff-only
git switch -c feat/42-terminal-session
# 完成修改并执行相关检查后
git add <本次修改的文件>
git commit -m 'feat(terminal): 支持会话切换'
git push -u origin feat/42-terminal-session
```

一个 PR 对应一个主题。尚未完成时创建 Draft；合并后自动删除远端分支。不得向其他贡献者的分支强推，也不将测试数据、模型凭据或本地参考材料加入提交。


## Commit 规范

采用 Conventional Commits 风格，提交必须表达实际意图：

```text
<type>[(scope)][!]: <summary>

[body: why, behavior, limitations]

[footer: references or breaking change]
```

- type 与上方分支类型一致，另允许 `perf` 和 `revert`。scope 使用真实模块，例如 `workspace`、`terminal`、`runtime`、`agent`、`ui`、`docs`。
- summary 用中文或英文，使用动词描述结果，整行建议不超过 100 字符，不写无意义的 `update`、`fix bug`。
- 一个 commit 只包含一个逻辑变更；禁止混入全项目格式化、构建产物和无关依赖升级。
- 不兼容变更使用 `!`，并在正文末尾写 `BREAKING CHANGE: ...`，解释影响和迁移方式。
- 自动回滚使用 `revert: ...`，正文给出原提交和原因。

例子：

```text
feat(workspace): 支持系统目录持久授权
fix(terminal): 取消任务时终止对应进程组
build(android): 固定编译工具版本
```

可选启用仓库内 commit-msg hook（只修改本地 Git 配置，不自动安装）：

```sh
git config --local core.hooksPath .githooks
```

撤销时执行 `git config --local --unset core.hooksPath`。也可直接运行 `python3 scripts/check_commit_message.py --title 'feat(workspace): 支持目录授权'`。本地 hook 和 CI 共用同一验证脚本。

CI 校验 PR 标题的类型格式；维护者使用 squash merge，使最终主线提交保持相同规范。开发中的 fixup 提交可在合并前整理。

## Pull Request 规范

标题遵循上述 commit 格式。正文使用模板，必须包含：

- 具体问题和触发条件，修改后的行为。
- 本次范围，以及仍未实现的部分。
- 实际运行的检查命令及结果；没有运行时明确原因，不能把待执行写成已通过。
- 任何 UI 新增、变更、调整都必须在 PR 正文附对应实际界面截图，按改动位置归档，覆盖日间与夜间两种模式；交互变化补充关键中间态，录屏可作为补充，不能替代截图。
- 截图说明对应提交、设备、主题和测试数据来源；复核窄屏、字体放大、安全区与键盘，不包含真实密钥、用户项目或会话。
- 只有本地环境确实无法提供夜间截图时，才可仅附日间截图；须写明环境限制，以及已确认夜间适配的实际验证方法和结果。未验证不得写“已适配”，也不能以环境限制代替验证。
- 权限、依赖、第三方代码、存储和公共接口的变化及兼容影响。
- 关联 Issue，只有完整解决才使用 `Closes #...`。

提交前运行：

```sh
python3 scripts/check_repository.py
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :terminal-emulator:testDebugUnitTest
```

没有相关单元测试时，Gradle 任务可能显示 `NO-SOURCE`，这不代表业务行为被测试。涉及文件权限、进程、安装或恢复逻辑时，必须补相应的设备验证或可复现记录，不能只靠编译。

## 审查与合并

维护者先审核行为、边界、验证证据和文档。涉及 UI 的 PR 缺少上述截图及合理例外说明时不进入合并；截图与对应提交一并归档，后续修改同步更新，不能沿用已失效的画面。安全、数据丢失、构建失败及缺乏关键验证是阻塞问题；偏好建议需标明为非阻塞。作者回应每个阻塞意见，新增提交后重新请求审查。

管理员应保护 `main`：要求 CI 的 `android`、`repository`、`pr-title` 三个检查通过和至少一位维护者批准，更新后撤销过期批准，禁止强推和删除受保护分支，普通贡献者不得直接提交。管理员保留紧急绕过能力，常规修改仍通过 PR；单人维护时自有 PR 无法获得自己的批准，应在检查通过后由管理员完成合并。GitHub 分支保护由仓库管理员配置。

使用 squash merge，维护者最终核对标题与贡献者归属。敏感漏洞不在公开 Issue/PR 中披露，见 SECURITY.md。
