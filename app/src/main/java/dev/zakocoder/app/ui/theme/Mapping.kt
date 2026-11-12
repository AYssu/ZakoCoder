package dev.zakocoder.app.ui.theme

import androidx.compose.ui.text.font.FontWeight

/**
 * 旧值 → 令牌的映射函数（tokens.md §1.2 / §2.3 / §3.2 / §6.3）。
 *
 * 存在的意义只有一个：让"替换硬编码"变成机械操作，而不是每次重新做一次审美判断。
 * 第二批判定页面布局时，遇到 `14.dp` / `13.5.sp` / `RoundedCornerShape(18.dp)`
 * 这类旧值，先过一遍这里的函数，再决定要不要手写令牌名。
 *
 * 新写的代码**不要**调用这些函数，直接用 [Space] / [Radius] / [Type] / [IconSize]。
 */

/**
 * 字号归位（tokens.md §3.2，20 种 → 6 档）。
 *
 * 判定是先按尺寸区间，再按原字重微调（微调由调用方决定，例如原 SemiBold 的 14sp
 * 应该映射到 `Type.bodyStrong` 而不是 `Type.body`）。
 */
fun legacyFontSp(old: Float): Float = when {
    old < 11f -> 11f    // 9, 10, 10.5 → 低于 11sp 在手机上不可读
    old < 12.5f -> 12f  // 11, 11.5, 12
    old < 15f -> 14f    // 12.5, 13, 13.5, 14, 14.5
    old < 17f -> 14f    // 卡片标题
    old < 21f -> 16f    // 分组标题
    else -> 20f         // 页面标题
}

/**
 * 字重归位：**任何 600/700 都降到 500，不保留例外。**
 *
 * 降级而不是报错，是因为这个函数的调用点会出现在业务代码里；
 * 让它静默产出 500，比让整个文件编译不过、逼开发者临时写一个 Bold 绕过要好。
 * 真正防止例外繁殖的是 CI 里的 `grep FontWeight.Bold` 归零检查（tokens.md §10）。
 */
fun legacyWeight(old: FontWeight): FontWeight =
    if (old.weight >= FontWeight.Medium.weight) FontWeight.Medium else FontWeight.Normal

/**
 * 页面平面圆角归位（tokens.md §2.3）。
 *
 * ≤8 的徽标 / 行内元素归 [Radius.sm]，其余全部归 [Radius.md]。
 *
 * **不要用这个函数处理 Dialog / Sheet / 内嵌预览** —— 它们属于浮层平面，
 * 用 [overlayRadiusDp]。旧版单函数 `old <= 20 -> 16` 的写法已废弃：
 * 它会把 `DetailGroup` 18dp、`DashedTerminalAction` 18dp、加载骨架 18dp
 * 全部判成 16，让页面平面混进浮层圆角。
 *
 * 先分平面，再定档位，是这条规则的唯一正确用法。
 */
fun legacyRadiusDp(old: Int): Int = if (old <= 8) 8 else 12

/** 浮层平面圆角归位（tokens.md §2.3）：Dialog / 内嵌面板归 16，Sheet 归 24。 */
fun overlayRadiusDp(old: Int): Int = if (old <= 20) 16 else 24

/**
 * 图标尺寸归位（tokens.md §6.3，12 种 → 3 档）。
 *
 * 12/14 → 16（12dp 的勾太细，提到 16 才看得见），18/22 → 20，≥24 → 24。
 */
fun legacyIconDp(old: Int): Int = when {
    old <= 16 -> 16
    old <= 22 -> 20
    else -> 24
}

/**
 * 间距归位（tokens.md §1.2，15 种 → 6 档）。
 *
 * 10dp 和 18dp 各自对应两个目标值，取决于原来的用途，所以需要 [LegacySpaceIntent]。
 * 默认按 [LegacySpaceIntent.INNER] 处理，因为绝大多数旧值是元素内部间距。
 */
fun legacySpaceDp(old: Int, intent: LegacySpaceIntent = LegacySpaceIntent.INNER): Int = when {
    old <= 2 -> 0        // 纯视觉微调，用留白或字距替代
    old <= 6 -> 4        // 图标—文字、圆点—文字
    old <= 9 -> 8        // Chip 横向内距、行内并列元素
    old == 10 -> if (intent == LegacySpaceIntent.CONTAINER_PADDING) 12 else 8
    old <= 14 -> 12      // 13 / 14 → 卡片内距
    old <= 16 -> 16      // 15 → 页面横向留白
    old <= 18 -> if (intent == LegacySpaceIntent.BLOCK_GAP) 20 else 16
    else -> 24           // 30 → 大区块间距
}

/** 旧间距值原本的用途，决定 10dp / 18dp 归到哪一档。 */
enum class LegacySpaceIntent {
    /** 元素内部紧凑间距（Chip 垂直内距、紧凑按钮）。 */
    INNER,

    /** 容器内距（卡片、Sheet、输入框）。 */
    CONTAINER_PADDING,

    /** 区块与区块之间。 */
    BLOCK_GAP,
}

/**
 * 图标容器尺寸归位（tokens.md §1.1「图标容器尺寸的旧值映射」）。
 *
 * 源码里 `IconTile` 默认 **46dp、圆角硬编码 13dp**，另有 4 处调用点用 `Modifier.size()`
 * 覆盖成 34 / 36 / 38 / 36 —— **圆角全都是 13dp**。这就是 13dp 越界一直清不掉的真正原因：
 * 改调用点没用，必须改组件本身，让 `size` 与 `radius` 都由调用方传入。
 *
 * 归并原则：**就近且不放大**。
 * - 34 / 36 / 38（列表行首）→ 32
 * - 46（卡片内对象标识）→ 40
 * - 46（空状态）→ 64 —— 空状态是唯一"放大"的方向，它是全页唯一的视觉锚点
 *
 * 46 这一个旧值对应两个目标，所以必须由 [TileRole] 决定，不能只看尺寸。
 */
fun legacyTileDp(old: Int, role: TileRole = TileRole.ROW): Int = when {
    role == TileRole.EMPTY -> 64
    role == TileRole.CARD -> 40
    old >= 44 -> 40          // 46 落在行首位置时也归 40，不硬塞进 32
    else -> 32
}

/** 图标容器的使用场景。决定 46dp 这个旧值归 40 还是 64。 */
enum class TileRole {
    /** 列表行首 / 选择行行首（34 / 36 / 38 / 46 → 32）。 */
    ROW,

    /** 卡片内对象标识（46 → 40）。 */
    CARD,

    /** 空状态图标（46 → 64）。 */
    EMPTY,
}

/**
 * 图标容器的圆角：**跟着边长走**（tokens.md §2.1.1 唯一例外，按"圆角 / 边长"比例判定）。
 *
 * 32 → 8（25%）、40 → 12（30%）、64 → 16（25%）。
 * 64dp 配 12dp 只有 18.75%，会显方；32dp 配 12dp 会变成胶囊，丢掉"方块"读法。
 *
 * **尺寸与圆角必须成对收敛** —— 只用 [legacyTileDp] 不用这个函数，等于把越界从
 * "尺寸不对"换成"圆角不对"。
 */
fun tileRadiusDp(tileDp: Int): Int = when {
    tileDp <= 32 -> 8
    tileDp <= 40 -> 12
    else -> 16
}
