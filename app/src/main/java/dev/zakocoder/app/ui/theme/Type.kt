package dev.zakocoder.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字阶六档（tokens.md §3.1）。
 *
 * ## 字重为什么只有 400 / 500
 *
 * 全站只有 [FontWeight.Normal] 和 [FontWeight.Medium]。**没有 SemiBold，没有 Bold，一个都不留。**
 *
 * 更硬的理由不是审美，是 Android 上字重根本不可靠：系统字体（中文为思源黑体 / 厂商定制字体）
 * 不保证提供真正的 Medium 字重，缺字重时 Compose 会回退到 Regular 或做伪粗。
 * 也就是说 **任何依赖 500 才能读出来的层级，在某些设备上会直接消失**。
 * 层级必须能在"500 退化成 400"的假设下依然成立，所以只能靠：
 * 尺寸（20/16/14/12/11）、颜色（textPrimary / textSecondary / textTertiary）、留白。
 *
 * 这三条通道不受字体回退影响，字重受。
 *
 * ## 字体族
 *
 * 正文标题用系统字体（FontFamily.Default，中文即系统思源黑体）；
 * 代码、终端、Model ID、路径、Token 数字用 [FontFamily.Monospace]。
 * **不引入自定义字体文件** —— 体积 + 中文字形覆盖率风险。
 */
object Type {
    /** 20 / 28 / -0.2 / 500 —— 页面标题，每屏 1 个。 */
    val title = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.2).sp,
    )

    /** 16 / 24 / -0.1 / 400 —— Sheet 标题、区块标题。 */
    val section = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.1).sp,
    )

    /** 14 / 20 / 0 / 500 —— 卡片标题、列表行主标题、空状态标题、导航项。 */
    val item = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    )

    /** 14 / 20 / 0 / 400 —— 正文默认：对话内容、说明、按钮文字（按钮用 [bodyStrong]）。 */
    val body = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 12 / 17 / 0 / 400 —— 辅助说明：时间、路径、Token 统计、次要提示。 */
    val meta = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 11 / 15 / +0.2 / 500 —— 状态 Chip、字段标签、模型 ID、终端默认字号、权限文字。 */
    val label = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    )

    // —— 同尺寸的强调 / 长文变体。不新增字号，只是换字重或行高 ——

    /** 14 / 20 / 500 —— 按钮文字、行内强调。对应 M3 的 `titleSmall` / `labelLarge`。 */
    val bodyStrong = body.copy(fontWeight = FontWeight.Medium)

    /** 12 / 17 / 500 —— 组标签、行内次级强调。对应 M3 的 `labelMedium`。 */
    val metaStrong = meta.copy(fontWeight = FontWeight.Medium)

    /**
     * 14 / 22 / 400 —— 长文行高覆盖（tokens.md §3.1）。
     * 连续正文使用 22sp 行高：对话消息、错误说明、Markdown 段落。
     * 界面元素（列表行、按钮、Chip）**不适用**此覆盖。
     */
    val bodyLong = body.copy(lineHeight = 22.sp)

    /** 对话沿用用户选择的紧凑字号，不随普通界面正文放大。 */
    val message = body.copy(fontSize = 12.sp, lineHeight = 18.sp)

    /** 12 / 20 / 400 —— 长文覆盖的 meta 版。 */
    val metaLong = meta.copy(lineHeight = 20.sp)

    /** 12 / 18 / 400 monospace —— 行内代码、diff、日志（tokens.md §8.13）。 */
    val code = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
    )

    /**
     * 11 / 16 / 400 monospace —— 终端默认（tokens.md §4.6）。
     * 终端字号由用户设置覆盖（11–28sp），这里只是默认值。
     */
    val terminal = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
    )
}

/** 普通界面全部 Typography 槽位；聊天与终端保留各自的紧凑字阶。 */
internal val WorkbenchTypography = Typography(
    // —— 六档本体 ——
    headlineMedium = Type.title,     // 20 / 28 / 500
    titleLarge = Type.section,       // 16 / 24 / 400
    titleMedium = Type.item,         // 14 / 20 / 500
    bodyMedium = Type.body,          // 14 / 20 / 0    / 400
    bodySmall = Type.meta,           // 12 / 17 / 0    / 400
    labelSmall = Type.label,         // 11 / 15 / +0.2 / 500

    // —— 兼容槽位：全部折叠到六档 ——
    displayLarge = Type.title,
    displayMedium = Type.title,
    displaySmall = Type.section,
    headlineLarge = Type.title,
    headlineSmall = Type.item,
    titleSmall = Type.bodyStrong,                    // 14 / 20 / 500
    bodyLarge = Type.body,                           // 表单与列表不继承长文行距
    labelLarge = Type.bodyStrong,                    // 按钮文字 14 / 500
    labelMedium = Type.metaStrong,                   // 12 / 17 / 500
)

/*
 * 500 的最终判据（tokens.md §3.1）：**看文本长度，不看元素类型。**
 *
 *     单条文本 ≤ 8 个汉字（或 ≤ 16 个西文字符）→ 允许 500
 *     超过 8 个汉字 → 一律 400
 *
 * 元素类型枚举不完（组标签、字段标签、行尾短值、折叠摘要、危险操作文字、Sheet 标题……
 * 每加一种都要吵一次）；而"只有短文本才加粗"是一条不会错的物理约束 ——
 * 加粗的作用是让**短**文本在扫视时跳出来，一段话加粗只会变成噪音。
 *
 * 按此判据，下面这些"白名单外"的用法合法，不需要单独申请：
 * - 组标签（`助手` / `终端`，2 字）      → [Type.metaStrong]
 * - 字段标签（`模型` / `端点`，≤4 字）    → [Type.metaStrong]
 * - 行尾危险操作（`移除订阅`，4 字）      → [Type.bodyStrong] + danger
 * - 折叠头摘要（`未安装 · 需要时安装`，10 字）→ [Type.meta]（**400**，靠颜色和右对齐区分）
 *
 * 反向检查：某处 500 的文本超过 8 字 → 改回 400，并检查层级是不是本该由尺寸或留白承担。
 */
