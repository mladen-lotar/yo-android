package hr.theshop.yo.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BitmapPhotoEncoderTest {
    // The encoder hardcoded Dispatchers.IO, so its work hopped to a shared pool that runTest's
    // 10s wall-clock budget cannot wait out on a loaded machine. Injecting the test dispatcher
    // keeps the encode on the test scheduler. Fixture bitmaps are built before runTest is
    // entered: writing a 4032x3024 JPEG is test setup, not the behaviour under test, and it
    // alone burned ~4s of that budget under load.
    private val testDispatcher = StandardTestDispatcher()

    private fun encoder(context: Context) = BitmapPhotoEncoder(context, testDispatcher)

    @Test
    fun `encodeForUpload returns a valid JPEG payload`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri =
            writeBitmap(
                context = context,
                width = 64,
                height = 32,
                format = Bitmap.CompressFormat.PNG,
            )

        runTest(testDispatcher) {
            val payload = encoder(context).encodeForUpload(uri.toString())

            assertNotNull(payload)
            val encodedPayload = requireNotNull(payload)
            assertEquals("image/jpeg", encodedPayload.mimeType)
            val jpegBytes = Base64.decode(encodedPayload.base64Data, Base64.NO_WRAP)
            val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            assertNotNull(decoded)
            val decodedBitmap = requireNotNull(decoded)
            assertEquals(64, decodedBitmap.width)
            assertEquals(32, decodedBitmap.height)
        }
    }

    @Test
    fun `encodeForUpload returns null for a nonexistent Uri`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val missingFile = File(context.cacheDir, "missing-photo-${System.nanoTime()}.jpg")

        runTest(testDispatcher) {
            val payload =
                encoder(context)
                    .encodeForUpload(Uri.fromFile(missingFile).toString())

            assertNull(payload)
        }
    }

    @Test
    fun `encodeForUpload applies EXIF rotation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceWidth = 80
        val sourceHeight = 40
        val uri =
            writeBitmap(
                context = context,
                width = sourceWidth,
                height = sourceHeight,
                format = Bitmap.CompressFormat.JPEG,
                exifOrientation = ExifInterface.ORIENTATION_ROTATE_90,
            )
        val sourceFile = File(requireNotNull(uri.path))

        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface(sourceFile).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            ),
        )

        runTest(testDispatcher) {
            val payload = requireNotNull(encoder(context).encodeForUpload(uri.toString()))
            val jpegBytes = Base64.decode(payload.base64Data, Base64.NO_WRAP)
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size))

            assertEquals(sourceHeight, decoded.width)
            assertEquals(sourceWidth, decoded.height)
        }
    }

    @Test
    fun `calculateBitmapSampleSize returns power of two samples`() {
        assertEquals(1, calculateBitmapSampleSize(width = 1920, height = 1080, maxLongEdgePx = 1280))
        assertEquals(2, calculateBitmapSampleSize(width = 4032, height = 3024, maxLongEdgePx = 1280))
        assertEquals(4, calculateBitmapSampleSize(width = 8000, height = 6000, maxLongEdgePx = 1280))
    }

    @Test
    fun `encoded JPEG long edge does not exceed the upload cap`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri =
            writeBitmap(
                context = context,
                width = 4032,
                height = 3024,
                format = Bitmap.CompressFormat.JPEG,
            )

        runTest(testDispatcher) {
            val payload = encoder(context).encodeForUpload(uri.toString())

            assertNotNull(payload)
            val jpegBytes = Base64.decode(requireNotNull(payload).base64Data, Base64.NO_WRAP)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
            assertTrue(options.outWidth > 0)
            assertTrue(options.outHeight > 0)
            assertTrue(maxOf(options.outWidth, options.outHeight) <= UPLOAD_MAX_EDGE_PX)
        }
    }

    private fun writeBitmap(
        context: Context,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
        exifOrientation: Int? = null,
    ): Uri {
        val suffix = if (format == Bitmap.CompressFormat.JPEG) ".jpg" else ".img"
        val file = File.createTempFile("bitmap-photo-", suffix, context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmap.eraseColor(Color.BLUE)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(format, 90, output))
        }
        bitmap.recycle()
        exifOrientation?.let { orientation ->
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return Uri.fromFile(file)
    }
}
