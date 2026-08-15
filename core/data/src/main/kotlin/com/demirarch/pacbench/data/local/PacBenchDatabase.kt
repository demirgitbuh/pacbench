package com.demirarch.pacbench.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.demirarch.pacbench.model.AccessMode

@Database(
    entities = [
        Game::class,
        PerformanceSession::class,
        PerformanceSample::class,
        HudPresetEntity::class,
    ],
    version = 1,
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
    // Add each explicit Migration here when the schema version advances.
    val all: Array<Migration> = emptyArray()
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
