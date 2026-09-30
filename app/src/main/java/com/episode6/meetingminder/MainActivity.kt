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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    // the loud schedule-change alert never rings over the app itself (MainUiVisibility), and
    // the first start of a day opens on today (ForegroundLog)
    override fun onStart() {
        super.onStart()
        appGraph.mainUiVisibility.visible = true
        lifecycleScope.launch { appGraph.foregroundLog.record()?.let(deepLinks::offerToday) }
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
