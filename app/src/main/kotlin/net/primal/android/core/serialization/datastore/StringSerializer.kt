package net.primal.android.core.serialization.datastore

import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import net.primal.android.security.Encryption

class StringSerializer(
    private val encryption: Encryption? = null,
    /**
     * What a store reads as before anything has ever been written to its file. Only an absent
     * file yields this: a value written later, an empty string included, is read back as written,
     * so a store can tell "never chosen" apart from "chosen, and the choice is empty".
     */
    override val defaultValue: String = "",
) : Serializer<String> {

    override suspend fun readFrom(input: InputStream): String {
        return encryption?.decrypt(input) ?: String(input.readBytes())
    }

    override suspend fun writeTo(t: String, output: OutputStream) {
        encryption?.encrypt(t, output) ?: output.use {
            it.write(t.toByteArray())
        }
    }
}
