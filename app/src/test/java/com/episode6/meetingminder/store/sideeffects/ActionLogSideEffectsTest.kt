package com.episode6.meetingminder.store.sideeffects

import assertk.assertThat
import assertk.assertions.isEmpty
import com.episode6.meetingminder.store.SetSettledDate
import com.episode6.meetingminder.store.ShowMessage
import com.episode6.meetingminder.store.UiMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ActionLogSideEffectsTest {

    private val sideEffects = object : ActionLogSideEffects {}

    @Test
    fun consumesEveryAction_andEmitsNothing() = runTest {
        val output = sideEffects.actionLog().output(
            SetSettledDate(TestAppState.anchorDate.plusDays(1)),
            ShowMessage(UiMessage(id = 1, text = 1)),
        ).toList()

        assertThat(output).isEmpty()
    }
}
