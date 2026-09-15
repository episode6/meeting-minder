package com.episode6.meetingminder.ui.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.core.content.ContextCompat
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
import com.episode6.meetingminder.permissions.PermissionRequester
import com.episode6.meetingminder.share.shareSchedule
import com.episode6.meetingminder.ui.day.DayScreen
import com.episode6.meetingminder.ui.day.DayViewModel
import com.episode6.meetingminder.ui.licenses.LicensesScreen
import com.episode6.meetingminder.ui.onboarding.OnboardingRow
import com.episode6.meetingminder.ui.onboarding.OnboardingScreen
import com.episode6.meetingminder.ui.onboarding.OnboardingViewModel
import com.episode6.meetingminder.ui.util.ComingSoonScreen
import com.episode6.meetingminder.ui.util.findActivity
import com.episode6.meetingminder.ui.util.resolve
import dev.zacsweers.metrox.viewmodel.metroViewModel
import java.time.LocalDate

/**
 * The wiring layer: the only place ViewModels are obtained and their state collected,
 * and where one-shot effects (snackbars, activity launchers, the share sheet and deep
 * links) are handled. Screens below it only take state + callbacks.
 */
@Composable
fun MeetingMinderNavigation(deepLinks: DeepLinkInbox) {
    val navController = rememberNavController()
    val navigationViewModel: NavigationViewModel = metroViewModel()
    val requiredPermissionsGranted by navigationViewModel.requiredPermissionsGranted.collectAsStateWithLifecycle()

    // Computed once: AppGraph already seeded AppState.permissions synchronously, so this
    // never flashes Day before redirecting to Onboarding (or vice versa).
    val startDestination = remember { if (requiredPermissionsGranted) Route.Day else Route.Onboarding }

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
    // also react whenever a required grant turns false and we are not already there.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(requiredPermissionsGranted, currentBackStackEntry) {
        if (!requiredPermissionsGranted && currentBackStackEntry?.destination?.hasRoute<Route.Onboarding>() != true) {
            navController.navigate(Route.Onboarding) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Deep links from notifications (TODO.md §4.3): meetingminder://day/{date} shows that day,
    // meetingminder://share/{date} also opens its share sheet. MainActivity queues them in
    // [deepLinks] (its launch intent on a fresh start, and every onNewIntent, since the
    // notifications launch it single-top) and each is taken here exactly once.
    var pendingJumpDate by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(deepLinks, navigationViewModel, navController) {
        for (link in deepLinks.links) {
            if (!navigationViewModel.onDeepLink(link)) continue
            pendingJumpDate = link.date
            if (navController.currentBackStackEntry?.destination?.hasRoute<Route.Day>() != true &&
                !navController.popBackStack<Route.Day>(inclusive = false)
            ) {
                navController.navigate(Route.Day) {
                    popUpTo(navController.graph.id) { inclusive = true }
                    launchSingleTop = true
                }
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
                        snackbarHostState.showSnackbar(message.resolve(resources))
                    }
                }
            }

            // ShareCompat needs a real Activity context and must never launch from a
            // receiver (TODO.md §4.2), so the actual chooser call lives here, not in the
            // side effect that formats the text and records the share.
            LaunchedEffect(viewModel, entryLifecycleOwner) {
                entryLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.pendingShare.collect { share ->
                        viewModel.onShareLaunched(share)
                        dayContext.shareSchedule(share.text)
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
                onShareAgainClick = viewModel::onShareAgainClick,
                onMarkNotSharedClick = viewModel::onMarkNotSharedClick,
                onEventClick = viewModel::onEventToggle,
                onEventLongClick = { event ->
                    viewModel.calendarEventFor(event.key)?.let { calendarEvent ->
                        if (!dayContext.openInCalendar(calendarEvent)) viewModel.onOpenInCalendarFailed()
                    }
                },
                onFabClick = viewModel::onFabClick,
                jumpToDate = pendingJumpDate,
                onJumpHandled = { pendingJumpDate = null },
            )
        }
        composable<Route.Onboarding> {
            val viewModel: OnboardingViewModel = metroViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val screenContext = LocalContext.current
            val calendarRequest = rememberRuntimePermissionRequest(
                permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                granted = state.calendarGranted,
                onResult = viewModel::onPermissionsMaybeChanged,
            )
            val notificationsRequest = rememberRuntimePermissionRequest(
                permissions = arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                granted = state.notificationsGranted,
                onResult = viewModel::onPermissionsMaybeChanged,
            )
            // Notifications have no runtime dialog on 12/12L, and none that helps once the
            // POST_NOTIFICATIONS grant is held but the user has silenced the alarms channel:
            // only system Settings can fix either.
            val notificationsSettingsOnly = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                notificationsRequest.permanentlyDenied ||
                screenContext.holdsPostNotifications()
            val settingsOnlyRows = buildSet {
                if (calendarRequest.permanentlyDenied) add(OnboardingRow.Calendar)
                if (notificationsSettingsOnly) add(OnboardingRow.Notifications)
            }

            // Onboarding is either the launch destination (nothing to pop back to) or was
            // pushed from the Day screen's overflow menu ("Permissions").
            val canNavigateBack = navController.previousBackStackEntry != null
            OnboardingScreen(
                state = state,
                settingsOnlyRows = settingsOnlyRows,
                canNavigateBack = canNavigateBack,
                onAllowClick = { row ->
                    when (row) {
                        OnboardingRow.Calendar -> calendarRequest.launch()
                        OnboardingRow.Notifications -> notificationsRequest.launch()
                        // special access, never a dialog: the Settings page is the request
                        OnboardingRow.ExactAlarms ->
                            screenContext.startActivity(PermissionRequester.exactAlarmSettingsIntent(screenContext))
                        OnboardingRow.FullScreenAlarms ->
                            screenContext.startActivity(PermissionRequester.fullScreenIntentSettingsIntent(screenContext))
                    }
                },
                onOpenSettingsClick = { row ->
                    val intent = when (row) {
                        OnboardingRow.Calendar -> PermissionRequester.appSettingsIntent(screenContext)
                        OnboardingRow.Notifications -> PermissionRequester.appNotificationSettingsIntent(screenContext)
                        OnboardingRow.ExactAlarms -> PermissionRequester.exactAlarmSettingsIntent(screenContext)
                        OnboardingRow.FullScreenAlarms -> PermissionRequester.fullScreenIntentSettingsIntent(screenContext)
                    }
                    screenContext.startActivity(intent)
                },
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

/** One runtime-permission request flow: [launch] shows the dialog, [permanentlyDenied] says when it no longer will. */
private class RuntimePermissionRequest(val permanentlyDenied: Boolean, val launch: () -> Unit)

/**
 * Wraps `RequestMultiplePermissions` for [permissions] with the "two denials → Open
 * settings" detection (TODO.md §4.1). Rationale is also false before the user has ever
 * made a choice, so a bare "not shown after" check would flip to "permanently denied" on
 * a first-ever Back-dismissal; snapshotting it right before `launch()` tells that apart
 * from a real two-denials rationale→false transition. [granted] turning true (a fresh
 * grant, or one made in system Settings) resets the flag.
 */
@Composable
private fun rememberRuntimePermissionRequest(
    permissions: Array<String>,
    granted: Boolean,
    onResult: () -> Unit,
): RuntimePermissionRequest {
    val context = LocalContext.current
    val key = permissions.first()
    var permanentlyDenied by rememberSaveable(key) { mutableStateOf(false) }
    var rationaleShownBeforeRequest by rememberSaveable(key) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        onResult()
        permanentlyDenied = if (results.values.all { it }) {
            false
        } else {
            rationaleShownBeforeRequest && !context.rationaleShownFor(key)
        }
    }
    LaunchedEffect(granted) {
        if (granted) permanentlyDenied = false
    }
    return RuntimePermissionRequest(permanentlyDenied) {
        rationaleShownBeforeRequest = context.rationaleShownFor(key)
        launcher.launch(permissions)
    }
}

private fun Context.rationaleShownFor(permission: String): Boolean {
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}

private fun Context.holdsPostNotifications(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
