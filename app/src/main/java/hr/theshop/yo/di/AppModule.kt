package hr.theshop.yo.di

import android.content.Context
import androidx.room.Room
import hr.theshop.yo.BuildConfig
import hr.theshop.yo.data.contacts.ContentResolverContactsRepository
import hr.theshop.yo.data.location.FusedOneShotLocationProvider
import hr.theshop.yo.data.local.GroupDao
import hr.theshop.yo.data.local.YoDao
import hr.theshop.yo.data.local.YoDatabase
import hr.theshop.yo.data.remote.CredentialManagerGoogleIdTokenProvider
import hr.theshop.yo.data.remote.FirebaseFcmTokenProvider
import hr.theshop.yo.data.remote.HttpYoBackendApi
import hr.theshop.yo.data.remote.SharedPreferencesDeviceRegistrationStore
import hr.theshop.yo.data.remote.SharedPreferencesSessionStore
import hr.theshop.yo.data.remote.YoBackendApi
import hr.theshop.yo.data.remote.YoRemoteDeliveryPortImpl
import hr.theshop.yo.data.repository.GroupRepositoryImpl
import hr.theshop.yo.data.repository.YoRepositoryImpl
import hr.theshop.yo.domain.location.OneShotLocationProvider
import hr.theshop.yo.domain.repository.ContactsRepository
import hr.theshop.yo.domain.repository.DeviceRegistrationStore
import hr.theshop.yo.domain.repository.FcmTokenProvider
import hr.theshop.yo.domain.repository.GoogleIdTokenProvider
import hr.theshop.yo.domain.repository.GroupRepository
import hr.theshop.yo.domain.repository.SessionStore
import hr.theshop.yo.domain.repository.YoRemoteDeliveryPort
import hr.theshop.yo.domain.repository.YoRepository
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
        )
            // Without this an upgrade THROWS on first open - this database shipped with no
            // migrations and no destructive fallback, which is exactly why the schema could not
            // be changed until now. Destructive fallback is still deliberately absent: it "fixes"
            // the crash by erasing the user's history.
            .addMigrations(YoDatabase.MIGRATION_2_3)
            .build()

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
abstract class ContactsModule {
    @Binds
    @Singleton
    abstract fun bindContactsRepository(
        impl: ContentResolverContactsRepository,
    ): ContactsRepository
}
