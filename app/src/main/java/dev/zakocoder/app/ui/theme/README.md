# ui/theme 令牌层速查

以 [界面规范](../../../../../../../../../docs/DESIGN.md) 和当前代码为准。

当前全局配置：普通卡片无投影，12dp 圆角，1dp 浅色边框（日间 `#E2E8F0`，夜间 `#28323E`）；输入框焦点与选中态使用独立控件色。页面标题 20/28、分组标题 16/24、普通正文 14/20、辅助 12/17、标签 11/15；聊天保留 12/18、终端默认 11sp。`Type.kt` 同时维护 Material Typography，`Radius.kt` 同时维护 Material Shapes。

以下为存量迁移记录，尚未迁移的页面不能视为已完成统一；记录中的旧数值不覆盖上面的当前配置。

## 0. 六个文件各管什么

| 文件 | 内容 | 什么时候打开 |
| --- | --- | --- |
| `Space.kt` | `Space`（6 档间距）· `Touch` · `RowHeight` · `IconSize` · `ControlSize` · `TileSize` | 要写任何 `xx.dp` 之前 |
| `Radius.kt` | `Radius` 与 `WorkbenchShapes` · `rowShape()` | 要写 `RoundedCornerShape` 之前 |
| `Type.kt` | `Type` 与 `WorkbenchTypography` | 要写 `fontSize` / `TextStyle` 之前 |
| `Palette.kt` | `WorkbenchPalette`（28 token）· `LightPalette` / `DarkPalette` · `LocalWorkbenchPalette` · `LightColorScheme` / `DarkColorScheme` · `statusContainer()` | 要写任何 `Color(...)` 之前。**M3 五档 `surfaceContainer*` 的映射有一个已踩过的坑，见 §0.1** |
| `Mapping.kt` | `legacyFontSp` · `legacyWeight` · `legacyRadiusDp` · `overlayRadiusDp` · `legacyIconDp` · `legacySpaceDp` · `legacyTileDp` · `tileRadiusDp` | 改存量代码、不想做审美判断时 |
| `README.md` | 本文件 | — |

**三条铁律**

1. **色值只允许出现在 `Palette.kt`。** 其他任何文件出现 `Color(0x…)` 都是错的。
2. **新代码不要调用 `Mapping.kt` 里的函数。** 那些函数只为"改存量"存在。新代码直接用 token。
3. **高度一律 `heightIn(min = …)`，不用 `height(…)`。** 130% 字体下 `twoLine` 要长到 77dp、`threeLine` 到 98dp，固定高度会裁字。

---

## 0.1 M3 `surfaceContainer*` 映射：一个已经踩过的坑

**先说结论**：`surfaceContainerHigh` 和 `surfaceContainerHighest` 都映射到 **`surfaceSunken`**，不是 `surfaceRaised`。
需要"上浮"的容器（弹出菜单、悬浮详情）**不要走 M3 槽位**，直接读 `LocalWorkbenchPalette.current.surfaceRaised` 并配 `borderHairline` 描边。

`tokens.md` §9 早期版本写的是 `surfaceRaised`，第一批按文档实现后立刻炸了两处，`Palette.kt` L146-156 留了完整注释：

| 槽位 | 存量调用点 | 映射到 `surfaceRaised` 的后果 |
| --- | --- | --- |
| `surfaceContainerHigh` | `AiMarkdown` L191 行内代码块底色、L224 代码块 Surface、`MessageCopyPopup` L36、`ToolBundleList` L87 图标块、`ProjectTasksPage` L162 Dialog | 日间 `surfaceRaised` = `#FFFFFF`，压在 `#FFFFFF` 正文上 → **行内代码块整个消失** |
| `surfaceContainerHighest` | `ToolBundleList` L50 分段控件轨道 | 轨道与选中项同为白色 → **分段控件读不出边界** |

根因：本项目里这五档几乎全落在「**下沉区**」——行内代码、代码块、分段轨道、Popup、Dialog 底。而 `surfaceRaised` 在日间刻意等于 `surface`（白），它只能靠 1dp `borderHairline` 建立边界，**不能承担任何"需要和正文区分的底色"职责**。

> 判断口诀：**问这个容器"是凹进去还是浮起来"**。凹进去 → `surfaceSunken` 系；浮起来 → `surfaceRaised` + 描边，不走 M3 槽位。M3 那五个槽位名字（"比 surface 更突出"）在本项目语义里会骗人。

---

## 1. 命名偏离规范的地方（照抄 tokens.md 会撞名）

规范里写的是 `Row.standard` / `Icon.md` / `Button.height` / `IconTile.sm`，代码里全部改了名。**原因是 Kotlin 顶层 object 与 Compose composable 同名会导致歧义**，不是随意改动：

| tokens.md 写法 | 实际 object | 撞了谁 |
| --- | --- | --- |
| `Space.xs…xxl` | `Space.xs…xxl` | — 无冲突 |
| `Radius.sm/md/lg/xl` | `Radius.sm/md/lg/xl` | — 无冲突 |
| `Type.title…label` | `Type.title…label` | — 无冲突 |
| `Row.compact/standard/twoLine/threeLine` | **`RowHeight.*`** | `androidx.compose.foundation.layout.Row`（composable） |
| `Icon.sm/md/lg` | **`IconSize.*`** | `androidx.compose.material3.Icon`（composable） |
| `Button.height` | **`ControlSize.button`** | `androidx.compose.material3.Button` |
| `Chip.height` | **`ControlSize.chip`** | `androidx.compose.material3` 的 Chip 系列 |
| `Field.height` | **`ControlSize.field`** | 项目内 `Field` 相关 |
| `TopBar.height` | **`ControlSize.topBar`** | 项目内 `TopBar` |
| `IconTile.sm/md/lg` | **`TileSize.sm/md/lg`** | `dev.zakocoder.app.ui.IconTile`（本项目 composable） |
| `LocalWorkbenchPalette.current` | `LocalWorkbenchPalette.current` | — 一致（`internal object`，带 `@Composable get()`） |

> `LocalWorkbenchPalette` 的实现是**派生式**的：读 `LocalWorkbenchDark.current` 决定用 `DarkPalette` 还是 `LightPalette`，而不是要求调用方 `CompositionLocalProvider`。这样任何忘记提供调色板的入口都不会静默退化成日间色。需要强制某个调色板时用 `ProvideWorkbenchPalette(palette) { … }`。

---

## 2. 旧值 → 新值速查

### 2.1 圆角：16 种 → 4 档 + 胶囊

**先判平面，再定档位。** 四档不是自由取用的调色盘：

| 平面 | 可用 | 元素 |
| --- | --- | --- |
| 页面平面 | **只有 8 和 12** | 卡片、列表组、行内元素、按钮、输入框、Chip、代码块、项目卡、设置组 |
| 浮层平面 | **只有 16 和 24** | Dialog(16)、内嵌预览面板(16)、悬浮窗详情(16)、ModalBottomSheet 顶角(24) |

| 旧值 | 新值 | 备注 |
| --- | --- | --- |
| 2, 4, 6, 7 | **8** | 微小元素。**6dp 出现在 24dp 高的徽标（如 `ProtocolBadge`）时必须归 8 而不是 12** —— 24dp 高配 12dp 圆角就是胶囊，会丢掉"徽标"读法 |
| 8 | 8 | 已是档位（Chip / 行内代码） |
| 9, 10, 11 | **12** | 紧凑按钮、小面板、`OutlinedButton`、56dp 选择行 |
| 12 | 12 | 已是默认档（103 处，保持不变，零成本） |
| 13, 14 | **12** | 选择面板、小型容器。**图标容器例外，不按本表走** —— 它的圆角由"圆角/边长"比例决定，见 §2.6（32dp→8、40dp→12、64dp→16） |
| 16 | **12** | `SettingsGroup` 是页面平面的列表组容器；16 只留给浮层 |
| 18, 20 | **12** | `DetailGroup`、`DashedTerminalAction`、加载骨架 —— 都是页面平面容器 |
| 24, 26 | **12** | `EmptyArtwork` 24（装饰方块 64dp → 16，见 §2.6）、`AiComposerDock` 26（页面内常驻输入坞）、`AiModelsPage` 20（72dp 图标块） |
| —（仅 1 处） | **24** | `ModalBottomSheet` 顶部两角。**全应用只允许这一处**，出现第二次就是错的 |

```kotlin
fun pageRadiusDp(old: Int): Int = if (old <= 8) 8 else 12   // → legacyRadiusDp
fun overlayRadiusDp(old: Int): Int = if (old <= 20) 16 else 24
```

> 旧版单函数 `old <= 20 -> 16` 已废弃：它会把 `DetailGroup` 18dp、`DashedTerminalAction` 18dp、加载骨架 18dp 全判成 16，让页面平面混进浮层圆角。

**首末行圆角：先问"会不会滚动"，再问"能不能交给容器裁切"**

| 写法 | 做法 | 判定 |
| --- | --- | --- |
| **W0（滚动列表）** | **不要外框、不要首末行圆角**：行用 1dp `divider` 分隔，整组全宽贴边（直角） | ✅ `LazyColumn` / 超过一屏的行组**只有这一种**写法 |
| **W1（首选，非滚动）** | 容器 `Modifier.clip(shape)` + `border`，**行完全不设 shape** | ✅ 四角天然正确，不存在"首末行"概念 |
| **W2（受限，非滚动）** | 每行独立 `Surface`（各自带背景/描边），首末行用 `rowShape(first, last, r)` | ⚠️ 仅当行必须独立成 Surface |
| W3（禁止） | 首末行写字面量，或容器与行各自写一个来源 | ❌ 必然漂移 |

> **`LazyColumn` 为什么连 W1 都不行**：`Modifier.border` 加在 `LazyColumn` 上画的是**视口**边框，不是内容边框 —— 内容没填满视口时框是空的，内容超出时框不跟着长；`clip` 同理，滑动时按视口硬切。**滚动容器不可能包住内容，外壳本身就不成立。**

`ProjectLibraryPage` L346-348 是 **W3 且位置也判错了**：它在 `LazyColumn`（L330）里，`files` 无上限，本该 W0。当前实际画了**三条线**：每行 `cardBorder()` 1dp + 中间 `GroupDivider()` 1dp + 下一行 1dp = 行间 3dp，首末行还额外带 18dp 圆角。改法：行去掉 `border` 和 `shape`（只留 `color`），保留 `GroupDivider()` 一条，全宽直角。见 §5.1 #4。

**W0 落地时 `GroupDivider` 的内缩要不要留？—— 要留，而且算式正好对上。**

`GroupDivider()` 自带 `padding(horizontal = 16.dp)`，而 L330 的 `LazyColumn` 有 `contentPadding = horizontal 20.dp` → 分隔线距屏幕边 **36dp**。行改成 W0 之后：

| 元素 | 距屏幕边 | 算法 |
| --- | --- | --- |
| 行的左边缘 | 20dp | `contentPadding` |
| 行的内容起点 | **36dp** | 20 + 行内距 16（`padding 15 → 16`，见 §2.7） |
| 分隔线两端 | **36dp** | 20 + `GroupDivider` 的 16 |

**完全对齐。** 所以这不是"线比块窄一截"，而是分隔线**对齐文字、不对齐行边**（iOS 分组列表就是这么做的）。但前提是行内距必须从 15 改成 16 —— 留在 15 就会差 1dp，肉眼能看出线比文字凸出一点。这条依赖 §2.7 的 `15 → 16` 一起改，单独改 W0 会留下 1dp 错位。

---

### 2.2 字号：20 种 → 6 档

| 旧值 | 新值 | Token |
| --- | --- | --- |
| 9, 10, 10.5 | **11** | `label` —— 低于 11sp 在手机上不可读 |
| 11 | 11 | `label`（尊重"终端默认 11sp"的既有约定） |
| 11.5 | **12** | `meta` |
| 12 | 12 | `meta`（聊天正文改 `body` 14/24，见下） |
| 12.5 | **12** | `metaStrong` 或 `label`，按钮文字统一 `bodyStrong` |
| 13, 13.5 | **14** | `body`（原 SemiBold → `bodyStrong`） |
| 14 | 14 | `body` / `bodyStrong` |
| 14.5 | **14** | `body` |
| 15 | **16** | `item`；若原意是正文则 14 |
| 16, 16.5 | 16 | `item` |
| 17.5 | **18** | `section` |
| 18 | 18 | `section` |
| 19 | **18** | `section`（与 18 合并） |
| 20 | **18** | `section` —— 源码无 `fontSize = 20.sp` 命中，仅作兜底 |
| 22 | 22 | `title` |
| **24** | **22** | `title` —— **本规范唯一主动删除的档位**，理由见 tokens.md §3.2.1 |

```kotlin
fun legacyFontSp(old: Float): Float = when {
    old < 11f -> 11f; old < 12.5f -> 12f; old < 15f -> 14f
    old < 17f -> 16f; old < 21f -> 18f; else -> 22f
}
```

> **24sp 不能再回来。** 去掉 SemiBold 后第一反应一定是"标题变弱了，把 24 拿回来"—— 不予受理。档位数量是这套体系唯一的防波堤；标题"不够重"的真实病灶在留白（上方 20 → 24dp、下方 8 → 12dp、每屏只留 1 个标题）。若 22sp 真不够，唯一合法路径是**全局升档**（改 token → 六档仍是六档 → 重跑档位比值表 → 全站生效），**不允许单页例外**。判据一句话：改 token 就要全站生效，只想改一个页面说明问题不在字号。

**同尺寸变体（不新增字号，只换字重或行高）**

| Token | 规格 | 用途 |
| --- | --- | --- |
| `bodyStrong` | 14 / 20 / **500** | 按钮文字、行内强调、行尾危险操作 |
| `metaStrong` | 12 / 17 / **500** | 组标签、字段标签 |
| `bodyLong` | 14 / **24** / 400 | 连续正文 >2 行（对话消息、错误说明、Markdown） |
| `metaLong` | 12 / **20** / 400 | 长文版 meta |

---

### 2.3 字重：4 档 → 2 档

| 旧值 | 新值 |
| --- | --- |
| `Normal` (400) | 400 |
| `Medium` (500) | 500 |
| `SemiBold` (600) | **500** |
| `Bold` (700) | **500** |

**500 的最终判据：看文本长度，不看元素类型。**

> 单条文本 **≤ 8 个汉字（或 ≤ 16 个西文字符）→ 允许 500**；**超过 8 个汉字 → 一律 400**。

元素类型枚举不完（组标签、字段标签、行尾短值、折叠摘要、危险操作文字、Sheet 标题……每加一种都要吵一次），而"只有短文本才加粗"是一条不会错的物理约束 —— 加粗是让**短**文本在扫视时跳出来，一段话加粗只会变成噪音。长度判据也是唯一能在 Code Review 里 3 秒判完的判据。

按此判据合法、不需要单独申请的用法：

| 用法 | 写法 | 字数 |
| --- | --- | --- |
| 组标签（`助手` / `终端`） | `metaStrong` `textTertiary` | 2 |
| 字段标签（`模型` / `端点`） | `metaStrong` `textSecondary` | ≤4 |
| 行尾危险操作（`移除订阅`） | `bodyStrong` + `dangerText` | 4 |
| 折叠头摘要（`未安装 · 需要时安装`） | **`meta`（400）** `textTertiary` | 10 → 必须 400 |
| 空状态说明 / 错误说明 / 帮助文字 | 任何（400） | 句子 |

> 更硬的理由：**字重是 Android 上最不可靠的层级通道。** 系统字体不保证提供真 Medium，缺字重时 Compose 会回退 Regular 或做伪粗 —— 任何依赖 500 才能读出来的层级，在某些设备上会直接消失。层级必须能在"500 退化成 400"的假设下成立。

---

### 2.4 行高：5 档

| 场景 | 高度 | Token |
| --- | --- | --- |
| 旧设置行 / 旧项目卡 | 72 | **56**（`RowHeight.standard`） |
| 单行 + 右侧当前值 | 56 | `RowHeight.standard` |
| 标题 + 副标题 | 64 | `RowHeight.twoLine` |
| 三行（标题+副标题+元信息） | 80 | `RowHeight.threeLine` |
| 仅标题的紧凑行 | 48 | `RowHeight.compact` |

**全部读作 `heightIn(min = …)`。** 130% 字体下 `twoLine` 自然到 77dp、`threeLine` 到 98dp。

---

### 2.5 图标：12 种 → 3 档

| 旧值 | 新值 | 场景 |
| --- | --- | --- |
| 12, 14 | **16** | Checkbox 内勾、Chip 内图标、Sheet 关闭图标 |
| 16 | 16 | 已是档位 |
| 18, 20, 22 | **20** | 行内图标、`WorkbenchIcon` 默认值（22 → 20，整体降 2dp 是降噪的一部分） |
| 24 | 24 | 已是档位 |
| 32, 36, 38, 40, 48 | **24** | 大按钮内图标、悬浮窗图标区、空状态图标、全屏工具条、特大装饰图标 |

```kotlin
fun legacyIconDp(old: Int): Int = when { old <= 16 -> 16; old <= 22 -> 20; else -> 24 }
```

> **`WorkbenchIcon` 默认值 22 → 20 对多数调用点是不可见的。** 那批调用点自己传了 `Modifier.size(17.dp)` 之类的值，按 §2.6 那条"外层永远赢"，实际渲染一个像素都没动。所以**不要以为"图标已经统一到 20 了"** —— 统一的是默认值，散落的 `Modifier.size()` 还在。验收时以 grep 结果为准，不以默认值为准。

---

### 2.6 图标容器：5 个旧尺寸（**尺寸与圆角成对收敛**）

源码里 `IconTile` 原本默认 **46dp、圆角硬编码 13dp**，另有 4 处调用点用 `Modifier.size()` 覆盖成 34 / 36 / 38 / 36 —— **圆角全都是 13dp**。这就是 13dp 越界一直清不掉的真正原因：改调用点没用，必须改组件本身，让 `size` 与 `radius` 都由调用方传入。

**第一批已完成组件侧改造**（`WorkbenchComponents.kt` L179-189）：

```kotlin
internal fun IconTile(
    glyph: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = TileSize.sm,      // 32dp
    radius: Dp = Radius.sm,      // 8dp
)
```

13dp 和 46dp 都已从组件里消失。**但调用点必须拆成两类看**，见 §5.1 2a / 2b —— 迁移时有一个陷阱：

> ⚠️ `Modifier.size()` **永远赢**，`size` 参数只在调用方没传时才生效。
> `Box(modifier.size(size))` 的链是 `Modifier.size(36.dp).size(32.dp)`，**左 = 外、右 = 内**：外层下发 `fixed(36)`，内层的 32 被 `Constraints.constrain` 夹到 36 → 最终 **36dp**。
> 同一条规则对 `WorkbenchIcon` 也成立（这也是"默认值 22 → 20 但视觉没变"的原因：那批调用点都自己传了 `Modifier.size(17.dp)`）。
> **所以：改默认值不会修好任何传了 `Modifier.size()` 的地方，但会立刻改坏所有没传的地方。** 前者是 §5.1 2b（视觉零变化），后者是 §5.1 2a（已经掉到 32dp 了）。

| 旧 | 新尺寸 | 新圆角 | 场景 | 归属 |
| --- | --- | --- | --- | --- |
| 46 | **64** | **16** | 空状态（`ProjectLibraryPage` L338）→ 直接换 `EmptyArtwork(Glyph.Projects)` | 🔴 2a 回归 |
| 46 | **40** | **12** | 卡片内对象标识（`ProjectLibraryPage` L203）→ `size = TileSize.md, radius = Radius.md` | 🔴 2a 回归 |
| 38 | **32** | **8** | 列表行首（`EnvironmentPage` L104）→ 用默认值，删 `Modifier.size(38.dp)` | 🟡 2b 越界 |
| 36 | **32** | **8** | 列表行首（`ProjectLibraryPage` L352 文件行）→ 用默认值，删 `Modifier.size(36.dp)` | 🟡 2b 越界 |
| 34 | **32** | **8** | 选择面板行首（`TerminalLibraryPage` L110）→ 用默认值，删 `Modifier.size(34.dp)` | 🟡 2b 越界 |

```kotlin
fun legacyTileDp(old: Int, role: TileRole = TileRole.ROW): Int   // ROW→32, CARD→40, EMPTY→64
fun tileRadiusDp(tileDp: Int): Int                               // 32→8, 40→12, 64→16
```

归并原则：**就近且不放大**。空状态是唯一"放大"的方向（它是全页唯一的视觉锚点）。圆角按"圆角 / 边长"比例判定：32 → 25%、40 → 30%、64 → 25%；64dp 配 12dp 只有 18.75% 会显方，32dp 配 12dp 会变胶囊。

**为什么列表行首是 32 而不是 40**（这条被问过，给个算式而不是审美）：

> **图标容器的边长 + 2 × 容器内距 = 行高**，三个数必须落在同一档 `RowHeight`。
>
> - `SelectablePanel`（`TerminalLibraryPage` L110 的容器）：`padding = Space.md` 12dp → **32 + 24 = 56 = `RowHeight.standard`** ✅；换成 40 就是 64，会把选择面板从标准行推到 `twoLine` 档，而它的文本只有两行小字，撑不到 64。
> - 文件行（`ProjectLibraryPage` L352）：`padding vertical 13 → 12` → **32 + 24 = 56** ✅。
> - 项目卡片（L203）：`padding 16` → **40 + 32 = 72**，卡片本来就比标准行高（有独立标题 + 类型行），40 是"卡片档"。
>
> 结论：**40 是卡片档，32 是行档。** 三处行首（L352 / L104 / L110）必须一起停在 32 —— 它们和 `EnvironmentPage` L104 是同一角色，两个 Sheet 里的项目标识长得不一样才是真缺陷。若要把它们抬到 40，那就不是改调用点，是**改 `TileSize` 这一档 + `tokens.md` §1.1 / §2.6 + 重跑行高比值表**，全站生效（同 24sp 那条规矩）。

---

### 2.7 间距：9 种 → 6 档

| 旧值 | 新值 | 判定 |
| --- | --- | --- |
| 2 | **0** | 纯视觉微调，用 `Space.xs` 或字距替代 |
| 6 | **4** | 图标—文字、圆点—文字 |
| 9 | **8** | Chip 横向内距、行内并列元素 |
| 10 | **8** | 元素内部紧凑间距（Chip 垂直、紧凑按钮） |
| 10 | **12** | 若原意是容器内距 |
| 13, 14 | **12** | 卡片内距 |
| 15 | **16** | 页面横向留白 |
| 18 | **16** | 页面横向留白（Sheet 内距） |
| 18 | **20** | 若原意是区块间距 |
| 30 | **24** | 大区块间距 |

```kotlin
fun legacySpaceDp(old: Int, intent: LegacySpaceIntent = LegacySpaceIntent.INNER): Int
// intent: INNER / CONTAINER_PADDING / BLOCK_GAP —— 只影响 10dp 和 18dp
```

---

## 3. 硬编码色：89 种 → 28 token

完整判定规则见 tokens.md §4.8。**实现时按"色相 + 明度"聚类，不要逐个改。**

| 色族 | 判定特征 | 映射到 |
| --- | --- | --- |
| 浅色中性 / 容器 | 亮度 ≥88%，饱和度 <8% | 亮度 ≥97% → `surface`；94–97% → `background`；88–94% → `surfaceSunken`；1dp 线 → `divider` |
| 浅蓝容器 | 亮度 ≥88%，色相 215–230° | `surfaceAccent` |
| 中间灰（文字/描边） | 亮度 40–75%，饱和度 <12% | <50% → `textSecondary`；50–62% → `textTertiary`；62–72% → `borderControl`；>72% → `textDisabled` |
| 深色正文 | 亮度 <12% | `textPrimary` |
| 品牌蓝族 | 色相 215–230°，饱和度 >55% | 文字/图标 → `primary`；填充底 → `primaryFill`；浅底上的文字 → `onPrimaryContainer` |
| 绿色族 | 色相 140–165° | `success` / `successText` |
| 琥珀族 | 色相 30–45° | `warning` / `warningText` |
| 红色族 | 色相 0–10°，饱和度 >50% | `danger` / `dangerFill` / `dangerText` |
| 深色表面 | 亮度 4–18%，饱和度 <15% | `background` / `surface` / `surfaceSunken` / `surfaceRaised` / `terminalBackground` |
| 深色描边 | 亮度 12–25%，饱和度 <12% | `divider` / `borderHairline` / `borderControl` |
| 终端前景 | `#C9D1D9` `#8B949E` | `terminalForeground` / `terminalSecondary` |

**两条容易踩的坑**

- **夜间有两个蓝，不能混用。** `primary`（`#4C8DFF`）只做前景（白字压它只有 3.2:1，不达标）；`primaryFill`（`#2F6FED`）只做填充底（白字 4.5:1，达标）。日间 `#2563EB` 一个值同时满足两者（白字 5.2:1），所以日间两者同值。
- **状态容器色不要硬编码。** 一律用 `statusContainer(color)` = `color.copy(alpha = 0.12f)`。规范表里那些 `#E2F7F0` / `#FEF3E2` / `#FDE9E9` 只是设计稿预览，硬编码之后日/夜两套要各维护一份，必然漂移。

**必须删除的 30 个无色值语义**：`#3B7BF5` `#5C9BFF` `#7AA7FF` `#5B8CFF` `#75D49B` `#318354` `#A95000` `#FF9191` `#C34F57` `#FFF7E8` `#F3E3C4` `#F0F4F8` `#F1F5F9` `#E9ECF2` `#E3E7ED` `#EEF1F6` `#EDF0F6` `#E7EBF2` `#1E3450` `#16233B` `#222A34` `#242C36` `#2A3340` `#2D343D` `#333B45` `#343D49` `#2E3A49` `#8B99AC` `#9AA7B8` `#B7C2CE`

---

## 4. 验收 grep（第二批每改完一个文件跑一次）

```bash
# 1. 除 theme/Palette.kt 外不允许出现字面量颜色
grep -rn "Color(0x" app/src/main/java/dev/zakocoder/app/ui/ | grep -v "/theme/"

# 2. 字重只允许 Normal / Medium
grep -rn "FontWeight\." app/src/main/java/dev/zakocoder/app/ui/ | grep -vE "Normal|Medium"

# 3. 不允许固定高度（列表行 / 按钮 / Chip / 输入框 / 折叠头）
grep -rn "\.height(" app/src/main/java/dev/zakocoder/app/ui/

# 4. 不允许阴影与渐变
grep -rnE "shadowElevation|Brush\.(linear|radial|sweep)Gradient" app/src/main/java/dev/zakocoder/app/ui/

# 5. 首末行圆角不允许出现字面量
grep -rnE "if \((first|isFirst|index == 0).{0,12}[0-9]+\.dp" app/src/main/java/dev/zakocoder/app/ui/

# 6. 圆角只允许 8 / 12 / 16 / 24（+ RectangleShape / CircleShape）
grep -rnE "RoundedCornerShape\([0-9]+" app/src/main/java/dev/zakocoder/app/ui/ \
  | grep -vE "\((8|12|16|24)\)"
```

**三条 grep 有已知假阳性，看到了不用改**（第一批实测，写下来省得第二批白改一遍）：

| grep | 假阳性 | 为什么不用改 |
| --- | --- | --- |
| #2 | `WorkbenchComponents.kt` L96 的注释里写着 `shadowElevation = cardElevation()` | 是注释，说明"调用点里的都已移除"。**已约定由 frontend-engineer 下次动代码时改成 `…shadowElevation 传参已一并移除。`**（语义不变、标识符消失，grep 就不再脏）。第二批看到不用管 |
| #4 | `ProjectLibraryPage.kt` L366 `Surface(..., shadowElevation = 0.dp)` | 已经是 0，等价于无阴影。保留是因为显式写 0 比省略更能表达"这里刻意不要阴影" |
| #4 | `AiConversationProgress.kt` L59 `Brush.linearGradient(...)` 画进度条微光 | **这是唯一合法的渐变**：它是逐帧动画（`drawRect` 里读动画值），不是静态装饰。tokens.md 禁的是"用渐变做静态背景/卡片" |

> 反过来说：除了上面三条，**任何新增命中都是真问题**。尤其 #6 —— 一旦出现 10dp、14dp、18dp，就说明有人又在"就近取一个好看的数"。

---

## 5. 遗留清单

### 5.1 仍待处理（第二批）

| # | 遗留 | 位置 | 说明 |
| --- | --- | --- | --- |
| 1 | **`WorkbenchTypography` 有两处声明的风险** | `ui/WorkbenchComponents.kt` L106 | 目前只有那一份。`theme/Type.kt` **刻意不留第二份**（两处必然漂移）。解冻后应把它搬进 `theme/Type.kt`，再删掉原处 |
| **2a** | 🔴 **`IconTile` 两处已发出可见回归（46 → 32）** | `ProjectLibraryPage` **L203**、**L338** | 这两处**没传** `Modifier.size()`，改默认值后从 46dp 掉到 32dp（-30%）。L338 是空目录空状态的正中图标，现在 32dp 躺在 `padding(vertical = 40.dp)` 的列里，**读起来像渲染失败**。必须修：L338 → 直接用 `EmptyArtwork(Glyph.Projects)`；L203 → `size = TileSize.md, radius = Radius.md` |
| **2b** | 🟡 **`IconTile` 三处越界未迁移（34/36/38 → 32）** | `ProjectLibraryPage` L352、`EnvironmentPage` L104、`TerminalLibraryPage` L110 | 这三处**传了** `Modifier.size()`，所以**视觉零变化**（外层赢），不算回归。改法：删掉 `Modifier.size(…)` 用默认 32/8。**不要"统一改 40"** —— 40 是卡片档，理由见 §2.6 |
| 3 | **`Shapes(...)` 里有 14dp / 18dp** | `ZakoCoderApp.kt` L97 | `medium = 14.dp`、`large = 18.dp` 都是越界值，应为 12 / 16。这是**全局形状入口**，改它会影响所有用 `MaterialTheme.shapes.*` 的控件，改之前先 grep 一遍用量 |
| 4 | **`ProjectLibraryPage` L346-348 首末行 18dp + 行间三重线** | 同左 | 在 `LazyColumn` 里 → 按 §2.1 应改 **W0**：行去掉 `border`/`shape` 只留 `color`，保留 `GroupDivider()` 一条，全宽直角。**不是 W1**（滚动容器加不了外壳） |
| 5 | **`GroupDivider` 住在 `SettingsPage.kt` 里，但它已是跨页原语** | `SettingsPage.kt` L74 | 现在 `ProjectLibraryPage`、`SettingsPage` 都在用（`internal` 所以编得过）。W0/W1/W2 三档全要分隔线，第二批会更多 → 挪进 `WorkbenchComponents.kt`，和 `cardBorder()` 放一起。**纯移动、零风险**，但目标文件当前冻结 |
| 6 | **`AiComposerDock` 26dp 圆角 + `#22272E` 冒充浮层** | `AiComposerDock.kt` | 归页面平面 12dp；描边 `#2D343D`/`#E3E7ED` 归 `borderHairline`；输入文字 12sp 应为 `body` 14sp。属任务 #4（AI 对话流专项） |

> **2a 和 2b 必须分开做，不能合并成"5 处统一改 40"。** 2a 是"已经坏了要修"，2b 是"还没坏、顺手收敛"，两者的目标值也不同（2a 是 64 和 40，2b 是 32）。混成一行最容易把 L338 的 64 也一起改成 40 —— 空状态就从"渲染失败"变成"还是不对，只是没那么明显"。
>
> **L338 用 `EmptyArtwork` 而不是 `IconTile(size = TileSize.lg, radius = Radius.lg)`**：两者在 64dp 时逐像素相同，`EmptyArtwork` 就是这个配置的具名预设（64dp + `Radius.lgShape` + `surfaceAccent` 底 + `IconSize.lg` primary 图标）。用意名少一个改错的机会，而且语义直接说出"这是空状态"。

### 5.2 第一批已解决（**不要重复改**）

| 原遗留 | 现状 |
| --- | --- |
| `IconTile` 组件硬编码 46dp + 13dp | ✅ `WorkbenchComponents.kt` L179-189 已改为 `size: Dp = TileSize.sm` / `radius: Dp = Radius.sm`，13dp 与 46dp 已从组件里消失。**但调用点拆分见 §5.1 2a/2b** —— 组件侧解决不等于视觉解决 |
| `EmptyArtwork` 线性渐变 + 84dp + 24dp | ✅ 已改为 64dp（`TileSize.lg`）+ 16dp（`Radius.lg`）+ `surfaceAccent` 平面底 + 24dp（`IconSize.lg`）`primary` 图标，`Brush.linearGradient` 已删除 |
| `GroupDivider` 硬编码 `#222A34` / `#F1F5F9` | ✅ `SettingsPage.kt` L74 已归 `LocalWorkbenchPalette.current.divider`（**但位置问题见 §5.1 #5**） |
| `surfaceContainerHigh`/`Highest` → `surfaceRaised` | ✅ `Palette.kt` L199-200 / L244-245 已改 `surfaceSunken`（理由见 §0.1）。`tokens.md` §9 也已同步修正 |

---

## 6. 一句话记住

> **同质用列表（56）、配置用折叠（56 + 摘要）、文本用视口（无圆角）；一页只留一个真标题，一屏只有一层滚动，每个折叠头都要说出里面是什么。**
