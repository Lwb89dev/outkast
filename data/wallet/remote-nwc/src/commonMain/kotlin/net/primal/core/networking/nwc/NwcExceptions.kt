package net.primal.core.networking.nwc

/**
 * Every way a request to the wallet can fail. The three cases are kept apart because, for a
 * payment, they mean different things: refused (no money moved), never sent (no money moved) and
 * unanswered (nobody knows).
 */
sealed class NwcFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The wallet answered with an error; [code] is one of the NIP-47 codes. */
class NwcWalletError(val code: String, message: String) : NwcFailure(message)

/** The request never reached a relay, so the wallet cannot have acted on it. */
class NwcNotSent(message: String, cause: Throwable? = null) : NwcFailure(message, cause)

/** The request went out and no usable reply came back. A payment may or may not have happened. */
class NwcNoReply(message: String = "The wallet did not reply.", cause: Throwable? = null) : NwcFailure(message, cause)
