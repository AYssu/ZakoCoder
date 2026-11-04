# 代理内核插件实现与验证

> 目标：**轻量主 APK + 独立可选内核插件**。主 APK 不内置 Xray；需要 VMess／VLESS／SS／Trojan 的用户在应用内主动安装内核插件后，才能启用这些节点。直连、手动 HTTP／SOCKS5 不依赖插件。
>
> 插件化、签名校验、AIDL 服务、安装入口和 Release 工作流已实施；下文保留原型阶段实测数据，当前版本与哈希以 `native-kernel/artifacts.lock.json` 为准。

## 1. 为什么不是“把 xray 打进主 APK”

实测（同一提交、debug 签名、arm64-v8a、`-s -w` 已生效，clean build）：

| 产物 | 大小 |
| --- | --- |
| 基础版主 APK（不打包内核） | **21,473,040 B ≈ 20.5 MiB** |
| 含内核主 APK（本 PR 当前形态） | **35,214,535 B ≈ 33.6 MiB** |
| `lib/arm64-v8a/libzakocoder_xray.so` | 原始 **30,277,794 B**，APK 内压缩 **11,883,858 B** |
| 主 APK 因内核增加 | **+13,741,495 B ≈ +13.1 MiB** |

对不使用内核协议的用户，主包接近翻倍，因此内核独立为可选插件。

## 2. 可行性边界（决定插件形态）

Android 10（API 29）起禁止应用执行可写私有目录中的文件（W^X）：
<https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission>。
因此：

- ❌ 下载内核二进制到 `filesDir` / `cacheDir` 后 `chmod +x` 执行：不可行，不能作为方案。
- ❌ 把可执行文件改名为 `.so` 后用 `System.load`：它仍是 ELF 可执行文件而非共享库，不能按 JNI 库加载。
- ❌ Play 按需动态模块：现有用户主要通过 GitHub Releases APK 安装，AAB/Play 路径不能覆盖现有分发。
- ✅ **独立插件 APK**：插件在自己的 `nativeLibraryDir` 中携带并执行 Xray 可执行文件。Android 允许应用执行自己 APK 内的 native 库（本 PR 当前内核就是从 `nativeLibraryDir` 执行的，已在 API 37 模拟器验证）；插件与主程序通过 Binder 通信。
- 备选（成本更高，暂不选）：把 Xray 改成带 JNI 接口的共享库（需要上游/自研 C-shared 封装，随主 APK 或插件加载，评估成本后再决定）。

本方案的可行性需在实现前用原型验证（见 §7）。

## 3. 插件包

- 包名：`dev.zakocoder.app.kernel`（独立 APK，可单独升级/卸载）。
- 签名：与主 APK **同一正式签名证书**；主程序内置发布证书 SHA-256 指纹作为信任锚。
- ABI：`arm64-v8a` 为正式发布；调试/模拟器可选 `x86_64`（同一插件包内多 ABI，或按 ABI 分资产，发布时二选一并在 Release 注明）。
- 内容：Xray 可执行文件（`libzakocoder_xray.so` 打包，插件自己的 `nativeLibraryDir` 执行）、插件 manifest（内核版本、ABI、xray 版本、许可证）、AIDL 服务实现、MPL-2.0 许可证与来源说明。
- 版本与兼容：`versionCode` 单调递增；插件声明 `minMainVersion`/内核接口版本（`kernelContractVersion`），主程序拒绝不兼容版本；主程序也声明可接受的最低插件版本。
- 来源材料：GitHub Releases 每个资产附 SHA-256、版本说明、Xray 源码固定提交、MPL-2.0 许可证；主程序 THIRD_PARTY 记录来源与构建方式。

## 4. 主程序 ↔ 插件接口（AIDL，v1）

```aidl
interface IKernelService {
    // 返回 JSON：{state, port, socksUser, socksPassword, nodeId} 或 {state:"failed", reason}
    String start(in String configJson);
    void stop();
    String status();
    int contractVersion();
}
```

- 主程序 `bindService` 显式组件（包名 + 服务类），插件服务 `exported=true` 但声明**签名级权限** `dev.zakocoder.app.permission.KERNEL`（`protectionLevel="signature"`）；插件在 `onBind` 用 `checkCallingPermission` 再次校验调用者。
- 配置（含节点凭据）通过 Binder 传入；插件写自己的 `cacheDir`（私有），启动后立即删除配置；SOCKS inbound 仅 `127.0.0.1`，随机 RFC 1929 账号密码，通过 Binder 返回主程序；主程序写现有代理设置。
- 生命周期：主程序绑定期间内核存活；解绑/停止/主程序退出时插件停止内核并清理；插件进程被杀时主程序通过 `DeathRecipient` 感知并显示失败状态；插件 APK 更新/卸载时内核随插件进程结束，主程序清理失效代理设置。
- 安全：签名权限保证只有同证书主程序可调用；不向第三方 App 暴露服务；配置与凭据不写日志；插件不接受任意路径/任意二进制。

## 5. 安装、校验与更新

信任锚：主程序内置**发布签名证书 SHA-256**（按构建渠道区分 debug/release），以及对插件包名、`versionCode` 单调性、`minMainVersion` 的校验。APK 签名校验是密码学信任，不依赖包内自声明哈希。

两种入口复用同一包格式与安装/校验流程：

1. **在线下载**：用户在设置页点“安装内核插件”→ 从 GitHub Releases 获取固定资产（HTTPS）；下载在 IO 调度器，显示进度、可取消、失败可重试；先下载到 `cacheDir`，校验包名/签名证书/版本/ABI 后交给系统安装器（`PackageInstaller`，用户确认）。
2. **本地文件安装**：`ACTION_OPEN_DOCUMENT`（SAF URI，不猜真实路径）选择同一个插件 APK；复制到 `cacheDir`（IO、可取消），执行同样的校验后安装；已取得发布材料后安装阶段不强制联网。

安装失败/取消/签名不符：保留原有可用版本（PackageInstaller 会话）并显示原因；不破坏已安装版本。更新：安装更高 `versionCode` 的同一签名包；更新后重新绑定并检查接口版本。移除：系统卸载插件；主程序清理内核代理端点/标记，内核节点显示“需安装内核插件”。

## 6. 未安装插件时的产品行为

- 直连、手动 HTTP／SOCKS5 完全正常。
- 机场订阅仍可识别/导入/展示节点；VMess／VLESS／SS／Trojan 节点显示「需安装内核插件」，不静默回退直连，不写本地代理。
- 设置页“本地内核”卡显示：未安装 / 已安装 vX（在线更新、从本地安装、移除）/ 运行中（停止）。安装入口仅在此页，用户主动触发。

## 7. 实施前的可行性验证（必须先做）

1. **执行边界**：在 API 29+ 模拟器/真机上验证：从 `filesDir` chmod 后 exec 失败（预期 EACCES/EPERM），从插件 `nativeLibraryDir` exec 成功；记录系统版本与错误码。
2. **跨包 Binder**：最小双 APK 原型（主程序 + 插件）验证 `bindService`、签名级权限、`checkCallingPermission`、`DeathRecipient`；确认同证书/异证书两种行为。
3. **安装流程**：`PackageInstaller` 在线下载包与 SAF 本地包两条路径，验证取消、失败、签名错误、降级拒绝、覆盖安装、卸载。
4. **内核迁移**：把当前 `ProxyKernel` 的 config/生命周期/随机 SOCKS 认证/SSL_CERT_DIR/清理逻辑迁移到插件进程；主程序只保留插件管理 + 状态 UI。
5. **体积与占用**：给出基础版 APK、插件 APK（arm64）、插件安装占用、内核文件原始/压缩大小；确认基础包无内核与重复 ABI。
6. **回归**：直连/手动代理、订阅导入（无插件）、安装后 VMess/VLESS/SS/Trojan 启动/停止/切换/重启、插件更新/卸载、失败恢复；在真实 arm64 设备上跑一次。
7. **供应链**：插件 Release 工作流：Go 工具链 pin、Xray 版本/sha256、许可证与源码包、按 ABI 发布、校验元数据。

## 7.1 原型验证结果（已完成，2026-11-03）

在 x86_64 Android 17/API 37 模拟器上用同一提交、debug 签名、x86_64 ABI 完成最小双 APK 原型（`prototype/kernel-plugin` 分支，不合并）：

- **执行边界**：主 APK 自己的 `nativeLibraryDir/libzakocoder_xray.so` 可执行（`Xray version` 正常）；同一 ELF 复制到 `filesDir`、chmod 后执行被系统拒绝（Android 10+ W^X）。→ 插件必须从插件自己的 `nativeLibraryDir` 执行，设计成立。
- **跨包 Binder**：独立插件 APK 与主 APK 同一 debug 证书（SHA-256 `329ae2b1…`），签名级权限服务 `bindService` 成功；`ping/contractVersion` 正常，插件在**自己的** `nativeLibraryDir` 执行 xray 并返回版本串。设备测试 `KernelPluginProbeDeviceTest` → **OK (1 test)**。
- **实现要点（原型踩坑）**：① Android 11+ 主程序必须声明 `<queries><package android:name="插件包名"/></queries>`，否则 logcat `AppsFilter … BLOCKED`、`bindService` 失败；② `Service.onBind` 的调用者身份是 system，不能在其中 `checkCallingPermission`（会拒绝），签名权限由 `<service android:permission>` 在 bind 前强制，真正的调用者校验放在 AIDL transact / Messenger `sendingUid`。
- **体积实测（同提交/debug/arm64，clean）**：基础版主 APK **21,473,040 B**；当前含内核主 APK **35,214,535 B**；**独立插件 APK（arm64）12,922,037 B**（xray 原始 30,277,794 B / 压缩 11,883,858 B）。x86_64 原型插件 14,115,955 B。主 APK 可回到约 20.5 MiB，需要内核的用户额外安装约 12.3 MiB 插件。
- **尚未验证**：PackageInstaller 在线/SAF 安装、取消/失败/更新/卸载、binder 死亡、生产证书 pinning、真实 arm64 设备与安装占用。原型用 Messenger；生产实现用 AIDL，边界相同。

## 7.2 生产实现与端到端验证（已完成）

在原型基础上已完成生产实现：`kernel-plugin` 模块（AIDL 服务、签名级权限、插件 `nativeLibraryDir` 执行 xray、`SSL_CERT_DIR`、随机 SOCKS 认证、启动/停止清理）与主程序 `KernelPluginManager`/`KernelPluginInstaller`/插件化 `ProxyKernel`、安装卡 UI、CI/手动构建拆分。当前内核固定为 Xray-core `v26.10.31`/Go 1.27.1，主程序配置生成器已支持 VLESS REALITY/TLS 与 XTLS Vision。

- **关键修复**：插件 APK 最初漏了 `android.permission.INTERNET`，导致 xray 在插件进程内无法建立/接受网络连接，表现为启动 Timeout；补权限后通过。
- **设备端到端**（x86_64/API 37，本地自建 VMess + HTTP fixture，debug 证书）：`KernelPluginDeviceTest` → **OK (2 tests)**：插件绑定/`contractVersion=1`；经插件启动 VMess 内核 → 随机 SOCKS 认证 → HTTP 200（`kernel-e2e-ok`）→ stop 后 Stopped。
- **实测体积（debug/arm64/clean）**：基础主 APK **21,530,137 B ≈ 20.5 MiB**（无 xray）；插件 APK **12,772,910 B ≈ 12.2 MiB**（xray 压缩 11,883,858 B）。
- **自动化**：JVM **114 tests / 0 failures**（settings 103 + kernel trust 11）；`assembleDebug`、`lintDebug`、`assembleDebugAndroidTest`、`check_repository` 通过；CI 拆成 base app job（不需要 Go）与独立 `kernel-plugin` job（setup-go + 自编译校验）。
- **仍未完成**：真实 arm64 设备回归；GitHub Releases 正式插件资产与 release 签名 pin（当前在线下载 URL 为占位、debug 证书 pin）；系统安装器安装/更新/卸载全流程 UI 回归。

## 8. 构建与发布改造（方案确认后）

- 主 APK：移除 `prepareProxyKernel` 与 xray jniLibs；基础构建不再需要 Go；CI 保持轻量。
- 新 Gradle 模块 `:kernel-plugin`（applicationId `dev.zakocoder.app.kernel`）：打包 xray 与 AIDL 服务；独立 CI job 按 `native-kernel/go.version`（当前 Go 1.27.1）构建 debug/release 包。
- 发布：`.github/workflows/release-kernel-plugin.yml`（手动 dispatch）构建插件 APK、生成 SHA-256/来源/许可证材料并上传 GitHub Releases；Release 说明含版本、ABI、接口版本、校验值。
- 主程序 release 构建只包含插件管理器代码，不包含内核二进制。

## 9. 风险与边界

- 独立 APK 需要用户确认系统安装；无法静默安装（Android 限制）。
- 签名级权限要求主程序与插件同证书；debug/release 渠道必须使用各自证书并在主程序内置对应指纹，避免 debug 包接受 release 插件或反之。
- 插件进程生命周期受系统回收影响；主程序需检测 binder 死亡并提示，不假装运行中。
- GitHub Releases 资产大小/国内网络可用性；下载失败可重试、可离线本地导入。
- Xray 的 Go 传递依赖许可证清单仍需在发布材料中补齐（THIRD_PARTY 已标注）。
- 本方案不承诺 Play 动态模块路径；现有 GitHub APK 分发优先。

## 10. 交付顺序

1. 本 PR：修复维护者 6 个代码问题 + 提交本设计 + 体积实测（基础版/含内核版）。
2. 维护者确认方案后：插件原型（§7.1–7.3）→ 插件模块 + 主程序管理器 + UX → 真实设备验证与体积数据 → 请求复审。
