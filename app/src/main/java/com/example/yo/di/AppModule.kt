package com.example.yo.di

import android.content.Context
import androidx.room.Room
import com.example.yo.data.location.FusedOneShotLocationProvider
import com.example.yo.data.local.YoDao
import com.example.yo.data.local.YoDatabase
import com.example.yo.data.repository.YoRepositoryImpl
import com.example.yo.domain.location.OneShotLocationProvider
import com.example.yo.domain.repository.YoRepository
import dagger.Binds
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
    fun provideYoDatabase(
        @ApplicationContext context: Context,
    ): YoDatabase =
        Room.databaseBuilder(
            context,
            YoDatabase::class.java,
            "yo.db",
        ).build()

    @Provides
    fun provideYoDao(database: YoDatabase): YoDao = database.yoDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindYoRepository(impl: YoRepositoryImpl): YoRepository
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {
    @Binds
    @Singleton
    abstract fun bindOneShotLocationProvider(
        impl: FusedOneShotLocationProvider,
    ): OneShotLocationProvider
}
