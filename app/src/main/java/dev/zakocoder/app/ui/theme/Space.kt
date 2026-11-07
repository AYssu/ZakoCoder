package dev.zakocoder.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 间距与尺寸标尺（tokens.md §1）。
 *
 * 只有六档间距。2/6/9/13/14/15/18/30 这些旧值一律不允许再出现，
 * 需要时用 [legacySpaceDp] 归位。
 *
 * 注意：`none` 存在的目的是**显式取消**间距（替代原来的 2dp 像素微调），
 * 不是让你在 Arrangement.spacedBy(0.dp) 里刷存在感。
 */
object Space {
    val none = 0.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

/**
 * 触区（tokens.md §6.2）。48dp 是下限，不是比例 —— 字体放大到 200% 也不缩放。
 */
object Touch {
    val target = 48.dp
    val gap = 8.dp
}

/**
 * 列表行高（tokens.md §1.1）。
 *
 * 命名偏离规范里的 `Row.standard`：`Row` 是 `androidx.compose.foundation.layout.Row`
 * 这个 composable 的名字，同名 object 会让所有同时用到两者和 `Row.standard` 的文件
 * 出现显式 import 冲突。语义完全一致，只是换了个不撞名的外壳。
 *
 * **所有行高都是 `heightIn(min = …)`，不是 `height(…)`**（tokens.md §3.4 R1）。
 * 130% 字体下 twoLine 会自动长到 77dp、threeLine 到 98dp，固定高度会裁字。
 */
object RowHeight {
    val compact = 48.dp      // 单行列表行 / 设置行（仅标题）
    val standard = 56.dp     // 单行 + 右侧当前值
    val twoLine = 64.dp      // 标题 + 副标题
    val threeLine = 80.dp    // 标题 + 副标题 + 元信息
}

/**
 * 图标尺寸（tokens.md §6.1）。三档，且**用 dp 固定，不参与字体缩放**。
 *
 * 命名偏离规范里的 `Icon`：`Icon` 是 `androidx.compose.material3.Icon`，
 * 同名 object 会和所有画图标的文件冲突。
 *
 * 图标是识别符号不是阅读内容，所以不跟随 fontScale；行变高时垂直居中即可。
 */
object IconSize {
    val sm = 16.dp   // 行内：Chip 内、行尾 chevron、按钮内、输入框前缀
    val md = 20.dp   // 默认：列表行首、工具栏、导航项、Sheet 标题旁
    val lg = 24.dp   // 强调：空状态、页面级操作、全屏工具条、品牌标识
}

/**
 * 控件固定尺寸（tokens.md §1.1 的 Button / Chip / Field / TopBar）。
 *
 * 命名偏离规范里的 `Button` / `Chip` / `Field` / `TopBar`：前三个都是 Compose 的
 * composable 名，直接撞名。数值与语义一一对应。
 *
 * 这里除 [chip] 外全部读作"最小高度"，实际高度由内容撑开。
 */
object ControlSize {
    val topBar = 56.dp
    val field = 44.dp            // 单行输入框外框高度，触区仍按 48dp 校验
    val button = 48.dp           // 主按钮
    val buttonSecondary = 40.dp  // 次按钮 / 文字按钮（触区 48dp）
    val chip = 22.dp             // 状态 Chip
}

/**
 * 图标容器（tokens.md §1.1 的 IconTile.*）。
 *
 * 命名偏离规范里的 `IconTile`：`dev.zakocoder.app.ui.IconTile` 已经是本项目的
 * composable，同名 object 会在同一个文件里撞名。
 *
 * 圆角按"圆角 / 边长"比例判定（tokens.md §2.1.1 唯一例外）：
 * 32/40dp 配 [Radius.sm]，64dp 配 [Radius.lg]（25% 比例，12dp 会显方）。
 */
object TileSize {
    val sm = 32.dp   // 列表行首
    val md = 40.dp   // 工具栏 / Sheet 标题
    val lg = 64.dp   // 空状态（原 84dp）
}
