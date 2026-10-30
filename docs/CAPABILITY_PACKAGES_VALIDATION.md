# 能力包验收记录

日期：2026-10-30。基线 `56d8608`；本地功能分支 `feat/android-screen-testing`。本记录覆盖两个独立能力包及其 AI 接线，屏幕后端此前的验证见 [屏幕测试验收](SCREEN_TESTING_VALIDATION.md)。

## 隔离与方法

Android 13 / MIUI / ARM64 真机。使用独立宿主 `dev.zakocoder.app.screenreview`，不覆盖日常应用。SSH 使用开发机上独立端口的临时 OpenSSH 服务及专为本次测试生成的密钥，通过 ADB reverse 连接；不读取用户已有凭据，不更改系统 SSH 服务或用户 authorized_keys。每项远程测试仅操作 UUID 命名的自有文件、配置与 tmux 任务。

AI 测试使用本地 HTTPS 确定性协议服务，不使用用户模型密钥。测试核对实际 HTTP 请求、真实工具输出及持久历史，不将假服务器回复当作真实 SSH 或屏幕操作的证据。这证明工具接线与权限行为，不证明商业模型的自主规划能力。

## 已执行结果

| 检查 | 实际结果 |
| --- | --- |
| 原生 Debug 与设备测试包构建、Lint | 通过；JSch 使用版本目录，原始 BSD 声明随 APK 提供 |
| 应用单元测试 | 226 项通过，包括 9 项 SSH 安全与 UTF-8 分页测试 |
| 能力包模型协议设备测试 | 4 项通过：默认请求不携带可选 schema / 专属说明；独立开启；未启用调用拒绝；关闭重开不能复用旧审批 |
| 能力开关存储设备测试 | 4 项通过：项目隔离、重建后状态、新租约、立即撤销、损坏拒绝及 AtomicFile 备份恢复 |
| 真实 SSH 设备测试 | 8 项通过：首次指纹确认、退出码、SFTP 精确修改与摘要、中文分页、并发修改拒绝、链接与越界拒绝、输出限制、超时取消、tmux 重连与配置租约、凭据加密与端点更换 |
| AI 屏幕协议与审批回归 | 12 项通过；已显式启用屏幕包，维持禁止规则、临时截图保留和任务租约检查 |
| AI → SSH 真实闭环 | 1 项通过：审批前远程文件不存在；批准后实际执行写入；SFTP 读回内容与 SHA-256，下一轮模型请求及持久历史包含实际结果 |
| AI → 屏幕真实闭环 | 1 项通过：开启包后启动、读取节点、真实点击、断言、截图回传，结束撤销目标授权 |
| 独立批量只读审查 | 原审批配置竞争、分页摘要、tmux 登记及备份恢复问题已修复；最终复审未发现新增关键阻塞项 |

本批真机集成共 30 项。终端模块未变，复用此前 145 项已通过结果，没有因每次编辑重复全量测试。界面渲染另行记录，不混入真实传输计数。

构建命令：

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest
```

设备测试使用本地 Gradle init 脚本替换 applicationId 实现宿主隔离；交付 APK 不使用该脚本，包名仍为 `dev.zakocoder.app`。仅测试脚本时序修正后，只重编设备测试 APK 并重跑界面用例。

## 限制

- 本轮实测 Android 13；其他平台的密钥算法、无障碍与 Root 兼容性仍需对应真机验证。UI 支持密码和私钥，本次 SSH 服务实际验证私钥认证，没有另行开启密码服务。
- 远程工作目录不是 Shell 权限沙箱；获批命令具有 SSH 用户本身的权限。远程副作用不被本地项目检查点恢复。
- 取消关闭所属连接；不能保证服务器已结束脱离连接的普通进程。tmux 任务按设计留在远端，后续可查看或明确关闭。
- SFTP 修改核对当前摘要并尽力使用同目录临时文件及重命名；最后一次校验与替换之间仍可能受到其他客户端写入的竞争，不宣称完整原子比较交换。
- 屏幕截图保留原有目标与分享授权约束，Android 11–13 瞬时覆盖等边界见屏幕测试文档。

## 界面验收与截图

`CapabilityPackagesUiDeviceTest` 日夜两项均通过，共 30 张真实原生界面截图，360dp 视口、130% 字号。已核对滚动可达、默认关闭、密码／私钥切换、凭据保存后清空、停止按钮、指纹确认及变更后的禁用状态。首轮测试暴露页面组合提交前误编辑上一场景的测试时序问题，已隔离场景状态并等待实际提交，保留启用与凭据清空断言；最后仅重编并重跑这两项测试。截图补拍让主机指纹阻断和清除操作同屏可见。

截图使用合成配置和纯 UI 状态，不包含真实密钥、用户项目或用户会话；它们证明渲染和回调，真实连接由上方 SSH 测试独立证明。文件为无损 WebP，仅裁去系统状态栏与导航栏；尺寸、主题、原始截图及归档文件摘要见 [截图清单](assets/capability-packages/screenshots.json)。截图与对应功能源码一并提交；PR 正文以提交固定链接关联本次归档。

| 场景 | 日间 | 夜间 |
| --- | --- | --- |
| 默认关闭与项目范围 | [查看](assets/capability-packages/light-packages-default-off-360-font130.webp) | [查看](assets/capability-packages/dark-packages-default-off-360-font130.webp) |
| 两个独立能力包 | [查看](assets/capability-packages/light-ssh-enabled.webp) | [查看](assets/capability-packages/dark-ssh-enabled.webp) |
| 密码配置 | [查看](assets/capability-packages/light-ssh-password-configured.webp) | [查看](assets/capability-packages/dark-ssh-password-configured.webp) |
| 私钥配置 | [查看](assets/capability-packages/light-ssh-private-key.webp) | [查看](assets/capability-packages/dark-ssh-private-key.webp) |
| 首次确认指纹 | [查看](assets/capability-packages/light-ssh-first-host-confirmation.webp) | [查看](assets/capability-packages/dark-ssh-first-host-confirmation.webp) |
| 指纹变化阻断 | [查看](assets/capability-packages/light-ssh-host-key-changed.webp) | [查看](assets/capability-packages/dark-ssh-host-key-changed.webp) |
| 连接中停止 | [查看](assets/capability-packages/light-ssh-connecting-cancel.webp) | [查看](assets/capability-packages/dark-ssh-connecting-cancel.webp) |
| 验证通过与 tmux | [查看](assets/capability-packages/light-ssh-connected-tmux.webp) | [查看](assets/capability-packages/dark-ssh-connected-tmux.webp) |
| 开源许可证 | [查看](assets/capability-packages/light-ssh-bsd-license.webp) | [查看](assets/capability-packages/dark-ssh-bsd-license.webp) |

本轮共 32 项真机用例通过（30 项集成、2 项界面）；先前未修改的屏幕后端专项覆盖保留在其独立记录，不重复计入本轮。临时无障碍设置和测试应用后台启动 app-op 已恢复；临时 SSH 服务已停止，ADB reverse 与合成测试密钥已移除。Debug APK 已校验签名及正式包名，未覆盖用户日常应用。
