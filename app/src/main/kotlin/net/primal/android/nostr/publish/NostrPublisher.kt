package net.primal.android.nostr.publish

import io.github.aakira.napier.Napier
import javax.inject.Inject
import net.primal.android.networking.relays.RelaysSocketManager
import net.primal.android.networking.relays.errors.NostrPublishException
import net.primal.android.nostr.notary.NostrNotary
import net.primal.android.pow.PowSettingsStore
import net.primal.android.pow.ProofOfWorkService
import net.primal.android.user.domain.Relay
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import net.primal.domain.global.CachingImportRepository
import net.primal.domain.nostr.ContentMetadata
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.SignatureException
import net.primal.domain.nostr.cryptography.utils.unwrapOrThrow
import net.primal.domain.nostr.pow.provenWork
import net.primal.domain.publisher.PrimalPublishResult
import net.primal.domain.publisher.PrimalPublisher

class NostrPublisher @Inject constructor(
    private val relaysSocketManager: RelaysSocketManager,
    private val nostrNotary: NostrNotary,
    private val cachingImportRepository: CachingImportRepository,
    private val powSettingsStore: PowSettingsStore,
    private val proofOfWork: ProofOfWorkService,
) : PrimalPublisher {

    private suspend fun persistEventLocally(event: NostrEvent) {
        runCatching {
            cachingImportRepository.cacheNostrEvents(events = listOf(event))
        }.onFailure { error ->
            Napier.w(throwable = error) { "Failed to persist event ${event.id} locally." }
        }
    }

    @Throws(NostrPublishException::class)
    private suspend fun publishAndImportEvent(signedNostrEvent: NostrEvent, outboxRelays: List<String> = emptyList()) {
        relaysSocketManager.publishEvent(signedNostrEvent)
        persistEventLocally(signedNostrEvent)
        if (outboxRelays.isNotEmpty()) {
            runCatching {
                relaysSocketManager.publishEvent(
                    nostrEvent = signedNostrEvent,
                    relays = outboxRelays.map { Relay(url = it, read = false, write = true) },
                )
            }.onFailure { error ->
                Napier.w(throwable = error) { "Failed to publish to outbox relays." }
            }
        }
    }

    @Throws(NostrPublishException::class, SignatureException::class)
    override suspend fun signPublishImportNostrEvent(
        unsignedNostrEvent: NostrUnsignedEvent,
        outboxRelays: List<String>,
    ): PrimalPublishResult {
        val signedNostrEvent = signWithProofOfWork(unsignedNostrEvent)
        publishAndImportEvent(signedNostrEvent = signedNostrEvent, outboxRelays = outboxRelays)
        return PrimalPublishResult(
            nostrEvent = signedNostrEvent,
        )
    }

    /**
     * Mines the event first when the account asks for it for this kind (NIP-13), because the nonce
     * is part of what gets signed. Profile, follow list and relay list have their own methods
     * below and are never mined.
     */
    private suspend fun signWithProofOfWork(unsigned: NostrUnsignedEvent): NostrEvent {
        val difficulty = powSettingsStore.get(unsigned.pubKey).difficultyFor(unsigned.kind)
        val prepared = proofOfWork.mine(unsigned, difficulty)
        val signed = nostrNotary.signNostrEvent(unsignedNostrEvent = prepared).unwrapOrThrow()
        // An external signer that rewrites created_at or the tags changes the id, and the work is
        // gone. The event is already signed and wanted, so it goes out, but not silently.
        if (difficulty > 0 && signed.provenWork() < difficulty) {
            Napier.w { "The signer returned kind ${signed.kind} without its proof of work." }
            proofOfWork.reportSignerDroppedWork()
        }
        return signed
    }

    @Throws(NostrPublishException::class, SignatureException::class)
    suspend fun publishUserProfile(userId: String, contentMetadata: ContentMetadata): NostrEvent {
        val signedNostrEvent = nostrNotary
            .signMetadataNostrEvent(userId = userId, metadata = contentMetadata)
            .unwrapOrThrow()

        relaysSocketManager.publishEvent(nostrEvent = signedNostrEvent)
        persistEventLocally(signedNostrEvent)
        return signedNostrEvent
    }

    @Throws(NostrPublishException::class, SignatureException::class)
    suspend fun publishUserFollowList(
        userId: String,
        contacts: Set<String>,
        content: String,
        interests: List<String> = emptyList(),
    ): NostrEvent {
        val signedNostrEvent = nostrNotary.signFollowListNostrEvent(
            userId = userId,
            contacts = contacts,
            content = content,
            interests = interests,
        ).unwrapOrThrow()

        publishAndImportEvent(signedNostrEvent)
        return signedNostrEvent
    }

    @Throws(NostrPublishException::class, SignatureException::class)
    suspend fun publishRelayList(userId: String, relays: List<Relay>): NostrEvent {
        val signedNostrEvent = nostrNotary.signRelayListMetadata(userId = userId, relays = relays).unwrapOrThrow()
        relaysSocketManager.publishEvent(nostrEvent = signedNostrEvent, relays = relays)
        persistEventLocally(signedNostrEvent)
        return signedNostrEvent
    }
}
