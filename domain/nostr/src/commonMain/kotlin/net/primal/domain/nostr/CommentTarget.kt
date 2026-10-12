package net.primal.domain.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/*
 * NIP-22: comments (kind 1111).
 *
 * A comment names two things with two sets of tags. The ROOT scope is the thing the whole
 * conversation hangs from (an article, a picture, a web page), written in UPPERCASE: `E` (an event),
 * `A` (an addressable event), `K` (its kind) and `P` (its author). The PARENT is what this one
 * comment answers, in lowercase: `e`/`a`, `k`, `p`. A top-level comment has the same thing in both;
 * a reply to a comment keeps the root and names that comment as the parent. Because the root is on
 * every comment, a whole tree is one query on `#E` or `#A`, however deep it goes.
 *
 * What it is not for, per the NIP: replying to a kind 1 note. That stays NIP-10 (a kind 1 with `e`
 * markers); a kind 1111 is for everything that is not a text note, and for replies to comments.
 * Several clients write one under a kind 1 root anyway, so reading does not rely on that rule.
 */

/** The thing a comment is written under, as a root or as a parent. */
sealed interface CommentTarget {
    val kind: Int
    val authorPubkey: String
    val relayHint: String?

    /** An event, by id. */
    data class Event(
        val eventId: String,
        override val kind: Int,
        override val authorPubkey: String,
        override val relayHint: String? = null,
    ) : CommentTarget

    /** An addressable event (a long-form article, say), by `kind:pubkey:identifier`. */
    data class Address(
        override val kind: Int,
        override val authorPubkey: String,
        val identifier: String,
        override val relayHint: String? = null,
    ) : CommentTarget {
        val aTagValue: String get() = "$kind:$authorPubkey:$identifier"
    }
}

/**
 * The tags of a kind 1111 comment under [root], answering [parent].
 *
 * For a top-level comment pass the same target for both. For a reply to a comment, [root] is that
 * comment's own root (see [findCommentRootTarget]) and [parent] is the comment, as an
 * [CommentTarget.Event] of kind [NostrEventKind.Comment].
 */
fun buildCommentTags(root: CommentTarget, parent: CommentTarget): List<JsonArray> =
    root.scopeTags(upperCase = true) + parent.scopeTags(upperCase = false)

private fun CommentTarget.scopeTags(upperCase: Boolean): List<JsonArray> =
    listOf(
        locatorTag(upperCase = upperCase),
        buildJsonArray {
            add(if (upperCase) "K" else "k")
            add(kind.toString())
        },
        authorTag(upperCase = upperCase),
    )

private fun CommentTarget.locatorTag(upperCase: Boolean): JsonArray =
    when (this) {
        is CommentTarget.Event -> buildJsonArray {
            add(if (upperCase) "E" else "e")
            add(eventId)
            add(relayHint.orEmpty())
            add(authorPubkey)
        }

        is CommentTarget.Address -> buildJsonArray {
            add(if (upperCase) "A" else "a")
            add(aTagValue)
            add(relayHint.orEmpty())
        }.removeTrailingEmptyStrings()
    }

private fun CommentTarget.authorTag(upperCase: Boolean): JsonArray =
    buildJsonArray {
        add(if (upperCase) "P" else "p")
        add(authorPubkey)
        add(relayHint.orEmpty())
    }.removeTrailingEmptyStrings()

/** True for a NIP-22 comment. */
fun NostrEvent.isComment(): Boolean = kind == NostrEventKind.Comment.value
