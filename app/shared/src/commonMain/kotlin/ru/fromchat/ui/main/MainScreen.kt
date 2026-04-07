package ru.fromchat.ui.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import ru.fromchat.Res
import ru.fromchat.*
import ru.fromchat.api.ApiClient
import ru.fromchat.utils.exclude
import ru.fromchat.ui.main.settings.SettingsTab
import ru.fromchat.ui.profile.ProfileScreen

private const val PAGE_CHATS = 0
private const val PAGE_CONTACTS = 1
private const val PAGE_SETTINGS = 2
private const val PAGE_PROFILE = 3
private const val PAGE_COUNT = 4

@Suppress("AssignedValueIsNeverRead")
@Composable
fun MainScreen() {
    val pagerState = rememberPagerState(
        initialPage = PAGE_CHATS,
        pageCount = { PAGE_COUNT },
    )
    val scope = rememberCoroutineScope()

    // Pager is the single source of truth; tabs only call animateScrollToPage (no write/read loop).
    val selectedPage = pagerState.currentPage

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedPage == PAGE_CHATS,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(PAGE_CHATS) }
                    },
                    label = { Text(stringResource(Res.string.chats)) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) }
                )
                NavigationBarItem(
                    selected = selectedPage == PAGE_CONTACTS,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(PAGE_CONTACTS) }
                    },
                    label = { Text(stringResource(Res.string.contacts)) },
                    icon = { Icon(Icons.Filled.Contacts, contentDescription = null) }
                )
                NavigationBarItem(
                    selected = selectedPage == PAGE_SETTINGS,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(PAGE_SETTINGS) }
                    },
                    label = { Text(stringResource(Res.string.settings)) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) }
                )
                NavigationBarItem(
                    selected = selectedPage == PAGE_PROFILE,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(PAGE_PROFILE) }
                    },
                    label = { Text(stringResource(Res.string.profile)) },
                    icon = { Icon(Icons.Filled.Person, contentDescription = null) }
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsetsSides.Top),
        modifier = Modifier.imePadding()
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            beyondViewportPageCount = 1,
        ) { page ->
            when (page) {
                PAGE_CHATS -> ChatsTab()
                PAGE_CONTACTS -> ContactsTab()
                PAGE_SETTINGS -> SettingsTab()
                PAGE_PROFILE -> {
                    val currentUserId = ApiClient.user?.id
                    ProfileScreen(
                        userId = currentUserId,
                        onBack = {},
                        onChat = { _ -> },
                        modifier = Modifier.fillMaxSize(),
                        onOpenSettings = {
                            scope.launch { pagerState.animateScrollToPage(PAGE_SETTINGS) }
                        }
                    )
                }
                else -> Unit
            }
        }
    }
}
