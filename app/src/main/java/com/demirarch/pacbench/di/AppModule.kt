package com.demirarch.pacbench.di

import android.content.Context
import com.demirarch.pacbench.data.local.GameDao
import com.demirarch.pacbench.data.local.HudPresetDao
import com.demirarch.pacbench.data.local.PacBenchDatabase
import com.demirarch.pacbench.data.local.PerformanceSessionDao
import com.demirarch.pacbench.data.repository.GameRepository
import com.demirarch.pacbench.data.repository.HudPresetRepository
import com.demirarch.pacbench.data.repository.RoomGameRepository
import com.demirarch.pacbench.data.repository.RoomHudPresetRepository
import com.demirarch.pacbench.data.repository.RoomSessionRepository
import com.demirarch.pacbench.data.repository.SessionRepository
import com.demirarch.pacbench.data.settings.DataStoreSettingsRepository
import com.demirarch.pacbench.data.settings.SettingsRepository
import com.demirarch.pacbench.metrics.MetricEngine
import com.demirarch.pacbench.metrics.NormalMetricProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PacBenchDatabase =
        PacBenchDatabase.getInstance(context)

    @Provides
    fun provideGameDao(database: PacBenchDatabase): GameDao = database.gameDao()

    @Provides
    fun provideSessionDao(database: PacBenchDatabase): PerformanceSessionDao =
        database.performanceSessionDao()

    @Provides
    fun provideHudPresetDao(database: PacBenchDatabase): HudPresetDao = database.hudPresetDao()

    @Provides
    @Singleton
    fun provideGameRepository(dao: GameDao): GameRepository = RoomGameRepository(dao)

    @Provides
    @Singleton
    fun provideSessionRepository(dao: PerformanceSessionDao): SessionRepository =
        RoomSessionRepository(dao)

    @Provides
    @Singleton
    fun provideHudPresetRepository(dao: HudPresetDao): HudPresetRepository =
        RoomHudPresetRepository(dao)

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        DataStoreSettingsRepository(context)

    /** A normal-access engine for capability screens; sessions use the target-aware factory. */
    @Provides
    @Singleton
    fun provideMetricEngine(@ApplicationContext context: Context): MetricEngine =
        MetricEngine(listOf(NormalMetricProvider(context)))
}
