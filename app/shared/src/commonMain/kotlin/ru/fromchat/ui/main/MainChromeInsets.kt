package ru.fromchat.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class MainChromeInsets(
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp,
) {
    fun asPaddingValues(
        extraTop: Dp = 0.dp,
        extraBottom: Dp = 0.dp,
    ): PaddingValues = PaddingValues(
        top = top + extraTop,
        bottom = bottom + extraBottom,
    )
}

val LocalMainChromeInsets = staticCompositionLocalOf { MainChromeInsets() }

@Composable
fun Modifier.mainPagerBottomInset(): Modifier =
    padding(bottom = LocalMainChromeInsets.current.bottom)
