package net.primal.data.remote.api.gifs

import net.primal.core.networking.factory.HttpClientFactory

/**
 * Builds the GIF search client on the app's shared HTTP engine, which means two things for free:
 * requests follow the network mode (direct or Tor), and they carry the `LibreNostr/<version>`
 * User-Agent nostr.build identifies registered native clients by.
 */
object GifSearchApiFactory {
    private val defaultHttpClient = HttpClientFactory.createHttpClientWithDefaultConfig()

    /** @param nostrBuildApiKey the key nostr.build issued to this app, if the build has one. */
    fun create(nostrBuildApiKey: String? = null): GifSearchApi =
        FallbackGifSearchApi(
            nostrBuild = NostrBuildGifApi(httpClient = defaultHttpClient, apiKey = nostrBuildApiKey),
            gifverse = GifverseGifApi(httpClient = defaultHttpClient),
        )
}
