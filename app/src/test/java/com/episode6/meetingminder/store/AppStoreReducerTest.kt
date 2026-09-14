package com.episode6.meetingminder.store

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import com.episode6.meetingminder.permissions.PermissionState
import com.episode6.redux.Action
import com.episode6.redux.subscriberaware.SubscriberStatusChanged
import org.junit.Test
import java.time.LocalDate

class AppStoreReducerTest {

    private val today = LocalDate.of(2026, 9, 14)
    private val state = AppState(anchorDate = today)
    private val message = UiMessage(id = 7, text = 42)

    @Test
    fun initialState_settlesOnTheAnchorDate() {
        assertThat(state.settledDate).isEqualTo(today)
        assertThat(state.transientMessage).isNull()
    }

    @Test
    fun setSettledDate_movesOnlyTheSettledDate() {
        val result = state.reduce(SetSettledDate(today.plusDays(2)))

        assertThat(result).isEqualTo(state.copy(settledDate = today.plusDays(2)))
    }

    @Test
    fun setPermissions_replacesTheWholePermissionState() {
        val granted = PermissionState(calendarGranted = true)

        val result = state.reduce(SetPermissions(granted))

        assertThat(result).isEqualTo(state.copy(permissions = granted))
    }

    @Test
    fun showMessage_replacesAnyPendingMessage() {
        val newer = UiMessage(id = 8, text = 43)

        val result = state.reduce(ShowMessage(message)).reduce(ShowMessage(newer))

        assertThat(result.transientMessage).isEqualTo(newer)
    }

    @Test
    fun clearMessage_clearsTheMatchingMessage() {
        val result = state.copy(transientMessage = message).reduce(ClearMessage(message.id))

        assertThat(result.transientMessage).isNull()
    }

    @Test
    fun clearMessage_forAnOlderId_keepsTheNewerMessage() {
        val showing = state.copy(transientMessage = message)

        val result = showing.reduce(ClearMessage(id = 6))

        assertThat(result).isSameInstanceAs(showing)
    }

    @Test
    fun nonUpdateStateActions_leaveStateUntouched() {
        assertThat(state.reduce(object : Action {})).isSameInstanceAs(state)
        assertThat(state.reduce(SubscriberStatusChanged(true))).isSameInstanceAs(state)
    }

    @Test
    fun uiMessageNext_handsOutIncreasingIds() {
        val first = UiMessage.next(1)
        val second = UiMessage.next(1, "arg")

        assertThat(second.id).isEqualTo(first.id + 1)
        assertThat(second.formatArgs).isEqualTo(listOf<Any>("arg"))
    }
}
