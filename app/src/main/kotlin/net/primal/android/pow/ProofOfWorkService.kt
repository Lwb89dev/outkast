package net.primal.android.pow

import io.github.aakira.napier.Napier
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.measureTimedValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.cryptography.SigningRejectedException
import net.primal.domain.nostr.pow.PartsHasher
import net.primal.domain.nostr.pow.PowMiner

/** One event being mined right now, so the screen can say so and offer to stop it. */
class MiningTask(val difficulty: Int, val cancel: () -> Unit)

/** Why a publish stopped when the user called off the mining. */
class ProofOfWorkCancelled(cause: Throwable) : Exception("Proof of work cancelled by the user.", cause)

@Singleton
class ProofOfWorkService @Inject constructor() {

    private val miner = PowMiner(newHasher = ::platformHasher)

    private val _tasks = MutableStateFlow<List<MiningTask>>(emptyList())
    val tasks = _tasks.asStateFlow()

    private val _signerDroppedWork = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** An event was signed without the proof of work it was mined with: the user has to be told. */
    val signerDroppedWork = _signerDroppedWork.asSharedFlow()

    fun reportSignerDroppedWork() {
        _signerDroppedWork.tryEmit(Unit)
    }

    /**
     * [event] mined to [difficulty], or untouched when that is 0. Runs on a computation thread.
     *
     * A user cancelling the mining surfaces as a [SigningRejectedException] caused by
     * [ProofOfWorkCancelled]: every publishing path already handles a refused signature, keeping
     * what was being sent, which is exactly what calling it off has to do.
     */
    suspend fun mine(event: NostrUnsignedEvent, difficulty: Int): NostrUnsignedEvent {
        if (difficulty <= 0) return event
        return coroutineScope {
            val mining = async(Dispatchers.Default) { measureTimedValue { miner.mine(event, difficulty) } }
            val task = MiningTask(difficulty = difficulty, cancel = { mining.cancel() })
            _tasks.update { it + task }
            try {
                val (mined, duration) = mining.await()
                Napier.d { "Proof of work: kind ${event.kind}, $difficulty bits in $duration" }
                mined
            } catch (error: CancellationException) {
                // Ours alone cancelled means the user asked; otherwise the whole publish was.
                if (!currentCoroutineContext().isActive) throw error
                throw SigningRejectedException(message = error.message, cause = ProofOfWorkCancelled(error))
            } finally {
                _tasks.update { it - task }
            }
        }
    }

    /** The platform's native SHA-256, several times faster than the shared pure Kotlin one. */
    private fun platformHasher(): PartsHasher {
        val digest = MessageDigest.getInstance("SHA-256")
        return PartsHasher { prefix, nonce, suffix ->
            digest.update(prefix)
            digest.update(nonce)
            digest.digest(suffix)
        }
    }
}
