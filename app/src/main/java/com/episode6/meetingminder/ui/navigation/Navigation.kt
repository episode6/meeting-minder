package com.episode6.meetingminder.ui.navigation

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import com.episode6.meetingminder.share.shareScheduleIntent
import com.episode6.meetingminder.ui.day.DayScreen
import com.episode6.meetingminder.ui.day.DayViewModel
import com.episode6.meetingminder.ui.licenses.LicensesScreen
import com.episode6.meetingminder.ui.onboarding.OnboardingRow
import com.episode6.meetingminder.ui.onboarding.OnboardingScreen
import com.episode6.meetingminder.ui.onboarding.OnboardingViewModel
import com.episode6.meetingminder.ui.settings.SettingsScreen
import com.episode6.meetingminder.ui.settings.SettingsViewModel
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
    // also react whenever a required grant turns false and we are not already there. The
    // entry is still null on the first frame (its collector hasn't delivered yet); the
    // synchronous seed covers that frame, and acting on null would pop the start
    // destination and push a second Onboarding over it, discarding its restored state.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(requiredPermissionsGranted, currentBackStackEntry) {
        val entry = currentBackStackEntry ?: return@LaunchedEffect
        if (!requiredPermissionsGranted && !entry.destination.hasRoute<Route.Onboarding>()) {
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
            // side effect that formats the text and records the share. It is launched for
            // a result only to hear the sheet close: that ends the share in flight
            // (AppState.shareInFlight), which is what keeps a second tap from opening a
            // second chooser while the first is still coming up.
            val shareSheet = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                viewModel.onShareSheetClosed()
            }
            LaunchedEffect(viewModel, entryLifecycleOwner) {
                entryLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.pendingShare.collect { share ->
                        viewModel.onShareLaunched(share)
                        try {
                            shareSheet.launch(dayContext.shareScheduleIntent(share.text))
                        } catch (_: ActivityNotFoundException) {
                            viewModel.onShareSheetClosed()
                        }
                    }
                }
            }

            DayScreen(
                state = state,
                snackbarHostState = snackbarHostState,
                onPageSettled = viewModel::onPageSettled,
                onRefreshClick = viewModel::onRefreshClick,
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
                onEventOpenClick = { event ->
                    viewModel.calendarEventFor(event.key)?.let { calendarEvent ->
                        if (!dayContext.openInCalendar(calendarEvent)) viewModel.onOpenInCalendarFailed()
                    }
                },
                onEventRespond = viewModel::onEventRespond,
                onFabClick = viewModel::onFabClick,
                jumpToDate = pendingJumpDate,
                onJumpHandled = { pendingJumpDate = null },
            )
        }
        composable<Route.Onboarding> {
            val viewModel: OnboardingViewModel = metroViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val requestedPermissions by viewModel.requestedPermissions.collectAsStateWithLifecycle()
            val screenContext = LocalContext.current
            val uriHandler = LocalUriHandler.current
            val calendarRequest = rememberRuntimePermissionRequest(
                permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                granted = state.calendarGranted,
                requestedPermissions = requestedPermissions,
                onRequested = viewModel::onPermissionRequested,
                onResult = viewModel::onPermissionsMaybeChanged,
            )
            val notificationsRequest = rememberRuntimePermissionRequest(
                permissions = arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                granted = state.notificationsGranted,
                requestedPermissions = requestedPermissions,
                onRequested = viewModel::onPermissionRequested,
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
                        // a system dialog; some OEM builds don't implement it, so fall back to
                        // the exemptions list
                        OnboardingRow.BatteryOptimization -> screenContext.startFirstResolvable(
                            PermissionRequester.ignoreBatteryOptimizationsIntent(screenContext),
                            PermissionRequester.batteryOptimizationSettingsIntent(),
                        )
                        OnboardingRow.BackgroundRestricted ->
                            screenContext.startActivity(PermissionRequester.appSettingsIntent(screenContext))
                    }
                },
                onOpenSettingsClick = { row ->
                    val intent = when (row) {
                        // app info is also where battery usage (Unrestricted / Restricted) is set
                        OnboardingRow.Calendar, OnboardingRow.BackgroundRestricted -> PermissionRequester.appSettingsIntent(screenContext)
                        OnboardingRow.Notifications -> PermissionRequester.appNotificationSettingsIntent(screenContext)
                        OnboardingRow.ExactAlarms -> PermissionRequester.exactAlarmSettingsIntent(screenContext)
                        OnboardingRow.FullScreenAlarms -> PermissionRequester.fullScreenIntentSettingsIntent(screenContext)
                        OnboardingRow.BatteryOptimization -> PermissionRequester.batteryOptimizationSettingsIntent()
                    }
                    screenContext.startActivity(intent)
                },
                onManufacturerGuideClick = { manufacturer ->
                    // the app has no network access: the guide opens in the browser, and
                    // with no browser there is simply nothing to open
                    try {
                        uriHandler.openUri(manufacturer.guideUrl)
                    } catch (_: IllegalArgumentException) {
                    }
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
            val viewModel: SettingsViewModel = metroViewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            val resources = LocalResources.current
            val entryLifecycleOwner = LocalLifecycleOwner.current

            LaunchedEffect(viewModel, entryLifecycleOwner) {
                entryLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.messages.collect { message ->
                        viewModel.onMessageShown(message)
                        snackbarHostState.showSnackbar(message.resolve(resources))
                    }
                }
            }

            SettingsScreen(
                state = state,
                snackbarHostState = snackbarHostState,
                onBackClick = { navController.popBackStack() },
                onLeadTimeSelected = viewModel::onLeadTimeSelected,
                onSnoozeLengthSelected = viewModel::onSnoozeLengthSelected,
                onAutoTimeoutSelected = viewModel::onAutoTimeoutSelected,
                onSoundPoolSelected = viewModel::onSoundPoolSelected,
                onTestAlarmClick = viewModel::onTestAlarmClick,
                onCalendarToggle = { calendar, included -> viewModel.onCalendarToggle(calendar, included) },
                onShowDeclinedToggle = viewModel::onShowDeclinedToggle,
                onBusySyncToggle = viewModel::onBusySyncToggle,
                onBusyCalendarSelected = viewModel::onBusyCalendarSelected,
                onBusyFirstNameChanged = viewModel::onBusyFirstNameChanged,
                onBusySendTextToggle = viewModel::onBusySendTextToggle,
                onLoudChangeAlertsToggle = viewModel::onLoudChangeAlertsToggle,
                onPermissionsClick = { navController.navigate(Route.Onboarding) },
                onLicensesClick = { navController.navigate(Route.Licenses) },
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
 * settings" detection (TODO.md §4.1): a result that isn't a grant and comes back with no
 * rationale to show means the system won't ask again. Rationale is also false before the
 * user has ever made a choice, so a bare "no rationale after" check would flip to
 * "permanently denied" on a first-ever Back-dismissal. The request is therefore only
 * counted when the user had already been asked before it — the rationale was showing
 * (one denial so far), or [requestedPermissions] (persisted through [onRequested]) says a
 * request happened in some earlier session. That persisted half is what catches a
 * permission denied for good before the process died: the system auto-denies its next
 * request without a dialog, and without it that request would look like a first-ever
 * dismissal and leave the user tapping a dead "Allow" for ever. The one thing it can't
 * tell apart is a user who dismisses the dialog with Back twice, who is offered
 * "Open settings" although the dialog would still show; that fails in the safe direction.
 * [granted] turning true (a fresh grant, or one made in system Settings) resets the flag.
 */
@Composable
private fun rememberRuntimePermissionRequest(
    permissions: Array<String>,
    granted: Boolean,
    requestedPermissions: Set<String>,
    onRequested: (String) -> Unit,
    onResult: () -> Unit,
): RuntimePermissionRequest {
    val context = LocalContext.current
    val key = permissions.first()
    var permanentlyDenied by rememberSaveable(key) { mutableStateOf(false) }
    var askedBeforeRequest by rememberSaveable(key) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        onResult()
        permanentlyDenied = if (results.values.all { it }) {
            false
        } else {
            askedBeforeRequest && !context.rationaleShownFor(key)
        }
    }
    LaunchedEffect(granted) {
        if (granted) permanentlyDenied = false
    }
    return RuntimePermissionRequest(permanentlyDenied) {
        // snapshot first: this request must not count as the earlier one
        askedBeforeRequest = key in requestedPermissions || context.rationaleShownFor(key)
        onRequested(key)
        launcher.launch(permissions)
    }
}

private fun Context.rationaleShownFor(permission: String): Boolean {
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}

/** Starts the first of [intents] some activity can handle; does nothing if none can. */
private fun Context.startFirstResolvable(vararg intents: Intent) {
    for (intent in intents) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}

private fun Context.holdsPostNotifications(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
