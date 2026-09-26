package com.episode6.meetingminder.alarm

import android.content.Context
import android.media.RingtoneManager
import android.provider.Settings
import android.util.Log
import com.episode6.meetingminder.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LOG_TAG = "SoundCatalogSource"

/**
 * Where the device's whole [SoundCatalog] comes from (TODO.md §4.4): every alarm ringtone
 * the device offers, every bundled OGG and the siren, before the user's choices are applied.
 * The ringing [AlarmSoundPlayer] and Settings → Alarm sounds read the same one, so the
 * page lists exactly what an alarm can draw from.
 */
interface SoundCatalogSource {
    suspend fun load(): SoundCatalog
}

/** [SoundCatalogSource] over `RingtoneManager` and [BundledAlarmSounds] (bound in `di/AlarmModule.kt`). */
class DeviceSoundCatalogSource(private val context: Context) : SoundCatalogSource {

    override suspend fun load(): SoundCatalog = withContext(Dispatchers.IO) {
        SoundCatalog(system = systemSounds(), bundled = BundledAlarmSounds.all.map { AlarmSound.Bundled(it.name) })
    }

    /**
     * The device's alarm ringtones; the default alarm sound if the list can't be read or is
     * empty. A ringtone the user added from storage sits on the external volume and needs
     * `READ_MEDIA_AUDIO` to open, which this app doesn't hold: it fails to open in the
     * player, costs the backoff's silence, and the recipe may well draw it again — accepted
     * rather than asking for a media permission for the sake of a re-roll.
     */
    private fun systemSounds(): List<AlarmSound.System> {
        val sounds = try {
            val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_ALARM) }
            // the manager is throwaway, so its cursor is ours to close (a CursorLeak on every ring otherwise)
            manager.cursor.use { cursor ->
                List(cursor.count) { position ->
                    cursor.moveToPosition(position)
                    AlarmSound.System(manager.getRingtoneUri(position).toString(), cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX))
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "couldn't list alarm ringtones", e)
            emptyList()
        }
        return sounds.ifEmpty {
            listOf(AlarmSound.System(Settings.System.DEFAULT_ALARM_ALERT_URI.toString(), context.getString(R.string.alarm_sound_default)))
        }
    }
}
