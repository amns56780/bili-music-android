package com.bilimusic.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = PinkPrimaryLight,
    onPrimary = PinkOnPrimaryLight,
    primaryContainer = PinkContainerLight,
    onPrimaryContainer = PinkOnContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    error = ErrorLight,
    onError = OnErrorLight,
)

private val DarkColors = darkColorScheme(
    primary = PinkPrimaryDark,
    onPrimary = PinkOnPrimaryDark,
    primaryContainer = PinkContainerDark,
    onPrimaryContainer = PinkOnContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    error = ErrorDark,
    onError = OnErrorDark,
)

/**
 * 全局主题：Material 3 + Android 12+ 动态取色 + 跟随系统深色模式。
 * 字体缩放由系统 fontScale 驱动，Typography 全部使用 sp，1.0~1.3x 均不裁切。
 */
@Composable
fun BiliMusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }
    val colorScheme = baseScheme.withReadableContainers(darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = BiliTypography,
        content = content,
    )
}

/**
 * 深色模式下动态取色常常让 `surfaceContainerLow`（Card 的默认底色）跟纯黑背景几乎同色，
 * 卡片就「糊」在背景里看不出边界。这里统一把各级容器色往主题表面色上提亮一档，
 * 保证卡片/弹窗在深色下也有清晰层次（不改变主色）。
 */
private fun ColorScheme.withReadableContainers(dark: Boolean): ColorScheme {
    if (!dark) return this
    // 实测结论（Android 16 动态取色）：
    // ① background 和 surface 是同一个颜色，只提亮 surface 会把整屏一起提亮；
    // ② Card 的底色跟着 surface 走（surfaceContainer* 只影响导航栏之类）。
    // 所以正确做法是把 background 压深、surface 略提亮，卡片才有清晰边界。
    return copy(
        background = lerp(surface, Color.Black, 0.35f),
        surface = lerp(surface, Color.White, 0.08f),
        surfaceContainerLow = lerp(surface, Color.White, 0.10f),
        surfaceContainer = lerp(surface, Color.White, 0.14f),
        surfaceContainerHigh = lerp(surface, Color.White, 0.18f),
        surfaceContainerHighest = lerp(surface, Color.White, 0.22f),
    )
}
