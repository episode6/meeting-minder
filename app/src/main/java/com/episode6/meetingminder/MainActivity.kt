package com.episode6.meetingminder

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.episode6.meetingminder.ui.navigation.DeepLinkInbox
import com.episode6.meetingminder.ui.navigation.MeetingMinderNavigation
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory

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

    // Also reached before the first composition when the activity is being recreated in a
    // task that survived it, which is why links queue in DeepLinkInbox rather than going to
    // a listener the composition registers.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinks.offer(intent)
    }
}
