package com.example.yo.data.remote

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.yo.di.GoogleClientId
import com.example.yo.domain.repository.GoogleIdTokenProvider
import com.example.yo.domain.repository.GoogleIdTokenResult
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google sign-in through Credential Manager, which is the supported path now that
 * `GoogleSignInClient` is deprecated.
 */
@Singleton
class CredentialManagerGoogleIdTokenProvider @Inject constructor(
    @GoogleClientId private val serverClientId: String,
) : GoogleIdTokenProvider {
    override val isConfigured: Boolean = serverClientId.isNotBlank()

    override suspend fun requestIdToken(context: Context): GoogleIdTokenResult {
        if (!isConfigured) {
            return GoogleIdTokenResult.Unavailable
        }
        val option =
            GetGoogleIdOption.Builder()
                // Both flags exist to force the picker. Filtering by authorized accounts would
                // hide every account that has not used Yo before - including, on a first run,
                // all of them. Auto-select would sign the user straight in as whichever account
                // it likes, which is the one behaviour this feature is meant to prevent.
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .setServerClientId(serverClientId)
                .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val credential =
            try {
                CredentialManager.create(context).getCredential(context, request).credential
            } catch (cancelled: GetCredentialCancellationException) {
                return GoogleIdTokenResult.Cancelled
            } catch (empty: NoCredentialException) {
                return GoogleIdTokenResult.NoAccount
            } catch (failed: GetCredentialException) {
                return GoogleIdTokenResult.Unavailable
            }

        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenResult.Unavailable
        }
        return try {
            GoogleIdTokenResult.Success(
                GoogleIdTokenCredential.createFrom(credential.data).idToken,
            )
        } catch (malformed: GoogleIdTokenParsingException) {
            GoogleIdTokenResult.Unavailable
        }
    }
}
