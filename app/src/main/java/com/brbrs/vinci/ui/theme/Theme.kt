package com.brbrs.vinci.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.TextUnit
import com.brbrs.vinci.R

// ── Dark palette ──────────────────────────────────────────────────────────────
val NavyDeep      = Color(0xFF080C18)   // deeper, richer black-blue
val NavyMid       = Color(0xFF0E1525)
val NavySurface   = Color(0xFF161D30)
val NavySurface2  = Color(0xFF1E2640)   // elevated surface
val GlassWhite    = Color(0x18FFFFFF)
val GlassBorder   = Color(0x22FFFFFF)
val GlowPurple    = Color(0x339C27B0)   // ambient glow tint

// ── Purple accent (Vinci) ─────────────────────────────────────────────────────
val PurplePrimary    = Color(0xFFAB47BC)   // slightly lighter — pops on dark
val PurpleDeep       = Color(0xFF9C27B0)
val PurpleDim        = Color(0x999C27B0)
val PurpleGlow       = Color(0x4DAB47BC)   // for glow effects
val PurpleLight      = Color(0xFF7B1FA2)   // light mode primary
val PurpleLightSoft  = Color(0xFF9C27B0)   // light mode softer accent

// ── Semantic ──────────────────────────────────────────────────────────────────
val SlateText        = Color(0xFF94A3B8)
val SlateTextDim     = Color(0x66CBD5E1)
val White            = Color(0xFFF1F5F9)
val ErrorRed         = Color(0xFFEF5350)
val GreenOk          = Color(0xFF4CAF78)
val AmberWarn        = Color(0xFFFFB300)
val AmberGlow        = Color(0x33FFB300)

// Aliases kept for backward compatibility
val CyanPrimary  = PurplePrimary
val CyanDim      = PurpleDim
val CyanLight    = PurpleLight

// ── Light palette ─────────────────────────────────────────────────────────────
// Warm lavender-tinted whites — feels considered, not generic
val LightBg         = Color(0xFFF2EEFB)   // confident lavender background
val LightSurface    = Color(0xFFFBF9FE)   // warm near-white with purple cast
val LightSurface2   = Color(0xFFE9E2F5)   // visible lavender for sections
val LightSurface3   = Color(0xFFDDD4EF)   // stronger lavender for elevated cards
val LightBorder     = Color(0x3A7B1FA2)   // purple-tinted border — visible
val LightBorderSoft = Color(0x229C27B0)   // softer purple border
val LightBorderMed  = Color(0x339C27B0)   // medium — for cards
val LightText       = Color(0xFF180F22)   // deep purple-black
val LightTextDim    = Color(0xFF5D4E75)   // readable purple-grey
val LightGlow       = Color(0x339C27B0)   // proper purple glow on light

val LocalIsDark = compositionLocalOf { true }

// ── Expressive shapes (2.0.0-beta01) ───────────────────────────────────────────
// Asymmetric "morph" corners for hero surfaces (contact cards, avatars) —
// replaces the single uniform 12/16dp radius used everywhere pre-2.0.
// Kept as explicit Shape values (not MaterialTheme.shapes) so buttons, dialogs
// and sheets elsewhere in the app are unaffected.
val ExpressiveCardShape: RoundedCornerShape = RoundedCornerShape(
    topStart = 20.dp, topEnd = 8.dp, bottomEnd = 20.dp, bottomStart = 8.dp,
)
val ExpressiveAvatarShape: RoundedCornerShape = RoundedCornerShape(
    topStart = 18.dp, topEnd = 8.dp, bottomEnd = 18.dp, bottomStart = 8.dp,
)
val ExpressiveFabShape: RoundedCornerShape = RoundedCornerShape(28.dp)

// Tag-chip color rotation — spreads accent duty across secondary/tertiary
// roles instead of primary carrying every badge in the app.
val ExpressiveTagPalette: List<Color> = listOf(PurplePrimary, Color(0xFFCE93D8), AmberWarn, GreenOk)

// Per-interaction-type color hints, all within the purple family so a timeline still
// reads as one coherent app, while letting you scan by color: calls stay the base
// purple, meetings lean violet-blue, emails lean pink, messages lean teal-purple.
fun interactionTypeColor(type: String): Color = when {
    type.startsWith("Social Media") -> Color(0xFFCE93D8)
    type == "Meeting" -> Color(0xFF7E57C2)
    type == "Email"   -> Color(0xFFBA68C8)
    type == "Message" -> Color(0xFF8E7CC3)
    type == "Other"   -> SlateText
    else              -> PurplePrimary // "Call"
}

// ── Color schemes ─────────────────────────────────────────────────────────────
private val DarkColorScheme = darkColorScheme(
    primary             = PurplePrimary,
    onPrimary           = NavyDeep,
    primaryContainer    = Color(0xFF2D1B33),
    onPrimaryContainer  = Color(0xFFE8B4F0),
    secondary           = Color(0xFFCE93D8),
    onSecondary         = NavyDeep,
    secondaryContainer  = Color(0xFF1E1428),
    onSecondaryContainer= Color(0xFFDDB8E8),
    tertiary            = AmberWarn,
    onTertiary          = NavyDeep,
    background          = NavyDeep,
    onBackground        = White,
    surface             = NavyMid,
    onSurface           = White,
    surfaceVariant      = NavySurface,
    onSurfaceVariant    = SlateText,
    surfaceContainer    = NavySurface2,
    outline             = GlassBorder,
    outlineVariant      = Color(0x12FFFFFF),
    error               = ErrorRed,
    scrim               = Color(0x99000000),
)

private val LightColorScheme = lightColorScheme(
    primary             = PurpleLight,
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFFEDD8F5),
    onPrimaryContainer  = Color(0xFF3D0050),
    secondary           = PurpleLightSoft,
    onSecondary         = Color.White,
    secondaryContainer  = LightSurface3,
    onSecondaryContainer= LightText,
    tertiary            = Color(0xFFE65100),
    onTertiary          = Color.White,
    background          = LightBg,
    onBackground        = LightText,
    surface             = LightSurface,
    onSurface           = LightText,
    surfaceVariant      = LightSurface2,
    onSurfaceVariant    = LightTextDim,
    surfaceContainer    = LightSurface3,
    outline             = LightBorderMed,
    outlineVariant      = LightBorderSoft,
    error               = Color(0xFFB00020),
    scrim               = Color(0x66000000),
)

// ── Font families ─────────────────────────────────────────────────────────────

// DM Serif Display — used for display/headline styles (contact names, screen titles)
val DMSerifDisplayFamily = FontFamily(
    Font(R.font.dm_serif_display_regular, FontWeight.Normal),
)

// Inter Tight — used for all UI body/label/title text
val InterTightFamily = FontFamily(
    Font(R.font.inter_tight_thin,        FontWeight.Thin),
    Font(R.font.inter_tight_extra_light, FontWeight.ExtraLight),
    Font(R.font.inter_tight_light,       FontWeight.Light),
    Font(R.font.inter_tight_regular,     FontWeight.Normal),
    Font(R.font.inter_tight_medium,      FontWeight.Medium),
    Font(R.font.inter_tight_semi_bold,   FontWeight.SemiBold),
    Font(R.font.inter_tight_bold,        FontWeight.Bold),
    Font(R.font.inter_tight_extra_bold,  FontWeight.ExtraBold),
    Font(R.font.inter_tight_black,       FontWeight.Black),
)

// ── Typography ────────────────────────────────────────────────────────────────

fun buildTypography(displayFont: FontFamily, bodyFont: FontFamily) = Typography(
    displayLarge   = TextStyle(fontFamily = displayFont, fontSize = 44.sp, fontWeight = FontWeight.Normal,   lineHeight = 52.sp, letterSpacing = (-0.5).sp),
    headlineLarge  = TextStyle(fontFamily = displayFont, fontSize = 34.sp, fontWeight = FontWeight.Normal,   lineHeight = 42.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontFamily = displayFont, fontSize = 26.sp, fontWeight = FontWeight.Normal,   lineHeight = 34.sp, letterSpacing = (-0.2).sp),
    headlineSmall  = TextStyle(fontFamily = displayFont, fontSize = 22.sp, fontWeight = FontWeight.Normal,   lineHeight = 30.sp),
    titleLarge     = TextStyle(fontFamily = bodyFont,    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium    = TextStyle(fontFamily = bodyFont,    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.15).sp),
    titleSmall     = TextStyle(fontFamily = bodyFont,    fontSize = 13.sp, fontWeight = FontWeight.Medium,   letterSpacing = (-0.1).sp),
    bodyLarge      = TextStyle(fontFamily = bodyFont,    fontSize = 15.sp, fontWeight = FontWeight.Normal,   lineHeight = 24.sp, letterSpacing = (-0.1).sp),
    bodyMedium     = TextStyle(fontFamily = bodyFont,    fontSize = 13.sp, fontWeight = FontWeight.Normal,   lineHeight = 20.sp, letterSpacing = (-0.05).sp),
    bodySmall      = TextStyle(fontFamily = bodyFont,    fontSize = 11.sp, fontWeight = FontWeight.Normal,   lineHeight = 16.sp),
    labelLarge     = TextStyle(fontFamily = bodyFont,    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.sp),
    labelMedium    = TextStyle(fontFamily = bodyFont,    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.06.sp),
    labelSmall     = TextStyle(fontFamily = bodyFont,    fontSize = 10.sp, fontWeight = FontWeight.Medium,   letterSpacing = 0.08.sp),
)

// Default: system serif + system sans-serif
val VinciTypography = buildTypography(FontFamily.Serif, FontFamily.SansSerif)

// Paired: DM Serif Display headlines + Inter Tight body
val VinciTypographyPaired = buildTypography(DMSerifDisplayFamily, InterTightFamily)

/** Returns typography scaled by [scale]. */
fun scaledTypography(scale: Float, useCustomFont: Boolean = false): Typography {
    val base = if (useCustomFont) VinciTypographyPaired else VinciTypography
    fun TextStyle.scaled() = copy(
        fontSize   = fontSize * scale,
        lineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight * scale else lineHeight,
    )
    return Typography(
        displayLarge   = base.displayLarge.scaled(),
        headlineLarge  = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(),
        headlineSmall  = base.headlineSmall.scaled(),
        titleLarge     = base.titleLarge.scaled(),
        titleMedium    = base.titleMedium.scaled(),
        titleSmall     = base.titleSmall.scaled(),
        bodyLarge      = base.bodyLarge.scaled(),
        bodyMedium     = base.bodyMedium.scaled(),
        bodySmall      = base.bodySmall.scaled(),
        labelLarge     = base.labelLarge.scaled(),
        labelMedium    = base.labelMedium.scaled(),
        labelSmall     = base.labelSmall.scaled(),
    )
}

// True-black OLED variant of the dark scheme -- same accents, pure-black surfaces so
// each-pixel-off OLED panels can actually turn those pixels off (saves battery, and the
// purple accent reads more strikingly against true black than against navy).
private val OledColorScheme = DarkColorScheme.copy(
    background         = Color.Black,
    surface            = Color.Black,
    surfaceVariant     = Color(0xFF0A0A0A),
    surfaceContainer   = Color(0xFF111111),
)

val LocalIsOled = compositionLocalOf { false }

@Composable
fun VinciTheme(
    isDark: Boolean = true,
    isOled: Boolean = false,
    textScale: Float = 1.0f,
    useCustomFont: Boolean = false,
    content: @Composable () -> Unit,
) {
    val typography = when {
        textScale != 1.0f -> scaledTypography(textScale, useCustomFont)
        useCustomFont     -> VinciTypographyPaired
        else              -> VinciTypography
    }
    val colorScheme = when {
        isOled -> OledColorScheme
        isDark -> DarkColorScheme
        else    -> LightColorScheme
    }
    CompositionLocalProvider(LocalIsDark provides isDark, LocalIsOled provides isOled) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = typography,
            content     = content,
        )
    }
}

/**
 * The background gradient's top-stop color shifts subtly across the day: deeper near
 * midnight, a touch warmer around dusk/dawn. Used behind [NavyDeep]-based screen
 * backgrounds; a no-op (returns [NavyDeep]) outside dark/OLED modes or when called with
 * light-scheme colors, since the effect only reads correctly against a dark backdrop.
 */
fun timeOfDayTint(hourOfDay: Int, base: Color): Color {
    // 0-5: deep night (slightly darker/cooler); 6-10: dawn (faint warm lift);
    // 11-17: day (neutral, same as base); 18-21: dusk (warm lift); 22-23: night.
    val warmLift = Color(0xFF2A1B10) // subtle warm brown-amber, mixed in at low alpha
    val coolDeepen = Color(0xFF000000)
    val blend: Pair<Color, Float> = when (hourOfDay) {
        in 0..4   -> coolDeepen to 0.12f
        in 5..8   -> warmLift to 0.05f
        in 18..20 -> warmLift to 0.08f
        in 21..23 -> coolDeepen to 0.06f
        else      -> base to 0f
    }
    val (tint, amount) = blend
    if (amount == 0f) return base
    return Color(
        red   = base.red   * (1 - amount) + tint.red   * amount,
        green = base.green * (1 - amount) + tint.green * amount,
        blue  = base.blue  * (1 - amount) + tint.blue  * amount,
        alpha = base.alpha,
    )
}
