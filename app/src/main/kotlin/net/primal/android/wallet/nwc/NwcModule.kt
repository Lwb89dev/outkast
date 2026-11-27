package net.primal.android.wallet.nwc

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Singleton
import net.primal.android.security.Encryption
import net.primal.android.security.readDecrypted
import net.primal.android.security.writeEncrypted
import net.primal.core.utils.serialization.CommonJson

private class NwcConnectionsSerialization(private val encryption: Encryption) : Serializer<Map<String, String>> {

    override val defaultValue: Map<String, String> = emptyMap()

    override suspend fun readFrom(input: InputStream): Map<String, String> =
        input.readDecrypted(CommonJson, encryption)

    override suspend fun writeTo(t: Map<String, String>, output: OutputStream) =
        output.writeEncrypted(t, CommonJson, encryption)
}

@Module
@InstallIn(SingletonComponent::class)
object NwcModule {

    @Provides
    @Singleton
    @NwcConnectionsStore
    fun provideNwcConnectionsStore(
        @ApplicationContext context: Context,
        encryption: Encryption,
    ): DataStore<Map<String, String>> =
        DataStoreFactory.create(
            serializer = NwcConnectionsSerialization(encryption),
            // A file that no longer decrypts only means the wallet has to be connected again.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyMap() },
            produceFile = { context.dataStoreFile("nwc_connections.json") },
        )
}
