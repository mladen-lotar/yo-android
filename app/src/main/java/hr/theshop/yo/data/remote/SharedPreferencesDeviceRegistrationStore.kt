package hr.theshop.yo.data.remote

import android.content.Context
import hr.theshop.yo.domain.model.DeviceRegistration
import hr.theshop.yo.domain.repository.DeviceRegistrationStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class SharedPreferencesDeviceRegistrationStore @Inject constructor(
    @ApplicationContext context: Context,
) : DeviceRegistrationStore {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isRegistered(registration: DeviceRegistration): Boolean =
        preferences.getString(KEY_USERNAME, null) == registration.username &&
            preferences.getString(KEY_FCM_TOKEN, null) == registration.fcmToken

    override fun markRegistered(registration: DeviceRegistration) {
        preferences.edit()
            .putString(KEY_USERNAME, registration.username)
            .putString(KEY_FCM_TOKEN, registration.fcmToken)
            .apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "yo_device_registration"
        const val KEY_USERNAME = "username"
        const val KEY_FCM_TOKEN = "fcm_token"
    }
}
