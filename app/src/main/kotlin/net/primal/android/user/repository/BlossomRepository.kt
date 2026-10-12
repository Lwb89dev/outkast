package net.primal.android.user.repository

import javax.inject.Inject
import kotlinx.coroutines.withContext
import net.primal.android.user.accounts.UserAccountsStore
import net.primal.core.networking.tor.NetworkMode
import net.primal.core.networking.tor.RouteController
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.ensureHttpOrHttps
import net.primal.core.utils.map
import net.primal.core.utils.runCatching
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.asServerTag
import net.primal.domain.publisher.PrimalPublisher

class BlossomRepository @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val userAccountsStore: UserAccountsStore,
    private val primalPublisher: PrimalPublisher,
    private val routeController: RouteController,
) {

    private companion object {
        private val DEFAULT_BLOSSOM_LIST = listOf(
            "https://blossom.band",
            "https://cdn.satellite.earth",
        )

        /**
         * Public servers that took an anonymous upload when tried (2026-10-12), over Tor as well, and
         * that accepted a 100 MB video where the defaults above cannot: blossom.band refuses anything
         * over 20 MiB and answers 403 "Access denied" to a Tor exit address, and cdn.satellite.earth
         * wants an upload permission nobody has by default. They are not run by Primal.
         *
         * With Tor as the only route they go right after the user's own servers, since a list made
         * of the defaults alone means every upload fails. Otherwise they are only the last resort
         * for a file no other server would take (see [provideFallbackServerList]).
         */
        private val TOR_FRIENDLY_BLOSSOM_LIST = listOf(
            "https://cdn.nostrcheck.me",
            "https://cdn.hzrd149.com",
        )
    }

    /**
     * The list the media settings show: what the user published, then the public defaults.
     * Whatever the network mode, so it does not change under the user's feet when they switch.
     */
    suspend fun ensureBlossomServerList(userId: String): List<String> =
        // Keep user-published servers first, but always retain public fallbacks. A stale
        // Blossom list must never make uploads fail permanently after a server disappears.
        (userPublishedServers(userId) + DEFAULT_BLOSSOM_LIST).distinct()

    /**
     * The servers an upload tries, in order, until one accepts.
     *
     * The same as [ensureBlossomServerList], except that while every connection goes through Tor
     * (see [TOR_FRIENDLY_BLOSSOM_LIST]) servers known to accept Tor traffic come right after the
     * user's own, ahead of the defaults, so an upload does not first spend several seconds on
     * servers that are certain to refuse it. A user's own servers always stay first: they chose them.
     */
    suspend fun provideUploadServerList(userId: String): List<String> {
        val viaTor = routeController.config.mode == NetworkMode.TOR
        val torFriendly = if (viaTor) TOR_FRIENDLY_BLOSSOM_LIST else emptyList()
        return (userPublishedServers(userId) + torFriendly + DEFAULT_BLOSSOM_LIST).distinct()
    }

    /** Where an upload goes when every server of [provideUploadServerList] has refused it. */
    fun provideFallbackServerList(): List<String> = TOR_FRIENDLY_BLOSSOM_LIST

    private suspend fun userPublishedServers(userId: String): List<String> {
        val userAccount = userAccountsStore.findByIdOrNull(userId)
        return userAccount?.blossomServers.orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.ensureHttpOrHttps().trimEnd('/') }
            .distinct()
    }

    suspend fun publishBlossomServerList(userId: String, servers: List<String>) {
        withContext(dispatcherProvider.io()) {
            primalPublisher.signPublishImportNostrEvent(
                unsignedNostrEvent = NostrUnsignedEvent(
                    pubKey = userId,
                    kind = NostrEventKind.BlossomServerList.value,
                    tags = servers.map { it.ensureHttpOrHttps().asServerTag() },
                    content = "",
                ),
            )
            persistBlossomServersLocally(userId = userId, blossomServers = servers.map { it.ensureHttpOrHttps() })
        }
    }

    private suspend fun persistBlossomServersLocally(userId: String, blossomServers: List<String>) {
        userAccountsStore.getAndUpdateAccount(userId) {
            copy(blossomServers = blossomServers)
        }
    }
}
