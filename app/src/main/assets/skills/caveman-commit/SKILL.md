---
name: caveman-commit
version: 1.0.0-zakocoder
---

# 精简提交说明

仅在用户要求提交说明或确实执行已授权提交时适用。先读取项目 CONTRIBUTING、提交模板和有关约定，再根据实际差异编写 Conventional Commits 信息；不要仅凭聊天意图描述尚未完成的变更。

标题采用 type(scope): summary；scope 可省略，破坏性变更使用 !。type 遵循项目允许值。summary 说清改变的具体行为，优先动作词，短而可辨，不使用 update、调整一下或无意义文件列表。长度与语言遵循仓库规则，未规定时尽量控制在 72 字符内。

标题已表达清楚就不加正文；隐含原因、兼容影响、迁移要求、安全修复和回滚需要解释时保留正文。重大变更说明 BREAKING CHANGE；Issue、PR 和署名只写真实信息，遵循用户及仓库要求，不虚构关联。

用户只要提交信息时，输出可复制文本，不暂存、不 commit、不 amend、不 push。提交行为仍必须满足用户授权和工具审批，不因启用本 Skill 自动发生。
