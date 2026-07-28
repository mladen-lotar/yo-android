package hr.theshop.yo.domain.model

/**
 * What actually happened to a Yo, as opposed to what the sender was told.
 *
 * The send used to report nothing at all: the band flashed "YO!" the instant it was tapped, so a
 * Yo that was rate-limited, addressed to someone whose device never registered, or dropped by a
 * dead network looked exactly like one that arrived. The type is the fix - a `Unit` return had
 * nowhere to put the truth.
 *
 * Deliberately three cases and not the server's `reason` string: the app says the consequence,
 * never the mechanism.
 */
enum class YoSendOutcome {
    /** The server confirmed the push was handed to FCM. */
    Delivered,

    /**
     * The server answered, and said no. Rate limited, recipient never registered a device, or
     * FCM is not configured - which arrives as HTTP 200 with `delivered:false`, so a check for a
     * 2xx alone would call this a success.
     */
    NotDelivered,

    /** The request never got an answer: no network, DNS failure, timeout, unparseable body. */
    Unreachable,
}
