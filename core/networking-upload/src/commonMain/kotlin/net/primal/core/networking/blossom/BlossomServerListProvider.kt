package net.primal.core.networking.blossom

interface BlossomServerListProvider {

    /** The servers an upload goes to, in order, and that the file is then mirrored across. */
    suspend fun provideBlossomServerList(userId: String): List<String>

    /**
     * Servers tried only when none of the above took the file, in order, and never mirrored to.
     *
     * The ones above can all be unable to take a given file (a server's size limit is often small,
     * and some want a paid account), and an upload that has nowhere left to go should still land
     * somewhere rather than fail.
     */
    suspend fun provideFallbackServerList(userId: String): List<String> = emptyList()
}
