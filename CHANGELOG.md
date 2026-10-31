# 更新记录

## 0.1.0-beta.1 · 准备中

首个 ARM64 Beta 的计划范围；以实际 GitHub Pre-release 为发布依据，最终 Release 签名与设备检查结果随发布记录提供。

- 原生项目库、目录导入与本地绑定、Sora 代码编辑器、草稿和 Markdown 预览。
- 按需安装 Ubuntu／开发工具包，多终端及本地网页预览。
- 多协议模型配置、流式会话、工具审批、Skill／MCP、文件差异和任务检查点。
- 项目命令验证、HTTPS Git 操作及实验性 Python／JavaScript／TypeScript LSP。
- Android 11–17 AOSP x86_64 Debug 兼容性基线，包含系统目录授权、真实键盘、进程恢复和覆盖升级；不等同于全部 ARM64 厂商或 Release 包验收。
- 最终本地 Release R8／Lint、425 项单元测试、58 项脚本测试与 AndroidTest 编译通过；API 35 R8 QA 项目编辑、重启持久化及 4 张日夜截图通过，制品共有代码／资源／原生文件和版本签名一致；GitHub Actions 尚未实际运行。
- Release + R8 Beta 预发布，默认复用仓库 CI Debug 证书、支持可选自有签名并关闭调试；仅附 APK 和校验摘要，构建信息放正文折叠详情，完整对应运行时源码通过固定提交／标签的 GitHub Source code 归档提供，许可证保留于 APK 与源码；原 Debug 下载入口保留。

安装、数据与已知限制见 [Beta 测试说明](docs/BETA_TESTING.md)。Android 8 完整开发环境、真实 16 KiB 页设备、x86_64 Android SDK／NDK 不在本次已验收范围内。应用更新不自动替换已有 Ubuntu；默认签名的 Beta 与仓库 CI Debug 共用证书，其他来源签名须先核对；未来切换正式密钥需说明升级迁移。
