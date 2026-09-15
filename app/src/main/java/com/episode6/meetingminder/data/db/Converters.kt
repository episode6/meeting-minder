package com.episode6.meetingminder.data.db

import androidx.room.TypeConverter
import java.time.LocalDate

/** Room type converters shared by every entity in [MeetingMinderDatabase]. */
internal class Converters {
    @TypeConverter
    fun localDateToIso(date: LocalDate): String = date.toString()

    @TypeConverter
    fun isoToLocalDate(iso: String): LocalDate = LocalDate.parse(iso)
}
