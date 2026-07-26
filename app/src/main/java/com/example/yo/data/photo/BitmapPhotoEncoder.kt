package com.example.yo.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.example.yo.domain.photo.PhotoEncoder
import com.example.yo.domain.photo.PhotoPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val UPLOAD_MAX_EDGE_PX = 1280

internal fun calculateBitmapSampleSize(
    width: Int,
    height: Int,
    maxLongEdgePx: Int,
): Int {
    val longEdge = max(width, height)
    var sampleSize = 1
    while (longEdge / (sampleSize * 2) >= maxLongEdgePx) {
        sampleSize *= 2
    }
    return sampleSize
}

internal fun decodeSampledBitmap(
    context: Context,
    uri: Uri,
    maxLongEdgePx: Int,
): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(uri) ?: return null
        boundsStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null
        }

        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize =
                    calculateBitmapSampleSize(
                        width = options.outWidth,
                        height = options.outHeight,
                        maxLongEdgePx = maxLongEdgePx,
                    )
            }
        val bitmapStream = context.contentResolver.openInputStream(uri) ?: return null
        val bitmap = bitmapStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null
        applyExifOrientation(context, uri, bitmap)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private fun applyExifOrientation(
    context: Context,
    uri: Uri,
    bitmap: Bitmap,
): Bitmap {
    return try {
        val exifStream = context.contentResolver.openInputStream(uri) ?: return bitmap
        val orientation =
            exifStream.use { stream ->
                ExifInterface(stream)
                    .getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_UNDEFINED,
                    )
            }
        val matrix =
            Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                        setRotate(180f)
                        postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_TRANSPOSE -> {
                        setRotate(90f)
                        postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> {
                        setRotate(-90f)
                        postScale(-1f, 1f)
                    }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                    else -> return bitmap
                }
            }
        Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        bitmap
    }
}

internal fun scaleToLongEdge(
    bitmap: Bitmap,
    maxLongEdgePx: Int,
): Bitmap {
    val longEdge = max(bitmap.width, bitmap.height)
    if (longEdge <= maxLongEdgePx) {
        return bitmap
    }

    val scale = maxLongEdgePx.toFloat() / longEdge
    val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
}

class BitmapPhotoEncoder(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : PhotoEncoder {

    // Dagger ignores Kotlin default arguments, so the injectable constructor is explicit rather
    // than a defaulted parameter. The two-arg form above stays available to tests.
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, Dispatchers.IO)

    override suspend fun encodeForUpload(photoUri: String): PhotoPayload? =
        withContext(ioDispatcher) {
            try {
                val decoded =
                    decodeSampledBitmap(
                        context = context,
                        uri = Uri.parse(photoUri),
                        maxLongEdgePx = UPLOAD_MAX_EDGE_PX,
                    ) ?: return@withContext null
                // Uploads need an exact cap; thumbnail sampling may remain up to 2x the requested edge.
                val scaled = scaleToLongEdge(decoded, UPLOAD_MAX_EDGE_PX)
                val output = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    return@withContext null
                }
                PhotoPayload(
                    base64Data = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                    mimeType = JPEG_MIME_TYPE,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    private companion object {
        const val JPEG_QUALITY = 70
        const val JPEG_MIME_TYPE = "image/jpeg"
    }
}
