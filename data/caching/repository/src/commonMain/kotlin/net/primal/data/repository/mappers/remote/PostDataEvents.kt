package net.primal.data.repository.mappers.remote

import kotlin.time.Clock
import net.primal.core.utils.detectUrls
import net.primal.core.utils.serialization.encodeToJsonString
import net.primal.data.local.dao.notes.PostData
import net.primal.data.local.dao.reads.ArticleData
import net.primal.data.local.dao.reads.HighlightData
import net.primal.domain.common.PrimalEvent
import net.primal.domain.common.util.takeContentOrNull
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.findCommentParentAuthor
import net.primal.domain.nostr.findCommentParentEventId
import net.primal.domain.nostr.findReplyTargetId
import net.primal.domain.nostr.getPubkeyFromReplyOrRootTag
import net.primal.domain.nostr.hasReplyMarker
import net.primal.domain.nostr.hasRootMarker
import net.primal.domain.nostr.isComment
import net.primal.domain.nostr.isDisplayablePictureNote
import net.primal.domain.nostr.isReply
import net.primal.domain.nostr.pictureCaption
import net.primal.domain.nostr.pictureImageUrls
import net.primal.domain.nostr.serialization.toNostrJsonObject
import net.primal.domain.nostr.utils.parseHashtags
import net.primal.domain.nostr.utils.parseNostrUriCandidates

private val postDataKinds = setOf(
    NostrEventKind.ShortTextNote.value,
    NostrEventKind.Poll.value,
    NostrEventKind.ZapPoll.value,
)

fun List<NostrEvent>.mapAsPostDataPO(
    referencedPosts: List<PostData>,
    referencedArticles: List<ArticleData>,
    referencedHighlights: List<HighlightData>,
): List<PostData> {
    val referencedPostAuthorsById = referencedPosts.associate { it.postId to it.authorId }
    val referencedArticlesMap = referencedArticles.associateBy { it.articleId }
    val referencedHighlightsMap = referencedHighlights.associateBy { it.highlightId }
    return map { event ->
        event.nostrEventAsPost(
            referencedPostAuthorsById = referencedPostAuthorsById,
            referencedArticlesMap = referencedArticlesMap,
            referencedHighlightsMap = referencedHighlightsMap,
        )
    }
}

fun List<PrimalEvent>.mapNotNullAsPostDataPO(
    referencedArticles: List<ArticleData> = emptyList(),
    referencedHighlights: List<HighlightData> = emptyList(),
): List<PostData> {
    val referencedArticlesMap = referencedArticles.associateBy { it.articleId }
    val referencedHighlightsMap = referencedHighlights.associateBy { it.highlightId }

    val events = this.mapNotNull { it.takeContentOrNull<NostrEvent>() }

    val postEvents = events.filter { event -> event.kind in postDataKinds }
    val pictureEvents = events.filter { event -> event.kind == NostrEventKind.PictureNote.value }
    val referencedPostAuthorsById = (postEvents + pictureEvents).associate { it.id to it.pubKey }

    val posts = postEvents.map {
        it.nostrEventAsPost(
            referencedPostAuthorsById = referencedPostAuthorsById,
            referencedArticlesMap = referencedArticlesMap,
            referencedHighlightsMap = referencedHighlightsMap,
        )
    }

    val pictures = pictureEvents.mapNotNull { it.toPicturePostDataOrNull() }

    return posts + pictures
}

private data class ReplyReference(
    val replyToPostId: String?,
    val replyToAuthorId: String?,
)

private fun NostrEvent.resolveReplyReference(
    referencedPostAuthorsById: Map<String, String> = emptyMap(),
    referencedArticlesMap: Map<String, ArticleData> = emptyMap(),
    referencedHighlightsMap: Map<String, HighlightData> = emptyMap(),
): ReplyReference =
    if (isComment()) {
        resolveCommentReplyReference(referencedPostAuthorsById)
    } else {
        resolveNoteReplyReference(
            referencedPostAuthorsById = referencedPostAuthorsById,
            referencedArticlesMap = referencedArticlesMap,
            referencedHighlightsMap = referencedHighlightsMap,
        )
    }

private fun NostrEvent.resolveNoteReplyReference(
    referencedPostAuthorsById: Map<String, String>,
    referencedArticlesMap: Map<String, ArticleData>,
    referencedHighlightsMap: Map<String, HighlightData>,
): ReplyReference {
    val referencedNoteId = this.tags.findReplyTargetId()
        ?: return ReplyReference(replyToPostId = null, replyToAuthorId = null)

    val referencedNoteAuthorId = this.tags.find { it.hasReplyMarker() }?.getPubkeyFromReplyOrRootTag()
        ?: this.tags.find { it.hasRootMarker() }?.getPubkeyFromReplyOrRootTag()
        ?: referencedPostAuthorsById[referencedNoteId]
        ?: referencedArticlesMap[referencedNoteId]?.authorId
        ?: referencedHighlightsMap[referencedNoteId]?.authorId

    return ReplyReference(replyToPostId = referencedNoteId, replyToAuthorId = referencedNoteAuthorId)
}

/**
 * What a NIP-22 comment answers. Its lowercase `e` is the parent, the comment or note it is a direct
 * reply to, and the lowercase `p` that parent's author. A top-level comment on an article has only
 * an `a` (the article) and so answers no stored note: the article, not a post, is its parent, and
 * the article's comment list is what links the two.
 */
private fun NostrEvent.resolveCommentReplyReference(referencedPostAuthorsById: Map<String, String>): ReplyReference {
    val parentId = tags.findCommentParentEventId()
        ?: return ReplyReference(replyToPostId = null, replyToAuthorId = null)
    return ReplyReference(
        replyToPostId = parentId,
        replyToAuthorId = tags.findCommentParentAuthor() ?: referencedPostAuthorsById[parentId],
    )
}

private fun NostrEvent.nostrEventAsPost(
    referencedPostAuthorsById: Map<String, String> = emptyMap(),
    referencedArticlesMap: Map<String, ArticleData> = emptyMap(),
    referencedHighlightsMap: Map<String, HighlightData> = emptyMap(),
): PostData {
    val reply = resolveReplyReference(
        referencedPostAuthorsById = referencedPostAuthorsById,
        referencedArticlesMap = referencedArticlesMap,
        referencedHighlightsMap = referencedHighlightsMap,
    )

    return PostData(
        postId = this.id,
        authorId = this.pubKey,
        createdAt = this.createdAt,
        kind = this.kind,
        tags = this.tags,
        content = this.content,
        uris = this.content.detectUrls() + this.content.parseNostrUriCandidates(),
        hashtags = this.parseHashtags(),
        sig = this.sig,
        raw = this.toNostrJsonObject().encodeToJsonString(),
        replyToPostId = reply.replyToPostId,
        replyToAuthorId = reply.replyToAuthorId,
    )
}

/**
 * A NIP-68 picture as a stored note: caption in [PostData.content], picture URLs in [PostData.uris]
 * so the gallery draws them even though they are not written in the text.
 *
 * Null when there is nothing to draw: expired, close-friends (the file is ciphertext), or no image.
 */
internal fun NostrEvent.toPicturePostDataOrNull(
    nowEpochSeconds: Long = Clock.System.now().epochSeconds,
): PostData? {
    if (!isDisplayablePictureNote(nowEpochSeconds)) return null
    val images = pictureImageUrls()
    val caption = pictureCaption()
    val reply = if (tags.isReply()) resolveReplyReference() else ReplyReference(null, null)
    return PostData(
        postId = id,
        authorId = pubKey,
        createdAt = createdAt,
        kind = kind,
        tags = tags,
        content = caption,
        uris = (images + caption.detectUrls() + caption.parseNostrUriCandidates()).distinct(),
        hashtags = parseHashtags(),
        sig = sig,
        raw = toNostrJsonObject().encodeToJsonString(),
        replyToPostId = reply.replyToPostId,
        replyToAuthorId = reply.replyToAuthorId,
    )
}
