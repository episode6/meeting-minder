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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.repeatOnLifecycle
import com.episode6.meetingminder.R
import com.episode6.meetingminder.data.calendar.openInCalendar
import com.episode6.meetingminder.permissions.PermissionRequester
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
    val navigationViewModel: NavigationViewModel = metroViewModel()
    val calendarGranted by navigationViewModel.calendarGranted.collectAsStateWithLifecycle()

    // Computed once: AppGraph already seeded AppState.permissions synchronously, so this
    // never flashes Day before redirecting to Onboarding (or vice versa).
    val startDestination = remember { if (calendarGranted) Route.Day else Route.Onboarding }

    // Auto-revoke/hibernation and a trip to system Settings can change grants without any
    // action of ours, so re-check on every resume, app-wide (not just while Onboarding is shown).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, navigationViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) navigationViewModel.onResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // rememberNavController restores the saved back stack on activity recreation, so the
    // synchronous seed above only covers the very first frame. Revoking calendar access in
    // system Settings kills the process; relaunching from recents must still land on
    // Onboarding (TODO.md §4.5: shown whenever a required grant is missing at launch), so
    // also react whenever the grant turns false and we are not already there.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(calendarGranted, currentBackStackEntry) {
        if (!calendarGranted && currentBackStackEntry?.destination?.hasRoute<Route.Onboarding>() != true) {
            navController.navigate(Route.Onboarding) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable<Route.Day> {
            val viewModel: DayViewModel = metroViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            val resources = LocalResources.current
            val uriHandler = LocalUriHandler.current
            val checkForUpdatesUrl = stringResource(R.string.check_for_updates_url)
            val dayContext = LocalContext.current
            val entryLifecycleOwner = LocalLifecycleOwner.current

            // Only while started: a plain LaunchedEffect would keep collecting the store
            // with the app in the background, which would keep the store's subscribers
            // (and so the calendar ContentObserver) alive after the UI is gone.
            LaunchedEffect(viewModel, entryLifecycleOwner) {
                entryLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.messages.collect { message ->
                        viewModel.onMessageShown(message)
                        snackbarHostState.showSnackbar(
                            resources.getString(message.text, *message.formatArgs.toTypedArray()),
                        )
                    }
                }
            }

            DayScreen(
                state = state,
                snackbarHostState = snackbarHostState,
                onPageSettled = viewModel::onPageSettled,
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
                // selection arrives with PR-7
                onEventClick = {},
                onEventLongClick = { event ->
                    viewModel.calendarEventFor(event.key)?.let { calendarEvent ->
                        if (!dayContext.openInCalendar(calendarEvent)) viewModel.onOpenInCalendarFailed()
                    }
                },
            )
        }
        composable<Route.Onboarding> {
            val viewModel: OnboardingViewModel = metroViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val screenContext = LocalContext.current
            var calendarPermanentlyDenied by rememberSaveable { mutableStateOf(false) }
            // Rationale is also false before the user has ever made a choice, so a bare
            // "not shown after" check would flip to "permanently denied" on a first-ever
            // Back-dismissal. Snapshotting it right before launch() lets us tell that case
            // apart from a real two-denials rationale->false transition.
            var rationaleShownBeforeRequest by rememberSaveable { mutableStateOf(false) }
            val calendarPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { results ->
                viewModel.onPermissionsMaybeChanged()
                calendarPermanentlyDenied = if (results.values.all { it }) {
                    false
                } else {
                    val activity = screenContext.findActivity()
                    val rationaleStillShown = activity != null &&
                        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)
                    rationaleShownBeforeRequest && !rationaleStillShown
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
                    val activity = screenContext.findActivity()
                    rationaleShownBeforeRequest = activity != null &&
                        ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)
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
