package net.primal.android.editor

import javax.inject.Inject
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import net.primal.android.editor.domain.NoteAttachment
import net.primal.android.editor.domain.PollOption
import net.primal.android.editor.domain.PollPublishRequest
import net.primal.android.editor.domain.asIMetaTag
import net.primal.android.emoji.Nip30
import net.primal.android.networking.UserAgentProvider
import net.primal.android.networking.relays.errors.NostrPublishException
import net.primal.android.nostr.publish.NostrPublisher
import net.primal.android.user.domain.RelayKind
import net.primal.android.user.domain.mapToRelayDO
import net.primal.android.user.repository.RelayRepository
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.events.EventRelayHintsRepository
import net.primal.domain.nostr.CommentTarget
import net.primal.domain.nostr.Naddr
import net.primal.domain.nostr.Nevent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.asClientTag
import net.primal.domain.nostr.asClosedAtTag
import net.primal.domain.nostr.asEndsAtTag
import net.primal.domain.nostr.asEventTag
import net.primal.domain.nostr.asOptionTag
import net.primal.domain.nostr.asPollOptionTag
import net.primal.domain.nostr.asPollTypeTag
import net.primal.domain.nostr.asPubkeyTag
import net.primal.domain.nostr.asRelayTag
import net.primal.domain.nostr.asReplaceableEventTag
import net.primal.domain.nostr.asValueMaximumTag
import net.primal.domain.nostr.asValueMinimumTag
import net.primal.domain.nostr.buildCommentTags
import net.primal.domain.nostr.getTagValueOrNull
import net.primal.domain.nostr.isEventIdTag
import net.primal.domain.nostr.isPubKeyTag
import net.primal.domain.nostr.isUppercasePubkeyTag
import net.primal.domain.nostr.parseEventTags
import net.primal.domain.nostr.parseHashtagTags
import net.primal.domain.nostr.parsePubkeyTags
import net.primal.domain.nostr.removeTrailingEmptyStrings
import net.primal.domain.nostr.parseReplaceableEventTags
import net.primal.domain.posts.FeedRepository
import net.primal.domain.publisher.PrimalPublishResult

class NotePublishHandler @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val nostrPublisher: NostrPublisher,
    private val eventRelayHintsRepository: EventRelayHintsRepository,
    private val feedRepository: FeedRepository,
    private val relayRepository: RelayRepository,
) {

    @Suppress("LongParameterList")
    private suspend fun prepareNote(
        content: String,
        attachments: List<NoteAttachment>,
        rootNoteNevent: Nevent?,
        rootArticleNaddr: Naddr?,
        rootHighlightNevent: Nevent?,
        replyToNoteNevent: Nevent?,
    ): PreparedNote {
        val noteContent = content.ensureWhitespaceBeforeUserTag()

        /* Event mentions from content */
        val mentionEventTags = noteContent.parseEventTags(marker = "mention")
        val mentionReplaceableEventTags = noteContent.parseReplaceableEventTags(marker = "mention")
        val allMentionedEventTags = mentionEventTags + mentionReplaceableEventTags

        /* Pubkey mentions from content and referenced pub keys */
        val mentionPubkeyTags = noteContent.parsePubkeyTags(marker = "mention").toSet()
        val referencedPubkeyTags = resolveReferencedPubkeyTags(
            rootNoteNevent = rootNoteNevent,
            rootArticleNaddr = rootArticleNaddr,
            rootHighlightNevent = rootHighlightNevent,
            replyToNoteNevent = replyToNoteNevent,
        )
        val allPubkeyTags = mentionPubkeyTags + referencedPubkeyTags

        /* Reply & Root tags */
        val replyEventTag = resolveReplyEventTag(
            rootNote = rootNoteNevent,
            replyTo = replyToNoteNevent,
            rootHighlight = rootHighlightNevent,
            rootArticle = rootArticleNaddr,
        )
        val rootTag = resolveRootEventTag(
            highlight = rootHighlightNevent,
            article = rootArticleNaddr,
            note = rootNoteNevent,
        )

        /* Hashtag tags */
        val hashtagTags = noteContent.parseHashtagTags().toSet()

        /* iMeta tags */
        val iMetaTags = attachments.filter { it.isMediaAttachment }.map { it.asIMetaTag() }

        /* Relay Hints */
        // The notes this one embeds are also named in `q` tags (NIP-18), which is where modern clients look
        // for who quotes whom; the NIP-10 `e` mention stays for the clients that still read that.
        val quoteTags = allMentionedEventTags.mapNotNull { it.asQuoteTag() }
        val allReferenceTags = (allMentionedEventTags + quoteTags + allPubkeyTags + replyEventTag + rootTag)
            .filterNotNull().toSet()
        val relayHintsMap = withContext(dispatcherProvider.io()) {
            val tagNoteIds = allReferenceTags.map { it.get(index = 1).jsonPrimitive.content }
            val hints = eventRelayHintsRepository.findRelaysByIds(eventIds = tagNoteIds)
            hints.associate { it.eventId to it.relays.first() }
        }
        val allReferenceTagsWithRelays = allReferenceTags.insertRelayHints(relayHintsMap = relayHintsMap)

        /* Content */
        val refinedContent = noteContent.appendAttachmentUrls(attachments)

        /* Outbox relays */
        val outboxRelays = replyToNoteNevent?.eventId?.let { noteId ->
            relayHintsMap[noteId]?.let { relayUrl -> listOf(relayUrl) }
        } ?: emptyList()

        return PreparedNote(
            refinedContent = refinedContent,
            referenceTags = allReferenceTagsWithRelays,
            hashtagTags = hashtagTags,
            iMetaTags = iMetaTags,
            outboxRelays = outboxRelays,
        )
    }

    @Throws(NostrPublishException::class)
    suspend fun publishShortTextNote(
        userId: String,
        content: String,
        attachments: List<NoteAttachment> = emptyList(),
        rootNoteNevent: Nevent? = null,
        rootArticleNaddr: Naddr? = null,
        rootHighlightNevent: Nevent? = null,
        replyToNoteNevent: Nevent? = null,
        customEmojis: Map<String, String> = emptyMap(),
    ): PrimalPublishResult {
        val commentContext = findCommentContext(
            rootArticleNaddr = rootArticleNaddr,
            rootHighlightNevent = rootHighlightNevent,
            replyToNoteNevent = replyToNoteNevent,
        )
        if (commentContext != null) {
            return publishComment(
                userId = userId,
                content = content,
                attachments = attachments,
                context = commentContext,
                customEmojis = customEmojis,
            )
        }

        val prepared = prepareNote(
            content = content,
            attachments = attachments,
            rootNoteNevent = rootNoteNevent,
            rootArticleNaddr = rootArticleNaddr,
            rootHighlightNevent = rootHighlightNevent,
            replyToNoteNevent = replyToNoteNevent,
        )

        return withContext(dispatcherProvider.io()) {
            nostrPublisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    pubKey = userId,
                    kind = NostrEventKind.ShortTextNote.value,
                    tags = (prepared.referenceTags + prepared.hashtagTags + prepared.iMetaTags).toList() +
                        Nip30.emojiTagsForContent(content = prepared.refinedContent, available = customEmojis) +
                        listOf(UserAgentProvider.CLIENT_NAME.asClientTag()),
                    content = prepared.refinedContent,
                ),
                outboxRelays = prepared.outboxRelays,
            )
        }
    }

    /**
     * A NIP-22 comment (kind 1111), see [resolveCommentContext] for when a reply is one.
     *
     * The conversation's root and the comment's parent are named by [CommentContext], and nothing
     * else says "reply": a comment carries no NIP-10 `e` markers, and a note or article it
     * mentions is a `q` tag, because a second `e` would be read as another parent.
     */
    @Throws(NostrPublishException::class)
    private suspend fun publishComment(
        userId: String,
        content: String,
        attachments: List<NoteAttachment>,
        context: CommentContext,
        customEmojis: Map<String, String>,
    ): PrimalPublishResult {
        val withHints = context.withRelayHints()
        val prepared = prepareComment(content = content, attachments = attachments, context = withHints)

        return withContext(dispatcherProvider.io()) {
            nostrPublisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    pubKey = userId,
                    kind = NostrEventKind.Comment.value,
                    tags = (prepared.referenceTags + prepared.hashtagTags + prepared.iMetaTags).toList() +
                        Nip30.emojiTagsForContent(content = prepared.refinedContent, available = customEmojis) +
                        listOf(UserAgentProvider.CLIENT_NAME.asClientTag()),
                    content = prepared.refinedContent,
                ),
                outboxRelays = prepared.outboxRelays,
            )
        }
    }

    private suspend fun findCommentContext(
        rootArticleNaddr: Naddr?,
        rootHighlightNevent: Nevent?,
        replyToNoteNevent: Nevent?,
    ): CommentContext? {
        // A highlight's thread is a kind 1 conversation, and it stays one.
        if (rootHighlightNevent != null) return null
        val replyToPost = replyToNoteNevent?.let { feedRepository.findPostsById(postId = it.eventId) }
        return resolveCommentContext(
            replyToPost = replyToPost,
            replyToRelayHint = replyToNoteNevent?.relays?.firstOrNull(),
            article = rootArticleNaddr,
            answersSomething = replyToNoteNevent != null,
        )
    }

    /** Fills in where each event of the comment's scope can be found, so a reader can fetch it. */
    private suspend fun CommentContext.withRelayHints(): CommentContext {
        val unhinted = listOf(root, parent)
            .filterIsInstance<CommentTarget.Event>()
            .filter { it.relayHint.isNullOrEmpty() }
            .map { it.eventId }
            .distinct()
        if (unhinted.isEmpty()) return this

        val hints = withContext(dispatcherProvider.io()) {
            eventRelayHintsRepository.findRelaysByIds(eventIds = unhinted)
        }.associate { it.eventId to it.relays.firstOrNull() }

        fun CommentTarget.hinted(): CommentTarget =
            when {
                this is CommentTarget.Event && relayHint.isNullOrEmpty() -> copy(relayHint = hints[eventId])
                else -> this
            }
        return copy(root = root.hinted(), parent = parent.hinted())
    }

    private fun prepareComment(
        content: String,
        attachments: List<NoteAttachment>,
        context: CommentContext,
    ): PreparedNote {
        val noteContent = content.ensureWhitespaceBeforeUserTag()
        val scopeTags = buildCommentTags(root = context.root, parent = context.parent)

        // The author of the root and of the parent are already named by the scope, as P and p.
        val namedPubkeys = scopeTags.filter { it.isPubKeyTag() || it.isUppercasePubkeyTag() }
            .mapNotNull { it.getTagValueOrNull() }
            .toSet()
        val mentionedPubkeyTags = noteContent.parsePubkeyTags(marker = "mention")
            .filterNot { it.getTagValueOrNull() in namedPubkeys }
        val mentionedEventTags = noteContent.parseEventTags(marker = "mention") +
            noteContent.parseReplaceableEventTags(marker = "mention")
        val quoteTags = mentionedEventTags.mapNotNull { it.asQuoteTag() }

        return PreparedNote(
            refinedContent = noteContent.appendAttachmentUrls(attachments),
            referenceTags = scopeTags + quoteTags + mentionedPubkeyTags,
            hashtagTags = noteContent.parseHashtagTags().toSet(),
            iMetaTags = attachments.filter { it.isMediaAttachment }.map { it.asIMetaTag() },
            outboxRelays = listOfNotNull(context.parent.relayHint?.takeIf { it.isNotEmpty() }),
        )
    }

    /** `["e", id, relay, "mention", pubkey]` or `["a", address, relay, "mention"]` as the `q` tag NIP-22 wants. */
    private fun JsonArray.asQuoteTag(): JsonArray? {
        val target = getTagValueOrNull() ?: return null
        val relay = getOrNull(RELAY_HINT_INDEX)?.jsonPrimitive?.content.orEmpty()
        val author = if (isEventIdTag()) getOrNull(MENTION_AUTHOR_INDEX)?.jsonPrimitive?.content.orEmpty() else ""
        return buildJsonArray {
            add("q")
            add(target)
            add(relay)
            add(author)
        }.removeTrailingEmptyStrings()
    }

    @Throws(NostrPublishException::class)
    suspend fun publishPoll(
        userId: String,
        content: String,
        attachments: List<NoteAttachment> = emptyList(),
        pollRequest: PollPublishRequest,
        rootNoteNevent: Nevent? = null,
        rootArticleNaddr: Naddr? = null,
        rootHighlightNevent: Nevent? = null,
        replyToNoteNevent: Nevent? = null,
        customEmojis: Map<String, String> = emptyMap(),
    ): PrimalPublishResult {
        val prepared = prepareNote(
            content = content,
            attachments = attachments,
            rootNoteNevent = rootNoteNevent,
            rootArticleNaddr = rootArticleNaddr,
            rootHighlightNevent = rootHighlightNevent,
            replyToNoteNevent = replyToNoteNevent,
        )

        val eventKind = if (pollRequest.isZapPoll) NostrEventKind.ZapPoll else NostrEventKind.Poll

        return withContext(dispatcherProvider.io()) {
            val writeRelayUrls = relayRepository.findRelays(userId, RelayKind.UserRelay)
                .map { it.mapToRelayDO() }
                .filter { it.write }
                .map { it.url }

            val pollTags = if (pollRequest.isZapPoll) {
                buildZapPollTags(
                    userId = userId,
                    choices = pollRequest.choices,
                    endsAt = pollRequest.endsAt,
                    minZapAmountInSats = pollRequest.minZapAmountInSats,
                    maxZapAmountInSats = pollRequest.maxZapAmountInSats,
                    writeRelayUrls = writeRelayUrls,
                )
            } else {
                buildUserPollTags(
                    choices = pollRequest.choices,
                    endsAt = pollRequest.endsAt,
                    writeRelayUrls = writeRelayUrls,
                )
            }

            nostrPublisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    pubKey = userId,
                    kind = eventKind.value,
                    tags = (prepared.referenceTags + prepared.hashtagTags + prepared.iMetaTags + pollTags).toList() +
                        Nip30.emojiTagsForContent(content = prepared.refinedContent, available = customEmojis) +
                        listOf(UserAgentProvider.CLIENT_NAME.asClientTag()),
                    content = prepared.refinedContent,
                ),
                outboxRelays = prepared.outboxRelays,
            )
        }
    }

    private fun buildUserPollTags(
        choices: List<PollOption>,
        endsAt: Long,
        writeRelayUrls: List<String>,
    ): List<JsonArray> {
        val tags = mutableListOf<JsonArray>()

        choices.forEach { (id, label) ->
            tags.add(id.asOptionTag(label))
        }

        tags.add("singlechoice".asPollTypeTag())
        tags.add(endsAt.toString().asEndsAtTag())

        writeRelayUrls.forEach { relayUrl ->
            tags.add(relayUrl.asRelayTag())
        }

        return tags
    }

    @Suppress("LongParameterList")
    private fun buildZapPollTags(
        userId: String,
        choices: List<PollOption>,
        endsAt: Long,
        minZapAmountInSats: Long?,
        maxZapAmountInSats: Long?,
        writeRelayUrls: List<String>,
    ): List<JsonArray> {
        val tags = mutableListOf<JsonArray>()

        choices.forEachIndexed { index, choice ->
            tags.add(index.toString().asPollOptionTag(label = choice.label))
        }

        tags.add(userId.asPubkeyTag(relayHint = writeRelayUrls.firstOrNull()))

        tags.add(endsAt.toString().asClosedAtTag())

        if (minZapAmountInSats != null) {
            tags.add(minZapAmountInSats.toString().asValueMinimumTag())
        }
        if (maxZapAmountInSats != null) {
            tags.add(maxZapAmountInSats.toString().asValueMaximumTag())
        }

        writeRelayUrls.forEach { relayUrl ->
            tags.add(relayUrl.asRelayTag())
        }

        return tags
    }

    private fun resolveRootEventTag(
        highlight: Nevent?,
        article: Naddr?,
        note: Nevent?,
    ) = when {
        highlight != null -> highlight.asEventTag(marker = "root")
        article != null -> article.asReplaceableEventTag(marker = "root")
        note != null -> note.asEventTag(marker = "root")
        else -> null
    }

    private fun resolveReplyEventTag(
        rootNote: Nevent?,
        rootHighlight: Nevent?,
        rootArticle: Naddr?,
        replyTo: Nevent?,
    ): JsonArray? {
        return when {
            rootArticle != null || rootHighlight != null -> {
                replyTo?.asEventTag(marker = "reply")
            }

            replyTo?.eventId != rootNote?.eventId -> {
                replyTo?.asEventTag(marker = "reply")
            }

            else -> null
        }
    }

    private suspend fun resolveReferencedPubkeyTags(
        rootNoteNevent: Nevent?,
        rootArticleNaddr: Naddr?,
        rootHighlightNevent: Nevent?,
        replyToNoteNevent: Nevent?,
    ): Set<JsonArray> {
        val replyToPostData = replyToNoteNevent?.let {
            feedRepository.findPostsById(postId = it.eventId)
        }

        val replyToExistingPubkeyTags = replyToPostData?.tags?.filter { it.isPubKeyTag() }?.toSet() ?: emptySet()
        val replyToAuthorPubkeyTag = replyToNoteNevent?.asPubkeyTag()
        val rootNoteAuthorPubkeyTag = rootNoteNevent?.asPubkeyTag()
        val rootHighlightAuthorPubkeyTag = rootHighlightNevent?.asPubkeyTag()
        val rootArticleAuthorPubkeyTag = rootArticleNaddr?.asPubkeyTag()

        return sequenceOf(
            replyToExistingPubkeyTags,
            listOfNotNull(
                replyToAuthorPubkeyTag,
                rootNoteAuthorPubkeyTag,
                rootHighlightAuthorPubkeyTag,
                rootArticleAuthorPubkeyTag,
            ),
        ).flatten().toSet()
    }

    private fun String.appendAttachmentUrls(attachments: List<NoteAttachment>): String {
        val attachmentUrls = attachments.mapNotNull { it.remoteUrl }
        val refinedContent = if (attachmentUrls.isEmpty()) {
            this
        } else {
            StringBuilder().apply {
                append(this@appendAttachmentUrls)
                appendLine()
                appendLine()
                attachmentUrls.forEachIndexed { index, url ->
                    append(url)
                    if (index < attachmentUrls.size - 1) appendLine()
                }
            }.toString()
        }
        return refinedContent
    }

    private fun Set<JsonArray>.insertRelayHints(relayHintsMap: Map<String, String>) =
        this.map {
            val noteId = it[1].jsonPrimitive.content
            val relayHint = relayHintsMap.getOrDefault(key = noteId, defaultValue = "")
            JsonArray(
                it.toMutableList().apply {
                    if (relayHint.isNotEmpty()) {
                        if (this.size > 2) {
                            this[2] = JsonPrimitive(relayHint)
                        } else {
                            add(index = 2, JsonPrimitive(relayHint))
                        }
                    }
                },
            )
        }

    private fun String.ensureWhitespaceBeforeUserTag(): String = this.replace(Regex("([^-\\s])(nostr:npub)"), "$1 $2")

    private companion object {
        /** In `["e", id, relay, "mention", pubkey]` the relay hint is the third element... */
        const val RELAY_HINT_INDEX = 2

        /** ...and the author's pubkey the fifth. */
        const val MENTION_AUTHOR_INDEX = 4
    }

    private data class PreparedNote(
        val refinedContent: String,
        val referenceTags: List<JsonArray>,
        val hashtagTags: Set<JsonArray>,
        val iMetaTags: List<JsonArray>,
        val outboxRelays: List<String>,
    )
}
