package com.episode6.meetingminder.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import assertk.assertThat
import assertk.assertions.containsExactly
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate

private const val DATABASE_NAME = "migration-test.db"
private const val SCHEMA_DIR = "schemas/com.episode6.meetingminder.data.db.MeetingMinderDatabase"

/**
 * The database's real migrations, from the first one on (6 → 7, `busy_block.title`). A
 * database is built at the old version straight from its exported schema JSON under
 * `app/schemas/` — the same file Room generates the `AutoMigration` from — seeded with plain
 * SQL, then opened through Room at the current version, which runs the migration and
 * validates the result against the entities. The builder here has **no** destructive
 * fallback (production keeps one for versions with no path), so a missing migration fails
 * the open instead of quietly emptying the tables. This stands in for `room-testing`'s
 * `MigrationTestHelper`, which wants the schemas packaged as assets; reading them where
 * they are committed needs no new dependency.
 */
@RunWith(RobolectricTestRunner::class)
class MeetingMinderDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: MeetingMinderDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    /** Creates [DATABASE_NAME] exactly as Room would have at [version], and hands it to [seed] before closing it. */
    private fun createDatabaseAt(version: Int, seed: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(File("$SCHEMA_DIR/$version.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(DATABASE_NAME).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val tableName = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
                }
            }
            val setupQueries = schema.getJSONArray("setupQueries")
            for (i in 0 until setupQueries.length()) db.execSQL(setupQueries.getString(i))
            db.version = version
            seed(db)
        }
    }

    private fun openCurrent(): MeetingMinderDatabase =
        Room.databaseBuilder(context, MeetingMinderDatabase::class.java, DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
            .also { database = it }

    @Test
    fun from6To7_keepsEveryBusyBlockRow_titledTheBareBusyItWasWrittenWith() = runTest {
        createDatabaseAt(6) { db ->
            db.execSQL("INSERT INTO busy_block (event_id, date, calendar_id, begin_millis, end_millis) VALUES (900, '2026-09-14', 7, 1000, 2000)")
        }

        val blocks = openCurrent().busyBlockDao().blocksOn(LocalDate.of(2026, 9, 14))

        // losing this row would orphan event 900 on the user's calendar: the app only ever
        // deletes ids it finds here
        assertThat(blocks).containsExactly(
            BusyBlockEntity(eventId = 900, date = LocalDate.of(2026, 9, 14), calendarId = 7, beginMillis = 1000, endMillis = 2000, title = "busy"),
        )
    }
}
