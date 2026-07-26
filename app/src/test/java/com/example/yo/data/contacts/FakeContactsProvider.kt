package com.example.yo.data.contacts

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** Serves a canned cursor (or null) so the repository's mapping can be tested without a device. */
class FakeContactsProvider(private val cursor: Cursor?) : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = cursor

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
