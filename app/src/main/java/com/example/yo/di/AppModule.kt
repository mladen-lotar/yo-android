package com.example.yo.di

import android.content.Context
import androidx.room.Room
import com.example.yo.BuildConfig
import com.example.yo.data.contacts.ContentResolverContactsRepository
import com.example.yo.data.location.FusedOneShotLocationProvider
import com.example.yo.data.local.GroupDao
import com.example.yo.data.local.YoDao
import com.example.yo.data.local.YoDatabase
import com.example.yo.data.photo.BitmapPhotoEncoder
import com.example.yo.data.remote.CredentialManagerGoogleIdTokenProvider
import com.example.yo.data.remote.FirebaseFcmTokenProvider
import com.example.yo.data.remote.HttpYoBackendApi
import com.example.yo.data.remote.SharedPreferencesDeviceRegistrationStore
import com.example.yo.data.remote.SharedPreferencesSessionStore
import com.example.yo.data.remote.YoBackendApi
import com.example.yo.data.remote.YoRemoteDeliveryPortImpl
import com.example.yo.data.repository.GroupRepositoryImpl
import com.example.yo.data.repository.YoRepositoryImpl
import com.example.yo.domain.location.OneShotLocationProvider
import com.example.yo.domain.photo.PhotoEncoder
import com.example.yo.domain.repository.ContactsRepository
import com.example.yo.domain.repository.DeviceRegistrationStore
import com.example.yo.domain.repository.FcmTokenProvider
import com.example.yo.domain.repository.GoogleIdTokenProvider
import com.example.yo.domain.repository.GroupRepository
import com.example.yo.domain.repository.SessionStore
import com.example.yo.domain.repository.YoRemoteDeliveryPort
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

    @Provides
    fun provideGroupDao(database: YoDatabase): GroupDao = database.groupDao()

    @Provides
    @InviteUrl
    fun provideInviteUrl(): String = BuildConfig.YO_INVITE_URL

    @Provides
    @GoogleClientId
    fun provideGoogleClientId(): String = BuildConfig.YO_GOOGLE_CLIENT_ID

    @Provides
    @Singleton
    fun provideYoBackendApi(sessionStore: SessionStore): YoBackendApi =
        HttpYoBackendApi(
            baseUrl = BuildConfig.YO_BACKEND_URL,
            sessionStore = sessionStore,
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindYoRepository(impl: YoRepositoryImpl): YoRepository

    @Binds
    @Singleton
    abstract fun bindGroupRepository(impl: GroupRepositoryImpl): GroupRepository

    @Binds
    @Singleton
    abstract fun bindYoRemoteDeliveryPort(impl: YoRemoteDeliveryPortImpl): YoRemoteDeliveryPort

    @Binds
    @Singleton
    abstract fun bindFcmTokenProvider(impl: FirebaseFcmTokenProvider): FcmTokenProvider

    @Binds
    @Singleton
    abstract fun bindDeviceRegistrationStore(
        impl: SharedPreferencesDeviceRegistrationStore,
    ): DeviceRegistrationStore

    @Binds
    @Singleton
    abstract fun bindSessionStore(impl: SharedPreferencesSessionStore): SessionStore

    @Binds
    @Singleton
    abstract fun bindGoogleIdTokenProvider(
        impl: CredentialManagerGoogleIdTokenProvider,
    ): GoogleIdTokenProvider
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

@Module
@InstallIn(SingletonComponent::class)
abstract class PhotoModule {
    @Binds
    @Singleton
    abstract fun bindPhotoEncoder(impl: BitmapPhotoEncoder): PhotoEncoder
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ContactsModule {
    @Binds
    @Singleton
    abstract fun bindContactsRepository(
        impl: ContentResolverContactsRepository,
    ): ContactsRepository
}
