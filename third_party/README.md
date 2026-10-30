# Vendored terminal libraries

Source: termux/termux-app, commit `8629e632fcb95da272221be327db653fb24befe9`.
Only terminal-emulator and terminal-view reusable libraries are included. No Termux application or Operit business/UI code is included.
Upstream LICENSE.md declares these libraries as Apache-2.0 exceptions; upstream notices and source headers are preserved beside each library.

Local modifications: Gradle configuration for this repository, ARM64 native build, and Android public-API PTY descriptor ownership (ParcelFileDescriptor rather than hidden-field reflection). Record future changes here; update upstream versions separately.

构建配置统一使用 `compileSdk = 37`，与主应用一致；终端库的最低 API 26、NDK 版本和原生编译目标保持原值。该配置由本仓库维护，未修改上游终端解析、绘制或 PTY 源码。
