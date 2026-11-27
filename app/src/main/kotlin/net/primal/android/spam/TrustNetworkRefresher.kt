package net.primal.android.spam

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.primal.android.pow.PowSettingsStore
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.wot.WebOfTrustRepository

/** Computes the web of trust outside any screen's life: it takes a while and must not be cut off. */
@Singleton
class TrustNetworkRefresher @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val activeAccountStore: ActiveAccountStore,
    private val settingsStore: PowSettingsStore,
    private val webOfTrustRepository: WebOfTrustRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io())
    private val running = ConcurrentHashMap.newKeySet<String>()

    fun refresh() {
        scope.launch { compute() }
    }

    /** For an account that hides strangers: follows change, so a week-old network is redone. */
    fun refreshIfStale(userId: String) {
        scope.launch {
            val settings = settingsStore.get(userId)
            if (!settings.networkOnly && !settings.filter) return@launch
            val computedAt = webOfTrustRepository.observeComputedAt(userId).first()
            val age = Clock.System.now().epochSeconds - computedAt
            if (age > MAX_AGE.inWholeSeconds) compute()
        }
    }

    private suspend fun compute() {
        val account = activeAccountStore.activeUserAccount()
        if (!running.add(account.pubkey)) return
        try {
            webOfTrustRepository.refreshNetwork(ownerId = account.pubkey, firstDegreeFollows = account.following)
        } finally {
            running.remove(account.pubkey)
        }
    }

    private companion object {
        val MAX_AGE = 7.days
    }
}
