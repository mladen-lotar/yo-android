package hr.theshop.yo.domain.photo

data class PhotoPayload(
    val base64Data: String,
    val mimeType: String,
)

interface PhotoEncoder {
    suspend fun encodeForUpload(photoUri: String): PhotoPayload?
}
