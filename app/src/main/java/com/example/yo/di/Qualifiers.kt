package com.example.yo.di

import javax.inject.Qualifier

/**
 * Distinguishes the invite URL from any other injectable String. Also the seam that lets unit tests
 * construct MainViewModel with a fake URL instead of reaching into BuildConfig.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InviteUrl
