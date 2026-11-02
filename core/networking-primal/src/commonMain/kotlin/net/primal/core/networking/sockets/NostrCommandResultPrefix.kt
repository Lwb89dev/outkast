package net.primal.core.networking.sockets

/** Standard NIP-20 command-result prefixes, now specified by NIP-01. */
enum class NostrCommandResultPrefix(val wireValue: String) {
    Duplicate("duplicate"),
    Pow("pow"),
    Blocked("blocked"),
    RateLimited("rate-limited"),
    Invalid("invalid"),
    Restricted("restricted"),
    Mute("mute"),
    Error("error"),

    /** NIP-42 extension used when publishing requires authentication. */
    AuthRequired("auth-required"),
    ;

    companion object {
        fun fromWireValue(value: String?): NostrCommandResultPrefix? = entries.firstOrNull { it.wireValue == value }
    }
}
