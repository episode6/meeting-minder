package com.episode6.meetingminder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import com.episode6.meetingminder.ui.navigation.DeepLinkInbox
import com.episode6.meetingminder.ui.navigation.MeetingMinderNavigation
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val deepLinks = DeepLinkInbox()

    /** This instance replaces one torn down for a configuration change: its first start isn't the app coming forward. */
    private var recreatedForConfigurationChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recreatedForConfigurationChange = savedInstanceState?.getBoolean(STATE_CHANGING_CONFIGURATIONS) == true
        // a recreated activity still carries the link it already acted on
        if (savedInstanceState == null) deepLinks.offer(intent)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalMetroViewModelFactory provides appGraph.metroViewModelFactory) {
                MeetingMinderTheme {
                    MeetingMinderNavigation(deepLinks)
                }
            }
        }
    }

    // The loud schedule-change alert never rings over the app itself (MainUiVisibility), and
    // every start opens on today until today has been shown (TodayShownLog). Not a start that
    // only rebuilt the activity (a rotation): that would yank a day a link just opened away.
    override fun onStart() {
        super.onStart()
        appGraph.mainUiVisibility.visible = true
        if (recreatedForConfigurationChange) {
            recreatedForConfigurationChange = false
        } else {
            lifecycleScope.launch { appGraph.todayShownLog.todayIfNotYetShown()?.let(deepLinks::offerToday) }
        }
    }

    // A process death restores this bundle too, with false in it: that start is a real one.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_CHANGING_CONFIGURATIONS, isChangingConfigurations)
    }

    override fun onStop() {
        appGraph.mainUiVisibility.visible = false
        deepLinks.onStop()
        super.onStop()
    }

    // Also reached before the first composition when the activity is being recreated in a
    // task that survived it, which is why links queue in DeepLinkInbox rather than going to
    // a listener the composition registers.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinks.offer(intent)
    }
}

private const val STATE_CHANGING_CONFIGURATIONS = "changing_configurations"
