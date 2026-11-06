# Lite / Full 分发界面验证

2026-11-06，模拟器采用 1080 × 2400、420 dpi 和默认字号。仅作为模拟器证据，不代表 ARM64 厂商真机验收；未执行 130% 字号测试。

## Full 内置内核组件

| 日间 | 夜间 |
| --- | --- |
| ![Full 内核日间](kernel-day.png) | ![Full 内核夜间](kernel-night.png) |

来自 API 33 x86_64 隔离测试包 `dev.zakocoder.app.runtimecheck` 的实际 Compose 组件。确认内置版本和诊断入口可见，外置插件的在线安装、本地安装、更新、移除和激活入口均不显示。测试容器未同步系统状态栏／导航栏主题，这两张图只验证组件内容及主题，不作为整页系统栏验收。

## 已执行的分发检查

- Debug 和 R8 Release 均实际构建 Lite / Full × ARM64 / x86_64 四包；验证单 ABI、版本身份、签名、edition 标记和四包 SHA-256 清单。
- Full 对应 ABI 的原始 Ubuntu 归档及内核摘要与固定清单匹配；Lite 不含这两项，不嵌套插件 APK。
- API 33 隔离测试分别验证 Full 内置内核、Lite 外置插件的真实本地认证代理转发，以及替换、停止和日夜组件。
- Full 内置 Ubuntu 验证取消清理、错误代理下的纯本地安装、重试和已有环境保护。

ARM64 本轮仅完成构建、包体与同 edition 代码资源一致性检查，未连接 ARM64 真机。x86_64 支持基础 Ubuntu 与终端，不代表 ARM64 专用 Android SDK / NDK / Flutter 工具包已适配。
