package net.primal.android.editor

import net.primal.domain.nostr.CommentTarget
import net.primal.domain.nostr.Naddr
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findCommentRootTarget
import net.primal.domain.posts.FeedPost

/**
 * What a NIP-22 comment hangs from: the [root] of the whole conversation and the [parent] this one
 * answers. Equal for a top-level comment.
 */
internal data class CommentContext(
    val root: CommentTarget,
    val parent: CommentTarget,
)

/** A top-level comment on an addressable event (an article): the article is both root and parent. */
internal fun Naddr.asTopLevelCommentContext(): CommentContext {
    val target = CommentTarget.Address(
        kind = kind,
        authorPubkey = userId,
        identifier = identifier,
        relayHint = relays.firstOrNull(),
    )
    return CommentContext(root = target, parent = target)
}

/**
 * A reply to this comment: the comment's own root is carried forward unchanged, and the comment
 * becomes the parent.
 *
 * When the comment names a root that cannot be pointed at (a web page, say, or tags without a
 * kind), [fallbackRoot] stands in, if the caller knows what the conversation is about; otherwise
 * there is no valid comment to build and the result is null.
 */
internal fun FeedPost.asReplyToCommentContext(relayHint: String?, fallbackRoot: CommentTarget?): CommentContext? {
    val root = tags.findCommentRootTarget() ?: fallbackRoot ?: return null
    val parent = CommentTarget.Event(
        eventId = eventId,
        kind = NostrEventKind.Comment.value,
        authorPubkey = author.authorId,
        relayHint = relayHint,
    )
    return CommentContext(root = root, parent = parent)
}

/**
 * Whether a reply is a NIP-22 comment, and under what.
 *
 * Replying to a kind 1 note is NIP-10, kind 1 with `e` markers, and stays that way: so does a reply
 * to a kind 1 comment on an article, which keeps a chain begun by an older client in one dialect.
 * A comment is for the two cases NIP-22 exists for here: writing on an article itself, and
 * answering somebody else's comment.
 *
 * @param replyToPost the post being answered, when there is one and it is known locally.
 * @param article the article the conversation is about, when it is one.
 */
internal fun resolveCommentContext(
    replyToPost: FeedPost?,
    replyToRelayHint: String?,
    article: Naddr?,
    answersSomething: Boolean,
): CommentContext? {
    val articleTarget = article?.asTopLevelCommentContext()?.root
    return when {
        replyToPost?.kind == NostrEventKind.Comment.value ->
            replyToPost.asReplyToCommentContext(relayHint = replyToRelayHint, fallbackRoot = articleTarget)

        !answersSomething -> article?.asTopLevelCommentContext()
        else -> null
    }
}
