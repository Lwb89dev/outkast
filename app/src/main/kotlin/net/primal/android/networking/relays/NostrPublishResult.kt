package net.primal.android.networking.relays

import net.primal.core.networking.sockets.NostrIncomingMessage

data class NostrPublishResult(
    val relayUrl: String,
    val result: NostrIncomingMessage.OkMessage? = null,
    val error: Throwable? = null,
) {
    fun isSuccessful(): Boolean = result?.success == true
}
