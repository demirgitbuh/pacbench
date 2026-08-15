package com.demirarch.pacbench.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.demirarch.pacbench.model.AccessMode

@Database(
    entities = [
        Game::class,
        PerformanceSession::class,
        PerformanceSample::class,
        HudPresetEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(DatabaseTypeConverters::class)
abstract class PacBenchDatabase : RoomDatabase() {
    abstract fun gameDao(): GameDao

    abstract fun performanceSessionDao(): PerformanceSessionDao

    abstract fun hudPresetDao(): HudPresetDao

    companion object {
        const val DEFAULT_DATABASE_NAME = "pacbench.db"

        @Volatile
        private var instance: PacBenchDatabase? = null

        fun getInstance(context: Context): PacBenchDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PacBenchDatabase::class.java,
                DEFAULT_DATABASE_NAME,
            )
                .addMigrations(*DatabaseMigrations.all)
                .build()
                .also { instance = it }
        }
    }
}

object DatabaseMigrations {
    private val migration1To2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            val appNameAdded = addColumn(database, "games", "app_name", "TEXT NOT NULL DEFAULT ''")
            addColumn(database, "games", "custom_name", "TEXT")
            addColumn(database, "games", "icon_reference", "TEXT")
            val addedAtAdded = addColumn(database, "games", "added_at", "INTEGER NOT NULL DEFAULT 0")
            addColumn(database, "games", "launch_configuration", "TEXT")
            addColumn(database, "games", "selected_hud_preset_id", "TEXT")
            addColumn(database, "games", "auto_monitoring", "INTEGER NOT NULL DEFAULT 1")
            addColumn(database, "games", "auto_overlay", "INTEGER NOT NULL DEFAULT 1")
            addColumn(database, "games", "is_archived", "INTEGER NOT NULL DEFAULT 0")
            if (appNameAdded) database.execSQL("UPDATE games SET app_name = display_name")
            if (addedAtAdded) database.execSQL("UPDATE games SET added_at = first_seen_at")

            addColumn(database, "performance_sessions", "duration_millis", "INTEGER")
            addColumn(database, "performance_sessions", "average_fps", "REAL")
            addColumn(database, "performance_sessions", "median_fps", "REAL")
            addColumn(database, "performance_sessions", "min_fps", "REAL")
            addColumn(database, "performance_sessions", "max_fps", "REAL")
            addColumn(database, "performance_sessions", "one_percent_low", "REAL")
            addColumn(database, "performance_sessions", "point_one_percent_low", "REAL")
            addColumn(database, "performance_sessions", "average_cpu", "REAL")
            addColumn(database, "performance_sessions", "average_gpu", "REAL")
            addColumn(database, "performance_sessions", "max_cpu_temp", "REAL")
            addColumn(database, "performance_sessions", "max_gpu_temp", "REAL")
            addColumn(database, "performance_sessions", "max_battery_temp", "REAL")
            addColumn(database, "performance_sessions", "average_power", "REAL")
            addColumn(database, "performance_sessions", "battery_start", "REAL")
            addColumn(database, "performance_sessions", "battery_end", "REAL")
            addColumn(database, "performance_sessions", "thermal_event_count", "INTEGER NOT NULL DEFAULT 0")
            addColumn(database, "performance_sessions", "is_favorite", "INTEGER NOT NULL DEFAULT 0")
            database.execSQL(
                "UPDATE performance_sessions SET duration_millis = MAX(ended_at - started_at, 0) " +
                    "WHERE ended_at IS NOT NULL AND duration_millis IS NULL",
            )

            rebuildVersion2Tables(database)
        }
    }

    private fun addColumn(
        database: SupportSQLiteDatabase,
        table: String,
        column: String,
        declaration: String,
    ): Boolean {
        val exists = database.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) database.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $declaration")
        return !exists
    }

    private fun rebuildVersion2Tables(database: SupportSQLiteDatabase) {
        database.execSQL("PRAGMA defer_foreign_keys = ON")
        database.execSQL(
            """
            CREATE TABLE games_v2 (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                package_name TEXT NOT NULL,
                display_name TEXT NOT NULL,
                app_name TEXT NOT NULL DEFAULT '',
                custom_name TEXT,
                icon_reference TEXT,
                version_name TEXT,
                version_code INTEGER,
                is_favorite INTEGER NOT NULL,
                first_seen_at INTEGER NOT NULL,
                added_at INTEGER NOT NULL DEFAULT 0,
                last_seen_at INTEGER NOT NULL,
                last_played_at INTEGER,
                launch_configuration TEXT,
                selected_hud_preset_id TEXT,
                auto_monitoring INTEGER NOT NULL DEFAULT 1,
                auto_overlay INTEGER NOT NULL DEFAULT 1,
                is_archived INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO games_v2 SELECT
                id, package_name, display_name, app_name, custom_name, icon_reference,
                version_name, version_code, is_favorite, first_seen_at, added_at,
                last_seen_at, last_played_at, launch_configuration, selected_hud_preset_id,
                auto_monitoring, auto_overlay, is_archived
            FROM games
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE performance_sessions_v2 (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                game_id INTEGER NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                duration_millis INTEGER,
                access_mode TEXT NOT NULL,
                status TEXT NOT NULL,
                device_manufacturer TEXT NOT NULL,
                device_model TEXT NOT NULL,
                android_version TEXT NOT NULL,
                app_version TEXT NOT NULL,
                data_quality_summary TEXT NOT NULL,
                average_fps REAL,
                median_fps REAL,
                min_fps REAL,
                max_fps REAL,
                one_percent_low REAL,
                point_one_percent_low REAL,
                average_cpu REAL,
                average_gpu REAL,
                max_cpu_temp REAL,
                max_gpu_temp REAL,
                max_battery_temp REAL,
                average_power REAL,
                battery_start REAL,
                battery_end REAL,
                thermal_event_count INTEGER NOT NULL DEFAULT 0,
                is_favorite INTEGER NOT NULL DEFAULT 0,
                notes TEXT,
                FOREIGN KEY(game_id) REFERENCES games_v2(id) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO performance_sessions_v2 SELECT
                id, game_id, started_at, ended_at, duration_millis, access_mode, status,
                device_manufacturer, device_model, android_version, app_version,
                data_quality_summary, average_fps, median_fps, min_fps, max_fps,
                one_percent_low, point_one_percent_low, average_cpu, average_gpu,
                max_cpu_temp, max_gpu_temp, max_battery_temp, average_power,
                battery_start, battery_end, thermal_event_count, is_favorite, notes
            FROM performance_sessions
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE performance_samples_v2 (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                session_id INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                fps REAL,
                frame_time REAL,
                cpu_usage REAL,
                cpu_frequency REAL,
                cpu_temp REAL,
                gpu_usage REAL,
                gpu_frequency REAL,
                gpu_temp REAL,
                ram_used INTEGER,
                ram_available INTEGER,
                battery_level REAL,
                battery_temp REAL,
                voltage REAL,
                current REAL,
                power_watts REAL,
                download_rate REAL,
                upload_rate REAL,
                ping REAL,
                thermal_state INTEGER,
                FOREIGN KEY(session_id) REFERENCES performance_sessions_v2(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO performance_samples_v2 SELECT
                id, session_id, timestamp, fps, frame_time, cpu_usage, cpu_frequency,
                cpu_temp, gpu_usage, gpu_frequency, gpu_temp, ram_used, ram_available,
                battery_level, battery_temp, voltage, current, power_watts, download_rate,
                upload_rate, ping, thermal_state
            FROM performance_samples
            """.trimIndent(),
        )

        database.execSQL("DROP TABLE performance_samples")
        database.execSQL("DROP TABLE performance_sessions")
        database.execSQL("DROP TABLE games")
        database.execSQL("ALTER TABLE games_v2 RENAME TO games")
        database.execSQL("ALTER TABLE performance_sessions_v2 RENAME TO performance_sessions")
        database.execSQL("ALTER TABLE performance_samples_v2 RENAME TO performance_samples")
        database.execSQL("CREATE UNIQUE INDEX index_games_package_name ON games(package_name)")
        database.execSQL("CREATE INDEX index_performance_sessions_game_id ON performance_sessions(game_id)")
        database.execSQL("CREATE INDEX index_performance_sessions_started_at ON performance_sessions(started_at)")
        database.execSQL(
            "CREATE INDEX index_performance_sessions_game_id_started_at " +
                "ON performance_sessions(game_id, started_at)",
        )
        database.execSQL("CREATE INDEX index_performance_samples_session_id ON performance_samples(session_id)")
        database.execSQL("CREATE INDEX index_performance_samples_timestamp ON performance_samples(timestamp)")
        database.execSQL(
            "CREATE INDEX index_performance_samples_session_id_timestamp " +
                "ON performance_samples(session_id, timestamp)",
        )
    }

    val all: Array<Migration> = arrayOf(migration1To2)
}

internal class DatabaseTypeConverters {
    @TypeConverter
    fun accessModeToString(value: AccessMode): String = value.name

    @TypeConverter
    fun stringToAccessMode(value: String): AccessMode = AccessMode.valueOf(value)

    @TypeConverter
    fun sessionStatusToString(value: SessionStatus): String = value.name

    @TypeConverter
    fun stringToSessionStatus(value: String): SessionStatus = SessionStatus.valueOf(value)
}
