package net.primal.android.spam

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import net.primal.android.pow.PowSettingsStore
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.wot.WebOfTrustRepository

@Singleton
class StrangerFilterProvider @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val activeAccountStore: ActiveAccountStore,
    private val settingsStore: PowSettingsStore,
    private val webOfTrustRepository: WebOfTrustRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io())

    /** One per account, shared: a wide network is large, and several screens ask at once. */
    private val filters = ConcurrentHashMap<String, Flow<StrangerFilter?>>()

    /** The filter in force for [userId], or null while it is off: then nothing is looked at. */
    fun observe(userId: String): Flow<StrangerFilter?> =
        filters.getOrPut(userId) {
            build(userId).shareIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
                replay = 1,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun build(userId: String): Flow<StrangerFilter?> =
        settingsStore.observe(userId)
            .map { settings ->
                Rule(
                    networkOnly = settings.networkOnly,
                    minimumBits = settings.filterBits.takeIf { settings.filter },
                    minFollowers = settings.networkMinFollowers,
                )
            }
            .distinctUntilChanged()
            .flatMapLatest { rule ->
                if (!rule.networkOnly && rule.minimumBits == null) flowOf(null) else observe(userId, rule)
            }

    private fun observe(userId: String, rule: Rule): Flow<StrangerFilter?> =
        combine(
            activeAccountStore.activeUserAccount.map { it.following }.distinctUntilChanged(),
            webOfTrustRepository.observeQualifiedPubkeys(userId, rule.minFollowers),
            webOfTrustRepository.observeComputedAt(userId),
        ) { following, network, computedAt ->
            // Before a network exists only direct follows would pass, so too much would vanish.
            val networkOnly = rule.networkOnly && computedAt > 0
            if (!networkOnly && rule.minimumBits == null) {
                null
            } else {
                StrangerFilter(
                    following = following + userId,
                    network = network,
                    minimumBits = rule.minimumBits.takeUnless { networkOnly },
                )
            }
        }.distinctUntilChanged()

    private data class Rule(val networkOnly: Boolean, val minimumBits: Int?, val minFollowers: Int)
}
