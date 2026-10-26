package net.primal.android.user.repository

import androidx.room.withTransaction
import io.github.aakira.napier.Napier
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import net.primal.android.core.compose.profile.model.UserProfileItemUi
import net.primal.android.core.compose.profile.model.mapAsUserProfileUi
import net.primal.android.core.utils.authorNameUiFriendly
import net.primal.android.core.utils.usernameUiFriendly
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.android.networking.relays.buildRelayFilter
import net.primal.android.networking.relays.errors.NostrPublishException
import net.primal.android.nostr.publish.NostrPublisher
import net.primal.android.profile.domain.asProfileDataDO
import net.primal.android.profile.domain.ProfileMetadata
import net.primal.android.user.accounts.UserAccountsStore
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.android.user.accounts.copyFollowListIfNotNull
import net.primal.android.user.accounts.copyIfNotNull
import net.primal.android.user.credentials.CredentialsStore
import net.primal.android.user.db.RecentSearch
import net.primal.android.user.db.UserProfileInteraction
import net.primal.android.user.db.UsersDatabase
import net.primal.android.user.domain.ContentDisplaySettings
import net.primal.android.user.domain.UserAccount
import net.primal.android.user.domain.asUserAccountFromFollowListEvent
import net.primal.core.networking.blossom.AndroidPrimalBlossomUploadService
import net.primal.core.networking.blossom.BlossomException
import net.primal.core.networking.blossom.UploadResult
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.getOrDefault
import net.primal.core.utils.map
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import net.primal.domain.common.UserProfileSearchItem
import net.primal.domain.common.exception.NetworkException
import net.primal.domain.global.CachingImportRepository
import net.primal.domain.nostr.ContentMetadata
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.SignatureException
import net.primal.domain.nostr.cryptography.utils.hexToNpubHrp
import net.primal.domain.posts.FeedRepository
import net.primal.domain.profile.ProfileRepository
import net.primal.domain.user.UserDataCleanupRepository

class UserRepository @Inject constructor(
    private val usersDatabase: UsersDatabase,
    private val dispatchers: DispatcherProvider,
    private val accountsStore: UserAccountsStore,
    private val credentialsStore: CredentialsStore,
    private val activeAccountStore: ActiveAccountStore,
    private val primalUploadService: AndroidPrimalBlossomUploadService,
    private val relaysSocketManager: RelaysSocketManager,
    private val nostrPublisher: NostrPublisher,
    private val profileRepository: ProfileRepository,
    private val userDataCleanupRepository: UserDataCleanupRepository,
    private val cachingImportRepository: CachingImportRepository,
    private val feedRepository: FeedRepository,
) {
    suspend fun setActiveAccount(userId: String) =
        withContext(dispatchers.io()) {
            accountsStore.getAndUpdateAccount(userId = userId) { copy(lastAccessedAt = Instant.now().epochSecond) }
            activeAccountStore.setActiveUserId(pubkey = userId)
        }

    fun isNpubLogin(userId: String) =
        runCatching { credentialsStore.isNpubCredential(npub = userId.hexToNpubHrp()) }.getOrDefault(false)

    suspend fun ensureLocalUserAccount(userId: String): UserAccount {
        if (userId.isBlank()) return UserAccount.EMPTY
        return accountsStore.getAndUpdateAccount(userId = userId) { this }
    }

    suspend fun fetchAndUpdateUserAccount(userId: String): UserAccount {
        if (userId.isBlank()) return UserAccount.EMPTY
        ensureLocalUserAccount(userId)
        val userProfile = runCatching { fetchUserProfileOrNull(userId = userId) }.getOrNull()
        val userStats = userProfile?.takeIf {
            it.followersCount != null && it.followingCount != null && it.notesCount != null
        }
        val followList = runCatching { fetchUserFollowListOrNull(userId = userId) }.getOrNull()

        return accountsStore.getAndUpdateAccount(userId = userId) {
            copyIfNotNull(
                profile = userProfile,
                stats = userStats,
                followList = followList,
            )
        }
    }

    private suspend fun fetchUserProfileOrNull(userId: String): UserAccount? =
        withContext(dispatchers.io()) {
            val userData = profileRepository.fetchProfile(profileId = userId)
                ?: return@withContext null

            val userStats = profileRepository.findProfileStats(profileIds = listOf(userId))
                .firstOrNull { it.profileId == userId }

            UserAccount(
                pubkey = userId,
                authorDisplayName = userData.authorNameUiFriendly(),
                userDisplayName = userData.usernameUiFriendly(),
                avatarCdnImage = userData.avatarCdnImage,
                internetIdentifier = userData.internetIdentifier,
                lightningAddress = userData.lightningAddress,
                followersCount = userStats?.followers,
                followingCount = userStats?.following,
                notesCount = userStats?.notesCount,
                repliesCount = userStats?.repliesCount,
                primalLegendProfile = userData.primalPremiumInfo?.legendProfile,
                blossomServers = userData.blossoms,
            )
        }

    private suspend fun fetchUserFollowListOrNull(userId: String): UserAccount? =
        withContext(dispatchers.io()) {
            // The authoritative remote source is the user's kind-3 event on Nostr.
            // If relays are temporarily unavailable, keep the last locally persisted
            // list rather than falling back to the centralized Primal cache API.
            val fromRelays = fetchFollowListFromRelays(userId)
            val local = accountsStore.findByIdOrNull(userId = userId)?.takeIf { it.following.isNotEmpty() }
            when {
                fromRelays == null -> local
                local == null -> fromRelays
                // The relays can answer with an older list than the one this device last published (the ones
                // that answered had not received it yet). Building the next follow or unfollow on that would
                // publish a list missing every recent change, so the newer of the two is the base.
                local.isNewerFollowListThan(fromRelays) -> local
                else -> fromRelays
            }
        }

    private fun UserAccount.isNewerFollowListThan(other: UserAccount): Boolean =
        (followListCreatedAt ?: 0L) > (other.followListCreatedAt ?: 0L)

    private suspend fun fetchFollowListFromRelays(userId: String): UserAccount? {
        val result = runCatching {
            relaysSocketManager.queryEvents(
                filter = buildRelayFilter(
                    kinds = listOf(NostrEventKind.FollowList.value),
                    authors = listOf(userId),
                    limit = 5,
                ),
            )
        }.getOrNull() ?: return null

        val latest = result.events.maxByOrNull { it.createdAt } ?: return null
        Napier.i {
            "Relay kind-3 for $userId: events=${result.uniqueEventCount} " +
                "eose=${result.eoseRelays.size} fail=${result.failedRelays.size}"
        }
        return latest.asUserAccountFromFollowListEvent()
    }

    suspend fun clearAllUserRelatedData(userId: String) =
        withContext(dispatchers.io()) {
            // Called after the account is already gone from the accounts store (see
            // AuthRepository.logout), so an empty list here means nobody is left logged in and the
            // shared coordinator/event caches are safe to drop along with this account's own rows.
            val isLastAccount = accountsStore.userAccounts.value.isEmpty()
            userDataCleanupRepository.clearUserData(userId, clearSharedCaches = isLastAccount)
            usersDatabase.withTransaction {
                usersDatabase.userProfileInteractions().deleteAllByOwnerId(ownerId = userId)
                usersDatabase.recentSearches().deleteAllByOwnerId(ownerId = userId)
                usersDatabase.relays().deleteAll(userId = userId)
            }
        }

    /**
     * Stores [contactsUserAccount]'s follow list in the local account.
     *
     * A list read from the relays is only adopted if it is not older than the one held (see
     * [copyFollowListIfNotNull]). [force] is for a list this device has just signed and published: it is by
     * definition the one that counts, and must not be turned away because some other client once stamped a
     * follow list with a clock that runs ahead of this phone's.
     */
    suspend fun updateFollowList(userId: String, contactsUserAccount: UserAccount, force: Boolean = false) {
        accountsStore.getAndUpdateAccount(userId = userId) {
            val held = if (force) copy(followListCreatedAt = null) else this
            held.copyFollowListIfNotNull(followList = contactsUserAccount)
                .copy(followingCount = contactsUserAccount.following.size)
        }
    }

    suspend fun removeUserAccountById(pubkey: String) {
        accountsStore.deleteAccount(pubkey = pubkey)
    }

    @Throws(BlossomException::class, NostrPublishException::class, SignatureException::class)
    suspend fun setProfileMetadata(userId: String, profileMetadata: ProfileMetadata) {
        val pictureUrl = profileMetadata.remotePictureUrl
            ?: if (profileMetadata.localPictureUri != null) {
                val uploadResult = primalUploadService.upload(
                    uri = profileMetadata.localPictureUri,
                    userId = userId,
                )
                if (uploadResult is UploadResult.Success) {
                    uploadResult.remoteUrl
                } else {
                    null
                }
            } else {
                null
            }

        val bannerUrl = profileMetadata.remoteBannerUrl
            ?: if (profileMetadata.localBannerUri != null) {
                val uploadResult = primalUploadService.upload(
                    uri = profileMetadata.localBannerUri,
                    userId = userId,
                )
                if (uploadResult is UploadResult.Success) {
                    uploadResult.remoteUrl
                } else {
                    null
                }
            } else {
                null
            }

        setUserProfileAndUpdateLocally(
            userId = userId,
            contentMetadata = ContentMetadata(
                displayName = profileMetadata.displayName,
                name = profileMetadata.username,
                website = profileMetadata.website,
                about = profileMetadata.about,
                lud16 = profileMetadata.lightningAddress,
                nip05 = profileMetadata.nostrVerification,
                picture = pictureUrl,
                banner = bannerUrl,
            ),
        )
    }

    @Throws(NostrPublishException::class, NetworkException::class, SignatureException::class)
    suspend fun setNostrAddress(userId: String, nostrAddress: String) =
        withContext(dispatchers.io()) {
            setUserProfileAndUpdateLocally(
                userId = userId,
                contentMetadata = loadLocalOrRelayMetadata(userId).copy(nip05 = nostrAddress),
            )
        }

    @Throws(NostrPublishException::class, NetworkException::class, SignatureException::class)
    suspend fun setLightningAddress(userId: String, lightningAddress: String) =
        withContext(dispatchers.io()) {
            setUserProfileAndUpdateLocally(
                userId = userId,
                contentMetadata = loadLocalOrRelayMetadata(userId).copy(lud16 = lightningAddress),
            )
        }

    private suspend fun loadLocalOrRelayMetadata(userId: String): ContentMetadata {
        val profile = profileRepository.fetchProfile(profileId = userId)
            ?: throw NetworkException("Profile metadata not found on local storage or relays.")
        return ContentMetadata(
            name = profile.handle,
            nip05 = profile.internetIdentifier,
            about = profile.about,
            lud16 = profile.lightningAddress,
            displayName = profile.displayName,
            picture = profile.avatarCdnImage?.sourceUrl,
            banner = profile.bannerCdnImage?.sourceUrl,
            website = profile.website,
        )
    }

    private suspend fun setUserProfileAndUpdateLocally(userId: String, contentMetadata: ContentMetadata) {
        val profileMetadataNostrEvent = nostrPublisher.publishUserProfile(
            userId = userId,
            contentMetadata = contentMetadata,
        )
        val profileData = profileMetadataNostrEvent.asProfileDataDO(
            cdnResources = emptyMap(),
            primalUserNames = emptyMap(),
            primalPremiumInfo = emptyMap(),
            primalLegendProfiles = emptyMap(),
            blossomServers = emptyMap(),
        )

        cachingImportRepository.cacheNostrEvents(profileMetadataNostrEvent)
        accountsStore.getAndUpdateAccount(userId = userId) {
            this.copy(
                authorDisplayName = profileData.authorNameUiFriendly(),
                userDisplayName = profileData.usernameUiFriendly(),
                avatarCdnImage = profileData.avatarCdnImage,
                internetIdentifier = profileData.internetIdentifier,
                lightningAddress = profileData.lightningAddress,
            )
        }
    }

    suspend fun updateContentDisplaySettings(
        userId: String,
        reducer: ContentDisplaySettings.() -> ContentDisplaySettings,
    ) {
        accountsStore.getAndUpdateAccount(userId = userId) {
            copy(contentDisplaySettings = this.contentDisplaySettings.reducer())
        }
    }

    suspend fun updateCachingProxyEnabled(userId: String, enabled: Boolean) {
        accountsStore.getAndUpdateAccount(userId = userId) {
            copy(cachingProxyEnabled = enabled)
        }
    }

    suspend fun updateUpgradeDotTimestamp(userId: String) {
        accountsStore.getAndUpdateAccount(userId = userId) {
            copy(lastBuyPremiumTimestampInMillis = Clock.System.now().toEpochMilliseconds())
        }
    }

    @Throws(FollowListNotFound::class, NostrPublishException::class, SignatureException::class)
    suspend fun updateFollowList(
        userId: String,
        forceUpdate: Boolean,
        reducer: Set<String>.() -> Set<String>,
    ) = withContext(dispatchers.io()) {
        val userFollowList = fetchUserFollowListOrNull(userId = userId)
        val isEmptyFollowList = userFollowList == null || userFollowList.following.isEmpty()
        if (isEmptyFollowList && !forceUpdate) {
            throw FollowListNotFound()
        }

        // The local account is not touched before the publish: the list that goes out is built from `userFollowList`,
        // and the account takes the published one right after. Writing the relays' version in between used to be
        // visible, since the screens read the follow state from the account: for as long as the publish took, a
        // person who had just been unfollowed showed as followed again, then as unfollowed.
        val existingFollowing = userFollowList?.following ?: emptySet()
        val newFollowing = existingFollowing.reducer()
        val publishedEvent = setFollowList(
            userId = userId,
            contacts = newFollowing,
            content = userFollowList?.followListEventContent ?: "",
            interests = userFollowList?.interests.orEmpty(),
        )
        syncFeedsWithFollowList(
            userId = userId,
            publishedEvent = publishedEvent,
            unfollowedIds = existingFollowing - newFollowing,
        )
    }

    /**
     * The list was published and the account updated; the feeds still hold the old one. Best effort:
     * the follow itself has already succeeded, so a failure here is logged and nothing more.
     */
    private suspend fun syncFeedsWithFollowList(
        userId: String,
        publishedEvent: NostrEvent,
        unfollowedIds: Set<String>,
    ) {
        runCatching {
            feedRepository.onFollowListPublished(
                userId = userId,
                followListEvent = publishedEvent,
                unfollowedIds = unfollowedIds,
            )
        }.onFailure { error ->
            Napier.w(throwable = error) { "Failed to sync the feeds with the new follow list." }
        }
    }

    @Throws(NostrPublishException::class, SignatureException::class)
    suspend fun setFollowList(
        userId: String,
        contacts: Set<String>,
        content: String = "",
        interests: List<String> = emptyList(),
    ): NostrEvent =
        withContext(dispatchers.io()) {
            val nostrEventResponse = nostrPublisher.publishUserFollowList(
                userId = userId,
                contacts = contacts,
                content = content,
                interests = interests,
            )
            updateFollowList(
                userId = userId,
                contactsUserAccount = nostrEventResponse.asUserAccountFromFollowListEvent(),
                force = true,
            )
            nostrEventResponse
        }

    @Throws(NostrPublishException::class, SignatureException::class)
    suspend fun recoverFollowList(
        userId: String,
        tags: List<JsonArray>,
        content: String,
    ) = withContext(dispatchers.io()) {
        val publishResult = nostrPublisher.signPublishImportNostrEvent(
            unsignedNostrEvent = NostrUnsignedEvent(
                pubKey = userId,
                kind = NostrEventKind.FollowList.value,
                tags = tags,
                content = content,
            ),
        )

        updateFollowList(
            userId = userId,
            contactsUserAccount = publishResult.nostrEvent.asUserAccountFromFollowListEvent(),
            force = true,
        )
    }

    suspend fun markAsInteracted(profileId: String, ownerId: String) =
        withContext(dispatchers.io()) {
            usersDatabase.userProfileInteractions().upsert(
                UserProfileInteraction(
                    profileId = profileId,
                    lastInteractionAt = Instant.now().epochSecond,
                    ownerId = ownerId,
                ),
            )
        }

    suspend fun saveRecentSearch(ownerId: String, query: String) =
        withContext(dispatchers.io()) {
            usersDatabase.recentSearches().upsert(
                RecentSearch(
                    ownerId = ownerId,
                    query = query,
                    lastSearchedAt = Instant.now().epochSecond,
                ),
            )
        }

    fun observeRecentSearches(ownerId: String, limit: Int): Flow<List<String>> =
        usersDatabase.recentSearches()
            .observeRecentSearches(ownerId = ownerId, limit = limit)
            .map { rows -> rows.map { it.query } }
            .distinctUntilChanged()

    fun observeRecentUsers(ownerId: String): Flow<List<UserProfileItemUi>> =
        usersDatabase.userProfileInteractions()
            .observeRecentProfilesByOwnerId(ownerId = ownerId, limit = MAX_RECENT_PROFILES)
            .distinctUntilChanged()
            .map { recentProfiles ->
                val profileIds = recentProfiles.map { it.profileId }

                val profiles = profileRepository.findProfileData(profileIds).associateBy { it.profileId }
                val statsMap = profileRepository.findProfileStats(profileIds).associateBy { it.profileId }

                profileIds.mapNotNull { profileId ->
                    profiles[profileId]?.let { profile ->
                        val stats = statsMap[profileId]
                        UserProfileSearchItem(
                            metadata = profile,
                            followersCount = stats?.followers,
                        ).mapAsUserProfileUi()
                    }
                }
            }

    private companion object {
        const val MAX_RECENT_PROFILES = 5
    }

    class FollowListNotFound : Exception()
}
