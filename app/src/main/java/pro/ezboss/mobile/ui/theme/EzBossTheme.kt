package pro.ezboss.mobile.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared mobile tokens, aligned with the authenticated web workspace. */
object EzBossDesign {
    val Orange = Color(0xFFEA580C)
    val OrangeBright = Color(0xFFF97316)
    val OrangeDark = Color(0xFFC2410C)
    val Canvas = Color(0xFFF9FAFB)
    val Surface = Color.White
    val Ink = Color(0xFF111827)
    val Muted = Color(0xFF6B7280)
    val Border = Color(0xFFE5E7EB)
    val Navy = Color(0xFF0F172A)
    val NavySurface = Color(0xFF1E293B)
    val DrawerText = Color(0xFFCBD5E1)
    val Link = Color(0xFF2563EB)
    val PagePadding = 16.dp
    val Gap = 12.dp
    val CardShape = RoundedCornerShape(12.dp)
    val ControlShape = RoundedCornerShape(8.dp)
    val HeaderBrush = Brush.horizontalGradient(listOf(OrangeBright, Orange))
}

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable fun EzBossTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = EzBossDesign.OrangeDark, onPrimary = Color.White,
            primaryContainer = Color(0xFFFFEDD5), onPrimaryContainer = EzBossDesign.OrangeDark,
            secondary = EzBossDesign.Link, background = EzBossDesign.Canvas,
            surface = EzBossDesign.Surface, onSurface = EzBossDesign.Ink,
            onBackground = EzBossDesign.Ink, onSurfaceVariant = EzBossDesign.Muted,
            outline = Color(0xFFD1D5DB), outlineVariant = EzBossDesign.Border,
            surfaceContainerHigh = Color.White,
        ),
        typography = AppTypography,
        shapes = Shapes(small = EzBossDesign.ControlShape, medium = EzBossDesign.CardShape, large = RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().background(EzBossDesign.HeaderBrush).padding(EzBossDesign.PagePadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Status colors convey document/client state, independently of brand accents. */
@Composable fun StatusBadge(text: String, state: String?) {
    val (background, foreground) = when (state) {
        "invoice", "accepted" -> Color(0xFFDCFCE7) to Color(0xFF166534)
        "estimate", "sent" -> Color(0xFFDBEAFE) to Color(0xFF1D4ED8)
        "viewed" -> Color(0xFFF3E8FF) to Color(0xFF7E22CE)
        "denied", "expired" -> Color(0xFFFEE2E2) to Color(0xFFB91C1C)
        "change-order", "on-hold" -> Color(0xFFFFEDD5) to Color(0xFF9A3412)
        else -> Color(0xFFF3F4F6) to Color(0xFF4B5563)
    }
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}
