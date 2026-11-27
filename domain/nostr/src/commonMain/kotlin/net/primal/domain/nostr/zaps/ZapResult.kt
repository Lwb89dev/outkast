package net.primal.domain.nostr.zaps

sealed class ZapResult {
    data object Success : ZapResult()
    data class Failure(val error: ZapError) : ZapResult()
}

sealed class ZapError {
    data class InvalidZap(val message: String) : ZapError()
    data class FailedToFetchZapPayRequest(val cause: Throwable? = null) : ZapError()
    data class FailedToFetchZapInvoice(val cause: Throwable? = null) : ZapError()
    data class FailedToPayZap(val cause: Throwable? = null) : ZapError()
    data object FailedToSignEvent : ZapError()
    data object FailedToPublishEvent : ZapError()
    data class Timeout(val cause: Throwable? = null) : ZapError()

    /** The connected wallet refused to pay; [code] is a NIP-47 error code. Nothing was spent. */
    data class WalletRefused(val code: String, val message: String) : ZapError()

    /** The payment request went out and no reply came back: it may or may not have been paid. */
    data object PaymentUnconfirmed : ZapError()
    data class Unknown(val cause: Throwable? = null) : ZapError()
}
