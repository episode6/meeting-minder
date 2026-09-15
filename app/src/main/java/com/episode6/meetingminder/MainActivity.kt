package com.episode6.meetingminder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.episode6.meetingminder.ui.navigation.MeetingMinderNavigation
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalMetroViewModelFactory provides appGraph.metroViewModelFactory) {
                MeetingMinderTheme {
                    MeetingMinderNavigation()
                }
            }
        }
    }
}
