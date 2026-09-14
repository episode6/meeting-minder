package com.episode6.meetingminder.ui.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.episode6.meetingminder.R
import com.episode6.meetingminder.appGraph
import com.episode6.meetingminder.permissions.PermissionRequester
import com.episode6.meetingminder.store.PermissionsMaybeChanged
import com.episode6.meetingminder.ui.day.DayScreen
import com.episode6.meetingminder.ui.day.DayViewModel
import com.episode6.meetingminder.ui.licenses.LicensesScreen
import com.episode6.meetingminder.ui.onboarding.OnboardingScreen
import com.episode6.meetingminder.ui.onboarding.OnboardingViewModel
import com.episode6.meetingminder.ui.util.ComingSoonScreen
import com.episode6.meetingminder.ui.util.findActivity
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * The wiring layer: the only place ViewModels are obtained and their state collected,
 * and where one-shot effects (snackbars, activity launchers, later the share sheet and
 * deep links) are handled. Screens below it only take state + callbacks.
 */
@Composable
fun MeetingMinderNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val appStore = remember(context) { context.appGraph.appStore }

    // Computed once: AppGraph already seeded AppState.permissions synchronously, so this
    // never flashes Day before redirecting to Onboarding (or vice versa).
    val startDestination = remember { if (appStore.state.permissions.calendarGranted) Route.Day else Route.Onboarding }

    // Auto-revoke/hibernation and a trip to system Settings can change grants without any
    // action of ours, so re-check on every resume, app-wide (not just while Onboarding is shown).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, appStore) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) appStore.dispatch(PermissionsMaybeChanged)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    NavHost(navController = navController, startDestination = startDestination) {
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
                onCheckForUpdatesClick = {
                    // AndroidUriHandler reports "no activity can open this" as an
                    // IllegalArgumentException; show a snackbar instead of crashing
                    try {
                        uriHandler.openUri(checkForUpdatesUrl)
                    } catch (_: IllegalArgumentException) {
                        viewModel.onCheckForUpdatesFailed()
                    }
                },
            )
        }
        composable<Route.Onboarding> {
            val viewModel: OnboardingViewModel = metroViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val screenContext = LocalContext.current
            var calendarPermanentlyDenied by rememberSaveable { mutableStateOf(false) }
            val calendarPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { results ->
                viewModel.onPermissionsMaybeChanged()
                calendarPermanentlyDenied = if (results.values.all { it }) {
                    false
                } else {
                    val activity = screenContext.findActivity()
                    activity != null &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)
                }
            }
            // Reached from system Settings (or a fresh grant) without the launcher firing.
            LaunchedEffect(state.calendarGranted) {
                if (state.calendarGranted) calendarPermanentlyDenied = false
            }

            // Onboarding is either the launch destination (nothing to pop back to) or was
            // pushed from the Day screen's overflow menu ("Permissions").
            val canNavigateBack = navController.previousBackStackEntry != null
            OnboardingScreen(
                state = state,
                calendarPermanentlyDenied = calendarPermanentlyDenied,
                canNavigateBack = canNavigateBack,
                onAllowCalendarClick = {
                    calendarPermissionLauncher.launch(
                        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                    )
                },
                onOpenSettingsClick = { screenContext.startActivity(PermissionRequester.appSettingsIntent(screenContext)) },
                onContinueClick = {
                    if (canNavigateBack) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Route.Day) { popUpTo(Route.Onboarding) { inclusive = true } }
                    }
                },
                onBackClick = { navController.popBackStack() },
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
