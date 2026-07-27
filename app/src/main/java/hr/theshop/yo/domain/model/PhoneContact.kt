package hr.theshop.yo.domain.model

/**
 * A contact from the device address book, reduced to the only two things Yo needs: a stable id for
 * list keys and a name to show on a band.
 *
 * Deliberately carries no phone number or email. Inviting goes through the system share sheet, so
 * the recipient is chosen inside WhatsApp/Viber/Messenger — Yo never has to read, hold or transmit
 * anyone's contact details.
 */
data class PhoneContact(
    val id: String,
    val displayName: String,
)
