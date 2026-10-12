package net.primal.core.networking.blossom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.primal.core.utils.coroutines.DispatcherProvider
import net.primal.domain.nostr.NostrEvent
import net.primal.domain.nostr.NostrUnsignedEvent
import okio.Buffer
import okio.BufferedSource

/**
 * What an upload does when servers say no. Behind Tor the first servers of the default list
 * answered 403 and 401, and the app reported a bare "check your connection".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrimalUploadServiceTest {

    @Test
    fun `a refused server is skipped and the next one takes the file`() =
        runTest {
            val refusing = refusingServer("https://blossom.band", "Access denied", 403)
            val accepting = FakeServer("https://cdn.nostrcheck.me")

            val result = upload(refusing, accepting)

            assertIs<UploadResult.Success>(result)
            assertEquals(1, accepting.uploads)
            assertEquals(1, refusing.uploads)
        }

    @Test
    fun `the file is mirrored to every other server, never back to the one that has it`() =
        runTest {
            val refusing = refusingServer("https://blossom.band", "Access denied", 403)
            val accepting = FakeServer("https://cdn.nostrcheck.me")
            val untouched = FakeServer("https://cdn.hzrd149.com")

            upload(refusing, accepting, untouched)

            assertEquals(1, refusing.mirrors, "a server that refused is still offered a copy")
            assertEquals(0, accepting.mirrors, "mirroring the file onto the server it was uploaded to is a no-op")
            assertEquals(1, untouched.mirrors)
        }

    @Test
    fun `when nobody accepts the error says what each server answered`() =
        runTest {
            val band = refusingServer("https://blossom.band", "Access denied", 403)
            val satellite = refusingServer(
                url = "https://cdn.satellite.earth",
                reason = "Permission denied: blossom.upload required",
                status = 401,
            )

            val result = upload(band, satellite)

            assertIs<UploadResult.Failed>(result)
            val message = result.error.message.orEmpty()
            assertTrue("blossom.band: Access denied (403)" in message, message)
            assertTrue("cdn.satellite.earth: Permission denied: blossom.upload required (401)" in message, message)
        }

    @Test
    fun `a missing legacy media endpoint does not hide why the upload endpoint refused`() =
        runTest {
            val server = FakeServer(
                "https://cdn.satellite.earth",
                uploadError = refusal("Permission denied: blossom.upload required", 401),
                mediaError = refusal("Unknown", 404),
            )

            val result = upload(server)

            assertIs<UploadResult.Failed>(result)
            assertTrue("Permission denied: blossom.upload required" in result.error.message.orEmpty())
        }

    @Test
    fun `a real answer from the media endpoint is kept over the upload endpoint's`() =
        runTest {
            val server = FakeServer(
                "https://legacy.example.com",
                uploadError = refusal("Unknown", 404),
                mediaError = refusal("File too large", 413),
            )

            val result = upload(server)

            assertIs<UploadResult.Failed>(result)
            assertTrue("File too large (413)" in result.error.message.orEmpty())
        }

    @Test
    fun `a legacy server that only has the media endpoint still works`() =
        runTest {
            val server = FakeServer("https://legacy.example.com", uploadError = refusal("Unknown", 404))

            val result = upload(server)

            assertIs<UploadResult.Success>(result)
            assertEquals(1, server.mediaUploads)
        }

    @Test
    fun `a large file is not sent to a server whose pre-flight refuses it`() =
        runTest {
            val small = FakeServer("https://blossom.band")
                .also { it.headError = refusal("File size exceeds the maximum allowed size of 20 MiB", 413) }
            val big = FakeServer("https://cdn.hzrd149.com")

            val result = upload(small, big, file = VIDEO_BYTES)

            assertIs<UploadResult.Success>(result)
            assertEquals(0, small.uploads, "the body must not go out to a server that said it would refuse it")
            assertEquals(0, small.mediaUploads, "and the legacy endpoint is not a way around a refusal")
            assertEquals(1, big.uploads)
        }

    @Test
    fun `the pre-flight is skipped for a small file`() =
        runTest {
            val server = FakeServer("https://blossom.band")

            upload(server)

            assertEquals(0, server.heads, "a refused small upload wastes nothing worth a round trip")
        }

    @Test
    fun `a server that does not know the pre-flight is simply tried`() =
        runTest {
            val server = FakeServer("https://legacy.example.com").also { it.headError = refusal("Not found", 404) }

            val result = upload(server, file = VIDEO_BYTES)

            assertIs<UploadResult.Success>(result)
            assertEquals(1, server.uploads)
        }

    @Test
    fun `fallback servers take the file when every configured server refuses it`() =
        runTest {
            val configured = refusingServer("https://blossom.band", "Access denied", 403)
            val fallback = FakeServer("https://cdn.hzrd149.com")

            val result = upload(configured, fallbacks = listOf(fallback))

            assertIs<UploadResult.Success>(result)
            assertEquals(1, fallback.uploads)
        }

    @Test
    fun `a fallback server is never a mirror`() =
        runTest {
            val configured = FakeServer("https://blossom.band")
            val fallback = FakeServer("https://cdn.hzrd149.com")

            upload(configured, fallbacks = listOf(fallback))

            assertEquals(0, fallback.uploads, "a fallback is only tried when nothing else took the file")
            assertEquals(0, fallback.mirrors, "and nobody asked to have the file copied there")
        }

    @Test
    fun `when everything refuses the error names the large-file refusal of each server`() =
        runTest {
            val band = FakeServer("https://blossom.band")
                .also { it.headError = refusal("File size exceeds the maximum allowed size of 20 MiB", 413) }

            val result = upload(band, file = VIDEO_BYTES)

            assertIs<UploadResult.Failed>(result)
            val message = result.error.message.orEmpty()
            assertTrue("blossom.band: File size exceeds the maximum allowed size of 20 MiB (413)" in message, message)
        }

    // ------------------------------------------------------------------------------- harness

    private fun refusal(reason: String, status: Int) =
        BlossomUploadException(message = "$reason ($status)", statusCode = status)

    /** A server that turns the file away on both endpoints, the way blossom.band answers a Tor exit. */
    private fun refusingServer(url: String, reason: String, status: Int) =
        FakeServer(url, uploadError = refusal(reason, status), mediaError = refusal(reason, status))

    private suspend fun kotlinx.coroutines.test.TestScope.upload(
        vararg servers: FakeServer,
        fallbacks: List<FakeServer> = emptyList(),
        file: ByteArray = PNG_BYTES,
    ): UploadResult {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val service = PrimalUploadService(
            dispatchers = object : DispatcherProvider {
                override fun io(): CoroutineDispatcher = dispatcher
                override fun main(): CoroutineDispatcher = dispatcher
            },
            blossomResolver = object : BlossomServerListProvider {
                override suspend fun provideBlossomServerList(userId: String) = servers.map { it.serverUrl }
                override suspend fun provideFallbackServerList(userId: String) = fallbacks.map { it.serverUrl }
            },
            createBlossomApi = { url -> (servers.toList() + fallbacks).first { it.serverUrl == url } },
        )
        return service.upload(
            userId = USER,
            openBufferedSource = { Buffer().apply { write(file) } },
            onSignRequested = { unsigned -> signed(unsigned) },
        )
    }

    private fun signed(unsigned: NostrUnsignedEvent) =
        NostrEvent(
            id = "id",
            pubKey = unsigned.pubKey,
            createdAt = 1L,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = "sig",
        )

    private class FakeServer(
        override val serverUrl: String,
        private val uploadError: BlossomUploadException? = null,
        private val mediaError: BlossomUploadException? = null,
    ) : BlossomApi {
        var uploads = 0
        var mediaUploads = 0
        var mirrors = 0
        var heads = 0

        /** What the server answers to the pre-flight; null means it would take the file. */
        var headError: BlossomUploadException? = null

        private fun descriptor() =
            BlobDescriptor(
                url = "$serverUrl/blob.png",
                sha256 = "hash",
                sizeInBytes = PNG_BYTES.size.toLong(),
                uploaded = 1,
            )

        override suspend fun headUpload(authorization: String, fileMetadata: FileMetadata) {
            heads++
            headError?.let {
                throw UploadRequirementException(message = it.message, statusCode = it.statusCode)
            }
        }

        override suspend fun putUpload(
            authorization: String,
            fileMetadata: FileMetadata,
            bufferedSource: BufferedSource,
            onProgress: ((uploadedBytes: Int, totalBytes: Int) -> Unit)?,
        ): BlobDescriptor {
            uploads++
            uploadError?.let { throw it }
            return descriptor()
        }

        override suspend fun headMedia(authorization: String, fileMetadata: FileMetadata) = Unit

        override suspend fun putMedia(
            authorization: String,
            fileMetadata: FileMetadata,
            bufferedSource: BufferedSource,
            onProgress: ((uploadedBytes: Int, totalBytes: Int) -> Unit)?,
        ): BlobDescriptor {
            mediaUploads++
            mediaError?.let { throw it }
            return descriptor()
        }

        override suspend fun putMirror(authorization: String, fileUrl: String): BlobDescriptor {
            mirrors++
            return descriptor()
        }
    }

    private companion object {
        const val USER = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"

        /**
         * The PNG signature is all the mime sniffing needs, padded because the service sniffs the
         * first 512 bytes and a shorter file is an error there. Nothing here decodes the image.
         */
        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(600)

        /** Over the pre-flight threshold (8 MiB); the content is not looked at. */
        val VIDEO_BYTES = ByteArray(9 * 1024 * 1024)
    }
}
