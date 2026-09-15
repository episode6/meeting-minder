package com.episode6.meetingminder.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.episode6.meetingminder.data.settings.DataStoreSettingsRepository
import com.episode6.meetingminder.data.settings.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Binds the DataStore-backed [SettingsRepository]; tests substitute an in-memory fake. */
@ContributesTo(AppScope::class)
interface SettingsModule {
    @Provides
    @SingleIn(AppScope::class)
    fun settingsRepository(dataStore: DataStore<Preferences>): SettingsRepository = DataStoreSettingsRepository(dataStore)
}
