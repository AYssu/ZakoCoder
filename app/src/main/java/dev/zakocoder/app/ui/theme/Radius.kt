package dev.zakocoder.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 圆角四档 + 胶囊（tokens.md §2）。
 *
 * **先判平面，再定档位**（§2.1.1）—— 四档不是自由取用的调色盘：
 * - 页面平面：只有 [sm] 和 [md]。卡片、列表组、行内元素、按钮、输入框、Chip、代码块。
 * - 浮层平面：只有 [lg] 和 [xl]。Dialog(16)、内嵌预览面板(16)、ModalBottomSheet 顶角(24)。
 *
 * 同一平面内**圆角不承担区分职责**。独立卡片和列表组容器都是 [md]，
 * 差别由内容结构承担（卡片有标题+元信息，列表组是一串被 1dp 线切开的行）。
 * "可跳转的行"和"开关行"的差别由**行尾控件**承担（chevron / Switch / 仅值），
 * 不由圆角承担 —— 2dp 的差用户根本感知不到。
 *
 * [xl] 全应用只允许出现 1 处：[xl] = `ModalBottomSheet` 顶部两角。
 * 出现第二次就是错的，因为 24 与 16 承担的是两种不同的层级语义：
 * 24 = 从屏幕底部升起的系统级浮层，16 = 页面内的应用级浮层。
 * 两者必须在视觉上可区分，否则 Sheet 和 Dialog 会读成同一种东西。
 */
object Radius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp

    val smShape = RoundedCornerShape(sm)
    val mdShape = RoundedCornerShape(md)
    val lgShape = RoundedCornerShape(lg)
    val xlShape = RoundedCornerShape(xl)

    /**
     * `ModalBottomSheet` 顶部两角 —— **[xl] 在全应用唯一的合法出口**。
     *
     * 底部两角必须是 0：Sheet 是从屏幕底部升起的，底边贴着屏幕边缘，
     * 圆角会露出页面底色，读成"一个浮在页面上的卡片"而不是"从底部升起的系统级浮层"。
     */
    val sheetShape: Shape = RoundedCornerShape(topStart = xl, topEnd = xl)

    /** 胶囊：仅用于高度 ≤32dp 且内容是单一短文本 / 单一图标 / 纯色点。 */
    val pill: Shape = CircleShape
}

/** Material 默认控件也使用相同的圆角档位；显式指定形状的组件保持其语义。 */
val WorkbenchShapes = Shapes(
    extraSmall = Radius.smShape,
    small = Radius.smShape,
    medium = Radius.mdShape,
    large = Radius.lgShape,
    extraLarge = Radius.xlShape,
)

/*
 * 曲率规则（tokens.md §2.1.2）—— 构图约束，不是新 token，所以没有对应常量。
 *
 *   **容器圆角必须小于其内部圆形按钮的半径，否则曲率层级倒置。**
 *
 * 反例：`AiComposerDock` 用 26dp 圆角，内部是 36dp 圆形发送按钮（半径 18）。
 * 26 > 18，容器比自己的按钮更圆，视觉上读成"外面是个胶囊、里面塞了个方按钮"。
 * 正确的 12dp 让"越小的元素比例上越圆"这个普遍预期成立。
 *
 * 判定依据是结构而非高度：`AiComposerDock` 宽度 fillMaxWidth 在页面 Column 内、
 * 背后无 scrim、随内容流常驻底部、无拖拽条 —— 它是页面内常驻容器，
 * 归页面平面 Radius.md，不是浮层 Radius.lg。
 *
 * 反例本身属 AI 对话流专项（任务 #4），本批不动它，只把规则记在这里，
 * 避免下一批以"输入坞是个例外"为由重新立一个 26dp。
 */

/**
 * 列表"首末行单独圆角、中间行 0"时用的形状工厂（tokens.md §2.3 特例 **W2**）。
 *
 * ## 先确认你是不是真的需要它
 *
 * **先判断"会不会滚动"，再判断"能不能交给容器裁切"。** 会滚动的列表根本不存在"首末行圆角"这回事：
 *
 * | 写法 | 做法 | 判定 |
 * | --- | --- | --- |
 * | **W0（滚动列表）** | **不要外框、不要首末行圆角**：行用 1dp `divider` 分隔，整组全宽贴边（直角） | ✅ `LazyColumn` / 超过一屏的行组**只有这一种**写法 |
 * | **W1（首选，非滚动）** | 容器 `Modifier.clip(shape)` + `border`，**行完全不设 shape** | ✅ 行被容器裁切，四角天然正确 —— 不存在"首末行"这个概念，也就没有漂移的可能。仅限 `Column` + 行数固定且全可见 |
 * | **W2（本函数）** | 每行独立 `Surface`（各自带背景 / 描边 / 独立裁剪），首末行用 [rowShape] | ⚠️ 仅当行必须各自是 Surface，且容器不滚动 |
 * | W3（禁止） | 首末行写字面量，或容器与行各自写一个来源 | ❌ 双来源必然漂移 |
 *
 * > **`LazyColumn` 为什么连 W1 都不行**：`Modifier.border` 加在 `LazyColumn` 上，画的是
 * > **视口**的边框而不是内容的边框 —— 内容没填满视口时框是空的，内容超出时框不跟着长；
 * > `clip` 同理，滚动时按视口硬切。**滚动容器不可能"包住"内容，外壳本身就不成立。**
 * > 而且长列表本来就该全宽：给一个能放 200 个文件的列表加圆角外框，等于假装它只有 5 行。
 *
 * ```kotlin
 * // ✅ W1：容器裁切，行零圆角 —— 非滚动、行数固定的组（SettingsGroup / DetailGroup）
 * val shape = Radius.mdShape
 * Column(Modifier.clip(shape).border(1.dp, hairline, shape)) {
 *     rows.forEachIndexed { i, _ ->
 *         if (i > 0) HorizontalDivider(color = divider, thickness = 1.dp)
 *         Row(Modifier.fillMaxWidth()) { /* 行内不放 shape */ }
 *     }
 * }
 *
 * // ✅ W0：滚动列表 —— 无外壳、直角、只留 divider
 * LazyColumn(Modifier.fillMaxWidth()) {
 *     items(rows) { Row(Modifier.fillMaxWidth()) { /* 无 shape、无 border */ } }
 * }
 *
 * // ⚠️ W2：行必须独立成 Surface 且容器不滚动时，容器与首末行共用同一个 r
 * val r = Radius.md
 * val shape = RoundedCornerShape(r)
 * Column(Modifier.border(1.dp, hairline, shape)) {
 *     rows.forEachIndexed { i, _ ->
 *         Surface(shape = rowShape(i == 0, i == rows.lastIndex, r)) { … }
 *     }
 * }
 * ```
 *
 * **如果一段代码里出现了 `if (first)` / `if (last)` 的圆角判断，先问"行为什么不能
 * 交给容器裁切"，而不是直接去补 token。**
 *
 * 禁止 `if (first) 12.dp else 0.dp` 这种字面量 —— 容器一改就错位。
 *
 * 现状 `ProjectLibraryPage` L346-348 是 W3，而且**位置也判错了**：它在 `LazyColumn`
 * （L330）里、`files` 数量无上限，本该是 W0。当前实际在行间画了**三条线** ——
 * 每行 `cardBorder()` 1dp + `GroupDivider()` 1dp + 下一行 1dp = 3dp，首末行还额外
 * 带 18dp 圆角，与"组"的读法完全不符。改法见 theme/README.md §5.1 #4。
 */
fun rowShape(first: Boolean, last: Boolean, radius: androidx.compose.ui.unit.Dp): Shape = RoundedCornerShape(
    topStart = if (first) radius else 0.dp,
    topEnd = if (first) radius else 0.dp,
    bottomStart = if (last) radius else 0.dp,
    bottomEnd = if (last) radius else 0.dp,
)
