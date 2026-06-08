package ru.fromchat.ui.components

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import ru.fromchat.Res
import ru.fromchat.google_sans_bold
import ru.fromchat.google_sans_bold_italic
import ru.fromchat.google_sans_italic
import ru.fromchat.google_sans_medium
import ru.fromchat.google_sans_medium_italic
import ru.fromchat.google_sans_regular
import ru.fromchat.google_sans_semibold
import ru.fromchat.google_sans_semibold_italic

@Composable
fun googleSansMaterialTypography() = Typography().let {
    val family = FontFamily(
        Font(Res.font.google_sans_regular, FontWeight.Normal, FontStyle.Normal),
        Font(Res.font.google_sans_italic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.google_sans_medium, FontWeight.Medium, FontStyle.Normal),
        Font(Res.font.google_sans_medium_italic, FontWeight.Medium, FontStyle.Italic),
        Font(Res.font.google_sans_semibold, FontWeight.SemiBold, FontStyle.Normal),
        Font(Res.font.google_sans_semibold_italic, FontWeight.SemiBold, FontStyle.Italic),
        Font(Res.font.google_sans_bold, FontWeight.Bold, FontStyle.Normal),
        Font(Res.font.google_sans_bold_italic, FontWeight.Bold, FontStyle.Italic),
    )

    it.copy(
        displayLarge = it.displayLarge.copy(fontFamily = family),
        displayMedium = it.displayMedium.copy(fontFamily = family),
        displaySmall = it.displaySmall.copy(fontFamily = family),
        headlineLarge = it.headlineLarge.copy(fontFamily = family),
        headlineMedium = it.headlineMedium.copy(fontFamily = family),
        headlineSmall = it.headlineSmall.copy(fontFamily = family),
        titleLarge = it.titleLarge.copy(fontFamily = family),
        titleMedium = it.titleMedium.copy(fontFamily = family),
        titleSmall = it.titleSmall.copy(fontFamily = family),
        bodyLarge = it.bodyLarge.copy(fontFamily = family),
        bodyMedium = it.bodyMedium.copy(fontFamily = family),
        bodySmall = it.bodySmall.copy(fontFamily = family),
        labelLarge = it.labelLarge.copy(fontFamily = family),
        labelMedium = it.labelMedium.copy(fontFamily = family),
        labelSmall = it.labelSmall.copy(fontFamily = family),
    )
}