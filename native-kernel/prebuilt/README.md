# 本地构建使用的代理内核

本目录保存与 `../artifacts.lock.json` 完全一致的 Xray-core 可执行文件，供 Lite 配套插件及 Full 主应用本地打包复用。`.so` 是 Android 原生库打包所需名称；实际内容为 Linux ELF 可执行文件，必须从 APK 安装后的 `nativeLibraryDir` 执行。

## 来源与许可证

- 官方源码：<https://github.com/XTLS/Xray-core>。
- 上游发布：`v26.10.31`；固定提交：`a4a3dff5b20c2faae567b742c172c7b95e75c083`。
- Go 模块伪版本：`v1.260327.1-0.20261031132019-a4a3dff5b20c`。
- 工具链：Go `1.27.3`，与 `../go.version` 一致。
- 入口：`github.com/xtls/xray-core/main`；`GOOS=linux`、`CGO_ENABLED=0`，使用 `go build -trimpath -ldflags '-s -w'`。
- 未修改上游源码；许可证 **MPL-2.0**。原文保留在 `app/src/main/assets/licenses/Xray-MPL-2.0.txt`，分发方式及依赖声明见根目录 `THIRD_PARTY.md`。

| Android ABI | Go 架构 | 文件 | SHA-256 |
| --- | --- | --- | --- |
| arm64-v8a | arm64 | `arm64-v8a/libzakocoder_xray.so` | `b007302726169d6b68672b0c378f765666c23431334329e9a5a21ae81f20343c` |
| x86_64 | amd64 | `x86_64/libzakocoder_xray.so` | `7750bedaa60c98b649989a206d71d48140ba7ea8c9277d2851e90d48ae33e1b7` |

## 校验与重建

本地打包只能复用摘要与锁文件一致的二进制；不能以文件存在为由跳过完整性校验。源码、Go 工具链或构建参数变化时，必须通过 `scripts/prepare_proxy_kernel.py` 重建并审核后一起更新制品和锁文件。

CI 继续从固定源码构建这两个 ABI，并验证构建输出摘要，不以本目录二进制存在为理由跳过源码构建测试。公开分发仍须提供固定版本源码入口和所有适用许可声明，不能用本项目 Apache-2.0 替代上游许可。

## 进程边界

内核通过共享的 Android Service 启动；正常停止、替换启动或服务收到 `onDestroy` 时会终止当前 Java `Process` 并清除该次配置。Full 的 Binder 服务运行于应用 `:kernel` 进程，Lite 运行于独立插件。Binder 断开后主应用将当前代理标记为失败，用户可以重新启动。

这不等于常驻守护：Android 强制结束整个应用、系统回收或 `SIGKILL` 不保证调用 `onDestroy`，当前没有原生父进程死亡监督。服务重建后仅管理自己创建的新 `Process`，不会扫描并按进程名或端口杀死其他进程，也不会把旧 PID 当成仍然归属的进程。因而单独强制杀死服务进程后的 Xray 子进程自动清理不作保证；不能将无配置的 Binder 重连测试视为运行中代理恢复验收。

`KernelNativeLifecycleDeviceTest` 使用隔离测试应用和本机回环 HTTP 服务，验证真实 Xray 可执行文件启动、认证 SOCKS 转发、替换及停止，不需要订阅或外网凭据；它不验证外网节点可达性或强制杀死进程后的孤儿清理。
