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
            ?.let(::greetableFirstName)
            ?.let { "Hey $it, " }
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
     * The first name, but only when greeting by it actually reads like a greeting — otherwise null,
     * and the message simply opens with "get Yo".
     *
     * Real address books are not lists of people's names. They contain businesses
     * ("AS;Pizza/1"), nameless entries stored as raw numbers ("031 210 904"), and tagged entries
     * ("Mum ❤"). Naively taking the first space-delimited token produced "Hey AS;Pizza/1," and
     * "Hey 031," on a live device, so a token now has to look like a name to be used: at least two
     * characters and letters only.
     */
    private fun greetableFirstName(displayName: String): String? {
        val first = displayName.trim().substringBefore(' ').trim()
        if (first.length < 2) return null
        // Letters only — no digits, no punctuation. Accented letters are fine; emoji are not.
        if (!first.all { it.isLetter() }) return null
        return first
    }
}
