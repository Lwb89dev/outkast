package net.primal.data.repository.feed.processors

import net.primal.data.local.dao.notes.PostData
import net.primal.data.local.dao.profiles.ProfileData
import net.primal.data.local.db.CachingDatabase
import net.primal.data.repository.mappers.usernameUiFriendly
import net.primal.domain.nostr.utils.extractNoteId
import net.primal.domain.nostr.utils.extractProfileId
import net.primal.domain.nostr.utils.isNostrUri

/**
 * The profiles a batch of notes needs in order to render its `nostr:` mentions and quotes, as a map
 * by pubkey: [fromResponse] (the profiles the relay response being persisted carries) topped up from
 * the local database for everyone [posts] mention or quote that the response did not carry.
 *
 * Why the top-up exists: a mention row is stored as a *snapshot* of the mentioned person's display
 * handle, taken at the moment the citing note is persisted. The relay fetchers deliberately ask for a
 * profile only once per session (a claim per pubkey, so an active poster is not requested for every
 * page), which means the response that carries a note often does NOT carry the profile of the person
 * it mentions, because an earlier page already fetched and stored it. Resolving mentions against the
 * response alone therefore wrote the shortened npub fallback into the snapshot for a person whose name
 * was sitting in the database the whole time, and the note showed `@npub1abcd…wxyz` forever.
 *
 * Authors of notes that [posts] quote are included for the same reason: a quote only resolves to a
 * note card once its author's profile is known.
 */
internal suspend fun CachingDatabase.profilesForMentions(
    posts: List<PostData>,
    fromResponse: Map<String, ProfileData>,
    quotedPosts: Map<String, PostData> = emptyMap(),
): Map<String, ProfileData> {
    val uris = posts.flatMap { it.uris }.filter { it.isNostrUri() }
    val mentioned = uris.mapNotNull { it.extractProfileId() }
    val quotedAuthors = uris.mapNotNull { uri -> uri.extractNoteId()?.let { quotedPosts[it]?.authorId } }
    val missing = (mentioned + quotedAuthors).distinct().filterNot { it in fromResponse }
    if (missing.isEmpty()) return fromResponse
    return fromResponse + profiles().findProfileData(missing).associateBy { it.ownerId }
}

/**
 * Rewrites the mention rows that were stored with the shortened-npub fallback as their handle,
 * for every person in [profiles] who has a name, now that their profile is known.
 *
 * This is the other half of [profilesForMentions]. That one stops *new* snapshots from being taken
 * without a name that is already known; this one repairs the snapshots taken earlier, when the name
 * genuinely was not known yet and arrived later (the profile lands on a later page, on the profile
 * screen, or when the person is opened from a search). Without it the old note keeps showing the
 * npub until something happens to re-persist that very note, which for an old note is never.
 *
 * Call it right after the profiles have been written to the database.
 */
internal suspend fun CachingDatabase.healUnresolvedMentionHandles(profiles: Collection<ProfileData>) {
    val named = profiles.filter { it.hasChosenName() }.associateBy { it.ownerId }
    if (named.isEmpty()) return

    val unresolved = eventUris().findMentionsWithNpubHandle()
    if (unresolved.isEmpty()) return

    val healed = unresolved.mapNotNull { row ->
        val mention = row.referencedUser ?: return@mapNotNull null
        val profile = named[mention.userId] ?: return@mapNotNull null
        val handle = profile.usernameUiFriendly()
        if (handle == mention.handle) null else row.copy(referencedUser = mention.copy(handle = handle))
    }
    if (healed.isNotEmpty()) eventUris().upsertAllEventNostrUris(data = healed)
}

/** A profile that carries a name of its own, as opposed to one the UI can only label by its npub. */
private fun ProfileData.hasChosenName(): Boolean = !displayName.isNullOrBlank() || !handle.isNullOrBlank()
