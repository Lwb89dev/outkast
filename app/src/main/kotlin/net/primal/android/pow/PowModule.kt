package net.primal.android.pow

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
import kotlinx.serialization.SerializationException
import net.primal.core.utils.serialization.CommonJson

private object PowSettingsSerialization : Serializer<Map<String, PowSettings>> {

    override val defaultValue: Map<String, PowSettings> = emptyMap()

    override suspend fun readFrom(input: InputStream): Map<String, PowSettings> =
        try {
            CommonJson.decodeFromString(input.readBytes().decodeToString())
        } catch (error: SerializationException) {
            throw androidx.datastore.core.CorruptionException("Unreadable proof of work settings.", error)
        }

    override suspend fun writeTo(t: Map<String, PowSettings>, output: OutputStream) =
        output.write(CommonJson.encodeToString(t).encodeToByteArray())
}

@Module
@InstallIn(SingletonComponent::class)
object PowModule {

    @Provides
    @Singleton
    @PowSettingsDataStore
    fun providePowSettingsStore(@ApplicationContext context: Context): DataStore<Map<String, PowSettings>> =
        DataStoreFactory.create(
            serializer = PowSettingsSerialization,
            corruptionHandler = ReplaceFileCorruptionHandler { emptyMap() },
            produceFile = { context.dataStoreFile("pow_settings.json") },
        )
}
