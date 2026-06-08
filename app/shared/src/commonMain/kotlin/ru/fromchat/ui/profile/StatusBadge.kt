package ru.fromchat.ui.profile

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import ru.fromchat.Res
import ru.fromchat.api.ApiClient
import ru.fromchat.cd_similar_verified
import ru.fromchat.cd_verified_account

@Composable
fun StatusBadge(
    verified: Boolean?,
    userId: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp
) {
    var isSimilarToVerified by remember(userId, verified) {
        mutableStateOf(false)
    }

    LaunchedEffect(verified, userId, ApiClient.token) {
        isSimilarToVerified = !(
            verified == true ||
            userId == null ||
            ApiClient.token == null
        ) && withContext(Dispatchers.Default) {
            runCatching {
                ApiClient.checkSimilarity(userId)
            }.getOrNull()
        }?.isSimilar == true
    }

    when {
        verified == true -> Icon(
            imageVector = Icons.Filled.Verified,
            contentDescription = stringResource(Res.string.cd_verified_account),
            modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.primary
        )

        isSimilarToVerified -> Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = stringResource(Res.string.cd_similar_verified),
            modifier = modifier.size(size),
            tint = Color(0xFFFFA000)
        )
    }
}
