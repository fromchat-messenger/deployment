package ru.fromchat.ui.main.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import ru.fromchat.ui.components.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pr0gramm3r101.components.Category
import com.pr0gramm3r101.components.ListItem
import com.pr0gramm3r101.utils.verticalScroll
import org.jetbrains.compose.resources.stringResource
import ru.fromchat.Res
import ru.fromchat.about
import ru.fromchat.change_server
import ru.fromchat.change_server_d
import ru.fromchat.settings
import ru.fromchat.settings_category_account
import ru.fromchat.settings_category_account_d
import ru.fromchat.settings_category_appearance
import ru.fromchat.settings_category_appearance_d
import ru.fromchat.settings_category_devices
import ru.fromchat.settings_category_devices_d
import ru.fromchat.settings_category_notifications
import ru.fromchat.settings_category_notifications_d
import ru.fromchat.logs_title
import ru.fromchat.settings_hub_about_sub
import ru.fromchat.settings_hub_logs_sub
import ru.fromchat.ui.LocalNavController
import ru.fromchat.ui.main.mainPagerBottomInset

val SettingsStepHorizontalPadding = 24.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsTab() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val navController = LocalNavController.current

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = {
                    Text(stringResource(Res.string.settings), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll()
                .mainPagerBottomInset()
                .padding(innerPadding)
        ) {
            Category(Modifier.padding(top = 16.dp)) {
                ListItem(
                    headline = stringResource(Res.string.settings_category_account),
                    supportingText = stringResource(Res.string.settings_category_account_d),
                    onClick = { navController.navigate(SettingsRoutes.Account) },
                    leadingContent = { Icon(Icons.Filled.AccountCircle, null) },
                    divider = true
                )

                ListItem(
                    headline = stringResource(Res.string.settings_category_devices),
                    supportingText = stringResource(Res.string.settings_category_devices_d),
                    onClick = { navController.navigate(SettingsRoutes.Devices) },
                    leadingContent = { Icon(Icons.Filled.Devices, null) },
                    divider = true
                )

                ListItem(
                    headline = stringResource(Res.string.settings_category_appearance),
                    supportingText = stringResource(Res.string.settings_category_appearance_d),
                    onClick = { navController.navigate(SettingsRoutes.Appearance) },
                    leadingContent = { Icon(Icons.Filled.Palette, null) },
                    divider = true
                )

                ListItem(
                    headline = stringResource(Res.string.settings_category_notifications),
                    supportingText = stringResource(Res.string.settings_category_notifications_d),
                    onClick = { navController.navigate(SettingsRoutes.Notifications) },
                    leadingContent = { Icon(Icons.Filled.Notifications, null) },
                    divider = true
                )

                ListItem(
                    headline = stringResource(Res.string.change_server),
                    supportingText = stringResource(Res.string.change_server_d),
                    onClick = { navController.navigate(SettingsRoutes.ServerConfig) },
                    leadingContent = { Icon(Icons.Filled.Storage, null) },
                    divider = true
                )

                ListItem(
                    headline = stringResource(Res.string.about),
                    supportingText = stringResource(Res.string.settings_hub_about_sub),
                    onClick = { navController.navigate(SettingsRoutes.About) },
                    leadingContent = { Icon(Icons.Filled.Info, null) },
                    divider = true
                )

                ListItem(
                    headline = stringResource(Res.string.logs_title),
                    supportingText = stringResource(Res.string.settings_hub_logs_sub),
                    onClick = { navController.navigate(SettingsRoutes.Logs) },
                    leadingContent = { Icon(Icons.Outlined.BugReport, null) }
                )
            }
        }
    }
}