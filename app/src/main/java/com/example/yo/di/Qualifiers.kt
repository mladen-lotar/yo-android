package com.example.yo.di

import javax.inject.Qualifier

/**
 * Distinguishes the invite URL from any other injectable String. Also the seam that lets unit tests
 * construct MainViewModel with a fake URL instead of reaching into BuildConfig.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InviteUrl

/**
 * The Google OAuth web client id. Blank when this build has none, which is how the sign-in screen
 * knows to leave the Google band off.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleClientId
