package com.episode6.meetingminder.ui.navigation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.episode6.meetingminder.R
import com.episode6.meetingminder.ui.day.DayScreen
import com.episode6.meetingminder.ui.day.DayViewModel
import com.episode6.meetingminder.ui.licenses.LicensesScreen
import com.episode6.meetingminder.ui.util.ComingSoonScreen
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * The wiring layer: the only place ViewModels are obtained and their state collected,
 * and where one-shot effects (snackbars, activity launchers, later the share sheet and
 * deep links) are handled. Screens below it only take state + callbacks.
 */
@Composable
fun MeetingMinderNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Route.Day) {
        composable<Route.Day> {
            val viewModel: DayViewModel = metroViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            val resources = LocalResources.current
            val uriHandler = LocalUriHandler.current
            val checkForUpdatesUrl = stringResource(R.string.check_for_updates_url)

            LaunchedEffect(viewModel) {
                viewModel.messages.collect { message ->
                    viewModel.onMessageShown(message)
                    snackbarHostState.showSnackbar(
                        resources.getString(message.text, *message.formatArgs.toTypedArray()),
                    )
                }
            }

            DayScreen(
                state = state,
                snackbarHostState = snackbarHostState,
                onTodayClick = viewModel::onTodayClick,
                onPermissionsClick = { navController.navigate(Route.Onboarding) },
                onSettingsClick = { navController.navigate(Route.Settings) },
                onLicensesClick = { navController.navigate(Route.Licenses) },
                onCheckForUpdatesClick = { uriHandler.openUri(checkForUpdatesUrl) },
            )
        }
        composable<Route.Onboarding> {
            ComingSoonScreen(
                title = stringResource(R.string.onboarding_title),
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.Settings> {
            ComingSoonScreen(
                title = stringResource(R.string.settings_title),
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.Licenses> {
            LicensesScreen(onBack = { navController.popBackStack() })
        }
    }
}
