package net.primal.domain.nostr.relay

data class RelayFilter(
    val kinds: List<Int>? = null,
    val authors: List<String>? = null,
    val ids: List<String>? = null,
    val limit: Int? = null,
    val since: Long? = null,
    val until: Long? = null,
    val eventTags: List<String>? = null,
    val addressTags: List<String>? = null,
    /**
     * Values for the `#d` tag. This, not [addressTags], is how an addressable event itself is looked
     * up: `#a` matches events that *refer* to an address, and an article does not carry its own.
     */
    val identifierTags: List<String>? = null,
    /**
     * Values for the uppercase `#E` tag, the root event of a NIP-22 comment. Every comment of a tree
     * carries it, however deep, so one query on it returns the whole tree.
     */
    val rootEventTags: List<String>? = null,
    /** Values for the `#q` tag (NIP-18): the notes that quote these event ids. */
    val quoteTags: List<String>? = null,
    /** Values for the uppercase `#A` tag, the root addressable event of a NIP-22 comment. */
    val rootAddressTags: List<String>? = null,
    val pubkeyTags: List<String>? = null,
    /** Values for the NIP-12 `t` hashtag tag. Topic feeds need this, not `#e`. */
    val hashtagTags: List<String>? = null,
    /** Values for a custom `bolt11` tag, used to resolve zap receipts by invoice. */
    val bolt11Tags: List<String>? = null,
    /** Optional NIP-50 full-text search expression. */
    val search: String? = null,
)
