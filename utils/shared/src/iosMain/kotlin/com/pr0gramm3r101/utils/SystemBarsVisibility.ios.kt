package com.pr0gramm3r101.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// TODO iOS 13+ uses scene-based API; status bar control requires native Swift/ObjC bridge
@Composable
actual fun rememberSystemBarsController(): ((Boolean) -> Unit)? {
    return remember {
        { _ -> }
    }
}
