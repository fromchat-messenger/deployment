package com.pr0gramm3r101.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalSystemBarsVisibility = compositionLocalOf<((Boolean) -> Unit)?> { null }

@Composable
expect fun rememberSystemBarsController(): ((Boolean) -> Unit)?
