package net.primal.core.networking.blossom

import io.github.aakira.napier.Napier
import io.github.anvell.filetype.FileType
import io.ktor.http.Url
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.core.utils.onFailure
import net.primal.core.utils.runCatching
import net.primal.core.utils.serialization.encodeToJsonString
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrEventKind
import net.primal.domain.nostr.NostrUnsignedEvent
import net.primal.domain.nostr.asExpirationTag
import net.primal.domain.nostr.asHashtagTag
import net.primal.domain.nostr.asSha256Tag
import net.primal.domain.nostr.cryptography.NostrEventSignatureHandler
import net.primal.domain.nostr.cryptography.utils.unwrapOrThrow
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import okio.HashingSource
import okio.blackholeSink
import okio.buffer
import okio.use

internal class PrimalUploadService(
    private val dispatchers: DispatcherProvider,
    private val blossomResolver: BlossomServerListProvider,
    private val signatureHandler: NostrEventSignatureHandler? = null,
    /** How a server URL becomes a client. Only a test replaces it. */
    private val createBlossomApi: (String) -> BlossomApi = { BlossomApiFactory.create(baseBlossomUrl = it) },
) {

    private val mirroringScope = CoroutineScope(SupervisorJob() + dispatchers.io())

    suspend fun upload(
        userId: String,
        openBufferedSource: () -> BufferedSource,
        onSignRequested: (suspend (NostrUnsignedEvent) -> NostrEvent)? = null,
        onProgress: ((uploadedBytes: Int, totalBytes: Int) -> Unit)? = null,
    ): UploadResult {
        val result = withContext(dispatchers.io()) {
            runCatching {
                val servers = resolveBlossomApisOrThrow(userId = userId)

                val fileMetadata = openBufferedSource().use { it.getMetadata() }

                val uploadAuthorizationHeader = signAuthorizationOrThrow(
                    userId = userId,
                    fileHash = fileMetadata.sha256,
                    onSignRequested = onSignRequested,
                )

                val accepted = uploadToFirstAcceptingServer(
                    blossomApis = servers.primary + servers.fallbacks,
                    authorization = uploadAuthorizationHeader,
                    fileMetadata = fileMetadata,
                    openBufferedSource = openBufferedSource,
                    onProgress = onProgress,
                )
                val uploadedDescriptor = accepted.descriptor

                val mirrorAuthorizationHeader = signAuthorizationOrThrow(
                    userId = userId,
                    fileHash = uploadedDescriptor.sha256,
                    humanMessage = "Mirror File",
                    onSignRequested = onSignRequested,
                )

                // Every configured server except the one that already has the file. (The first
                // server is not always that one: when it refused, a later server took the upload.)
                // Fallback servers are only ever a last resort for the upload, never a mirror.
                servers.primary.filter { it !== accepted.server }.forEach { blossomApi ->
                    mirroringScope.launch {
                        runCatching {
                            blossomApi.putMirror(
                                authorization = mirrorAuthorizationHeader,
                                fileUrl = uploadedDescriptor.url,
                            )
                        }.onFailure { error ->
                            Napier.w(error) { "Blossom mirror failed for ${uploadedDescriptor.url}" }
                        }
                    }
                }

                return@runCatching uploadedDescriptor
            }
        }

        return result.getOrNull()?.let { descriptor ->
            UploadResult.Success(
                remoteUrl = descriptor.url,
                originalFileSize = descriptor.sizeInBytes,
                originalHash = descriptor.sha256,
                nip94 = descriptor.nip94?.toNip94Metadata(),
            )
        } ?: run {
            val error = result.exceptionOrNull() ?: IllegalStateException("Upload failed.")
            val uploadError = error as? BlossomException ?: BlossomException(cause = error)
            UploadResult.Failed(error = uploadError, message = uploadError.message)
        }
    }

    private class AcceptedUpload(val server: BlossomApi, val descriptor: BlobDescriptor)

    /**
     * Tries the servers in order and stops at the first that takes the file.
     *
     * When none does, the error says what each of them answered. That is the one thing that tells a
     * user "this server refuses connections from Tor" from "the connection is down", and a single
     * generic message used to hide it.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun uploadToFirstAcceptingServer(
        blossomApis: List<BlossomApi>,
        authorization: String,
        fileMetadata: FileMetadata,
        openBufferedSource: () -> BufferedSource,
        onProgress: ((uploadedBytes: Int, totalBytes: Int) -> Unit)?,
    ): AcceptedUpload {
        val refusals = mutableListOf<String>()
        var lastError: Throwable? = null
        for (blossomApi in blossomApis) {
            try {
                val descriptor = uploadToServer(
                    blossomApi = blossomApi,
                    authorization = authorization,
                    fileMetadata = fileMetadata,
                    openBufferedSource = openBufferedSource,
                    onProgress = onProgress,
                )
                return AcceptedUpload(server = blossomApi, descriptor = descriptor)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                refusals += "${blossomApi.serverUrl.hostOrSelf()}: ${error.describeForUser()}"
                Napier.w(error) { "Blossom upload failed; trying the next configured server." }
            }
        }
        throw BlossomUploadException(
            message = "No media server accepted the upload. ${refusals.joinToString(separator = "; ")}",
            cause = lastError,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun uploadToServer(
        blossomApi: BlossomApi,
        authorization: String,
        fileMetadata: FileMetadata,
        openBufferedSource: () -> BufferedSource,
        onProgress: ((uploadedBytes: Int, totalBytes: Int) -> Unit)?,
    ): BlobDescriptor {
        if (fileMetadata.sizeInBytes >= PREFLIGHT_MIN_BYTES) {
            blossomApi.failIfPreflightRefuses(authorization = authorization, fileMetadata = fileMetadata)
        }
        return try {
            blossomApi.putUpload(
                authorization = authorization,
                fileMetadata = fileMetadata,
                bufferedSource = openBufferedSource(),
                onProgress = onProgress,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Napier.w(error) { "Blossom /upload failed; trying legacy /media endpoint." }
            putMediaAfterFailedUpload(
                blossomApi = blossomApi,
                uploadError = error,
                authorization = authorization,
                fileMetadata = fileMetadata,
                openBufferedSource = openBufferedSource,
                onProgress = onProgress,
            )
        }
    }

    /**
     * Asks a server whether it would take a large file before the file is sent (BUD-06).
     *
     * A server's size limit is only learned from its answer, and answered after the whole body has
     * gone out unless asked first: a 150 MB video offered to a server that takes 20 would cost the
     * upload before the refusal, and then again for the legacy `/media` retry. A server that does
     * not know the pre-flight (404, 405) is simply tried.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun BlossomApi.failIfPreflightRefuses(authorization: String, fileMetadata: FileMetadata) {
        try {
            headUpload(authorization = authorization, fileMetadata = fileMetadata)
        } catch (error: CancellationException) {
            throw error
        } catch (error: UploadRequirementException) {
            if (error.isMissingEndpoint()) return
            Napier.w(error) { "Blossom server refused the pre-flight for ${fileMetadata.sizeInBytes} bytes." }
            throw BlossomUploadException(message = error.message, cause = error, statusCode = error.statusCode)
        }
    }

    /**
     * The legacy `/media` endpoint, tried after `/upload` failed.
     *
     * If that fails too, the error worth reporting is usually the first one: a server that has
     * `/upload` and refuses the file says why there, while its missing `/media` only answers
     * "not found". The second error wins only when it is a real answer.
     */
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    private suspend fun putMediaAfterFailedUpload(
        blossomApi: BlossomApi,
        uploadError: Exception,
        authorization: String,
        fileMetadata: FileMetadata,
        openBufferedSource: () -> BufferedSource,
        onProgress: ((uploadedBytes: Int, totalBytes: Int) -> Unit)?,
    ): BlobDescriptor {
        return try {
            blossomApi.putMedia(
                authorization = authorization,
                fileMetadata = fileMetadata,
                bufferedSource = openBufferedSource(),
                onProgress = onProgress,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (mediaError: Exception) {
            throw if (mediaError.isMissingEndpoint()) uploadError else mediaError
        }
    }

    private fun Throwable.isMissingEndpoint(): Boolean {
        val status = (this as? BlossomUploadException)?.statusCode ?: (this as? UploadRequirementException)?.statusCode
        return status == HTTP_NOT_FOUND || status == HTTP_METHOD_NOT_ALLOWED
    }

    /** What a server said, in a few words, or what went wrong before it could say anything. */
    private fun Throwable.describeForUser(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "unknown error"

    private fun String.hostOrSelf(): String = runCatching { Url(this).host }.getOrNull()?.ifBlank { null } ?: this

    private class ResolvedServers(val primary: List<BlossomApi>, val fallbacks: List<BlossomApi>)

    private suspend fun resolveBlossomApisOrThrow(userId: String): ResolvedServers {
        val primary = blossomResolver.provideBlossomServerList(userId).asSecureApis().take(MAX_BLOSSOM_SERVERS)
        if (primary.isEmpty()) {
            throw BlossomUploadException(cause = IllegalStateException("Invalid blossom server list."))
        }
        val fallbacks = blossomResolver.provideFallbackServerList(userId).asSecureApis()
            .filter { fallback -> primary.none { it.serverUrl == fallback.serverUrl } }
            .take(MAX_FALLBACK_SERVERS)
        return ResolvedServers(primary = primary, fallbacks = fallbacks)
    }

    private fun List<String>.asSecureApis(): List<BlossomApi> =
        asSequence()
            .map { it.trimTrailingSlashes() }
            .filter { it.isSecureBlossomUrl() }
            .distinct()
            .mapNotNull { runCatching { createBlossomApi(it) }.getOrNull() }
            .toList()

    private suspend fun signAuthorizationOrThrow(
        userId: String,
        fileHash: String,
        humanMessage: String? = null,
        onSignRequested: (suspend (NostrUnsignedEvent) -> NostrEvent)? = null,
    ): String {
        val unsignedAuthorizationEvent = NostrUnsignedEvent(
            kind = NostrEventKind.BlossomUploadBlob.value,
            pubKey = userId,
            content = humanMessage ?: "Upload File",
            tags = listOf(
                "upload".asHashtagTag(),
                fileHash.asSha256Tag(),
                expirationTimestamp().asExpirationTag(),
            ),
        )

        val signedAuthorizationEvent = onSignRequested?.invoke(unsignedAuthorizationEvent)
            ?: signatureHandler?.signNostrEvent(unsignedAuthorizationEvent)?.unwrapOrThrow()
            ?: error("Missing signature handler.")
        return signedAuthorizationEvent.buildAuthorizationHeader()
    }

    private fun NostrEvent.buildAuthorizationHeader(): String {
        val jsonPayload = this.encodeToJsonString()
        val base64Encoded = jsonPayload.encodeUtf8().base64()
        val authorizationHeader = "Nostr $base64Encoded"
        return authorizationHeader
    }

    // Nostr timestamps (including Blossom's expiration tag) are Unix seconds,
    // not milliseconds. Sending milliseconds makes every authorization appear
    // valid for tens of thousands of years and is rejected by compliant servers.
    private fun expirationTimestamp() = Clock.System.now().plus(1.hours).epochSeconds

    private fun BufferedSource.getMetadata(): FileMetadata {
        val hashingSource = HashingSource.sha256(this)
        val bufferedHashingSource = hashingSource.buffer()
        val blackhole = blackholeSink().buffer()
        val tempBuffer = Buffer()

        val mimeType = this.mimeType()
        var totalBytes = 0L
        bufferedHashingSource.use {
            while (!it.exhausted()) {
                val bytesRead = it.read(tempBuffer, DEFAULT_BUFFER_SIZE)
                if (bytesRead == -1L) break
                totalBytes += bytesRead
                blackhole.write(tempBuffer, bytesRead)
            }
        }

        val sha256Bytes = hashingSource.hash.toByteArray()
        val hex = sha256Bytes.joinToString("") {
            it.toInt().and(other = 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
        }

        return FileMetadata(
            sizeInBytes = totalBytes,
            sha256 = hex,
            mimeType = mimeType,
        )
    }

    /**
     * Returns the RFC-6838 media-type (e.g. "image/png") or `null`
     * without consuming the source.
     */
    fun BufferedSource.mimeType(): String? {
        val head = peek().readByteArray(FILE_HEAD_PEEK_BYTES)
        return FileType.detect(head)?.toString()
    }

    private companion object {
        private const val DEFAULT_BUFFER_SIZE = 8 * 1024L
        private const val FILE_HEAD_PEEK_BYTES = 512L
        private const val MAX_BLOSSOM_SERVERS = 6
        private const val MAX_FALLBACK_SERVERS = 3

        /** Below this the pre-flight costs more time than a refused upload would waste. */
        private const val PREFLIGHT_MIN_BYTES = 8L * 1024 * 1024
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_METHOD_NOT_ALLOWED = 405
    }

    private fun String.trimTrailingSlashes(): String = this.trimEnd('/')
}
