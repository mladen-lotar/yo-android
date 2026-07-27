package hr.theshop.yo.data.location

import android.annotation.SuppressLint
import android.content.Context
import hr.theshop.yo.domain.location.LocationCoordinates
import hr.theshop.yo.domain.location.OneShotLocationProvider
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val LOCATION_FETCH_TIMEOUT_MS = 10_000L

class FusedOneShotLocationProvider @Inject constructor(
    @ApplicationContext context: Context,
) : OneShotLocationProvider {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): LocationCoordinates? {
        val cancellationTokenSource = CancellationTokenSource()

        return try {
            withTimeoutOrNull(LOCATION_FETCH_TIMEOUT_MS) {
                client
                    .getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        cancellationTokenSource.token,
                    )
                    .await()
                    ?.let { location ->
                        LocationCoordinates(
                            latitude = location.latitude,
                            longitude = location.longitude,
                        )
                    }
            }
        } catch (e: SecurityException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            cancellationTokenSource.cancel()
        }
    }
}
