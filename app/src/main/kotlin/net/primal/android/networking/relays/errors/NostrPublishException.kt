package net.primal.android.networking.relays.errors

import net.primal.android.networking.relays.NostrPublishResult

class NostrPublishException(
    override val cause: Throwable?,
    val relayResults: List<NostrPublishResult> = emptyList(),
) : RuntimeException(relayResults.toFailureMessage(), cause)

private fun List<NostrPublishResult>.toFailureMessage(): String? =
    joinToString(separator = "; ") { result ->
        val detail = when {
            result.result != null -> result.result.message ?: "rejected without a reason"
            result.error != null -> result.error.message ?: result.error.javaClass.simpleName
            else -> "no response"
        }
        "${result.relayUrl}: $detail"
    }.takeIf { it.isNotEmpty() }
