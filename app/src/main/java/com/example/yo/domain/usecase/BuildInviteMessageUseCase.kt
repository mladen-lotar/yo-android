package com.example.yo.domain.usecase

import com.example.yo.domain.model.PhoneContact
import javax.inject.Inject

/**
 * Builds the text that goes into the share sheet. Pure and injectable so the wording is unit-tested
 * rather than buried in a Composable.
 *
 * The copy borrows Yo's own marketing lines verbatim — "The simplest & most efficient communication
 * tool in the world." and the tagline "Yo. It's that simple." — quoted in contemporaneous press
 * from the 2014 App Store listing.
 */
class BuildInviteMessageUseCase @Inject constructor() {
    operator fun invoke(
        contact: PhoneContact?,
        inviteUrl: String,
        senderUsername: String,
    ): String {
        val greeting = contact?.displayName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "Hey ${firstName(it)}, " }
            ?: ""
        return buildString {
            append(greeting)
            append("get Yo — the simplest & most efficient communication tool in the world. ")
            append("One tap, zero characters. ")
            append("I'm ${senderUsername.uppercase()} on it.")
            append("\n\n")
            append(inviteUrl)
            append("\n\nYo. It's that simple.")
        }
    }

    /**
     * Only the first name goes in the greeting: address books are full of entries like
     * "Alice Smith (Work)" and "Mum ❤" that read badly in full.
     */
    private fun firstName(displayName: String): String =
        displayName.trim().substringBefore(' ').ifEmpty { displayName.trim() }
}
