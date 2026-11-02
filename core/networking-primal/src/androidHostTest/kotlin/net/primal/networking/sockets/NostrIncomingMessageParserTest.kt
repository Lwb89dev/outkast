package net.primal.networking.sockets

import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import net.primal.core.networking.sockets.NostrCommandResultPrefix
import net.primal.core.networking.sockets.NostrIncomingMessage
import net.primal.core.networking.sockets.parseIncomingMessage
import net.primal.domain.nostr.NostrEvent

class NostrIncomingMessageParserTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @org.junit.Test
    fun `EVENT message parsed as EventMessage`() {
        val jsonMessage = buildJsonArray {
            add("EVENT")
            add(Uuid.random().toString())
            add(
                json.encodeToJsonElement(
                    NostrEvent(
                        id = "invalidId",
                        pubKey = "pukey",
                        createdAt = Clock.System.now().epochSeconds,
                        kind = 1,
                        tags = emptyList(),
                        content = "",
                        sig = "signature",
                    ),
                ),
            )
        }

        val actual = jsonMessage.toString().parseIncomingMessage()
        actual should beInstanceOf<NostrIncomingMessage.EventMessage>()
        (actual as NostrIncomingMessage.EventMessage).nostrEvent shouldBe null
    }

    @org.junit.Test
    fun `EOSE message parsed as EoseMessage`() {
        val jsonMessage = buildJsonArray {
            add("EOSE")
            add(Uuid.random().toString())
        }

        val actual = jsonMessage.toString().parseIncomingMessage()
        actual should beInstanceOf<NostrIncomingMessage.EoseMessage>()
    }

    @org.junit.Test
    fun `NOTICE without subscription id parsed with null subscriptionId and message`() {
        val jsonMessage = buildJsonArray {
            add("NOTICE")
            add("Your pubkey is not registered.")
        }

        val actual = jsonMessage.toString().parseIncomingMessage()

        actual should beInstanceOf<NostrIncomingMessage.NoticeMessage>()
        val notice = actual as NostrIncomingMessage.NoticeMessage
        notice.subscriptionId shouldBe null
        notice.message shouldBe "Your pubkey is not registered."
    }

    @org.junit.Test
    fun `NOTICE with subscription id parsed with both subscriptionId and message`() {
        val jsonMessage = buildJsonArray {
            add("NOTICE")
            add("android-fca5c468")
            add("error")
        }

        val actual = jsonMessage.toString().parseIncomingMessage()

        actual should beInstanceOf<NostrIncomingMessage.NoticeMessage>()
        val notice = actual as NostrIncomingMessage.NoticeMessage
        notice.subscriptionId shouldBe "android-fca5c468"
        notice.message shouldBe "error"
    }

    @org.junit.Test
    fun `OK false message parsed with NIP-20 rejection details`() {
        val jsonMessage = buildJsonArray {
            add("OK")
            add("ThisIsEventId")
            add(false)
            add("blocked: pubkey not registered.")
        }

        val actual = jsonMessage.toString().parseIncomingMessage()
        actual should beInstanceOf<NostrIncomingMessage.OkMessage>()
        val ok = actual as NostrIncomingMessage.OkMessage
        ok.eventId shouldBe "ThisIsEventId"
        ok.success shouldBe false
        ok.machineReadablePrefix shouldBe "blocked"
        ok.prefix shouldBe NostrCommandResultPrefix.Blocked
        ok.humanReadableMessage shouldBe "pubkey not registered."
    }

    @org.junit.Test
    fun `OK true message parsed with empty human readable message`() {
        val jsonMessage = buildJsonArray {
            add("OK")
            add("ThisIsEventId")
            add(true)
            add("")
        }

        val actual = jsonMessage.toString().parseIncomingMessage()
        actual should beInstanceOf<NostrIncomingMessage.OkMessage>()
        val ok = actual as NostrIncomingMessage.OkMessage
        ok.eventId shouldBe "ThisIsEventId"
        ok.success shouldBe true
        ok.machineReadablePrefix shouldBe null
        ok.prefix shouldBe null
        ok.humanReadableMessage shouldBe null
    }

    @org.junit.Test
    fun `OK message recognizes every supported command result prefix`() {
        NostrCommandResultPrefix.entries.forEach { expected ->
            val jsonMessage = buildJsonArray {
                add("OK")
                add("ThisIsEventId")
                add(false)
                add("${expected.wireValue}: human readable detail")
            }

            val ok = jsonMessage.toString().parseIncomingMessage() as NostrIncomingMessage.OkMessage
            ok.machineReadablePrefix shouldBe expected.wireValue
            ok.prefix shouldBe expected
            ok.humanReadableMessage shouldBe "human readable detail"
        }
    }

    @org.junit.Test
    fun `OK message preserves an unknown extension prefix`() {
        val jsonMessage = buildJsonArray {
            add("OK")
            add("ThisIsEventId")
            add(false)
            add("future-extension: human readable detail")
        }

        val ok = jsonMessage.toString().parseIncomingMessage() as NostrIncomingMessage.OkMessage
        ok.machineReadablePrefix shouldBe "future-extension"
        ok.prefix shouldBe null
        ok.humanReadableMessage shouldBe "human readable detail"
    }

    @org.junit.Test
    fun `AUTH message parsed as AuthMessage`() {
        val jsonMessage = buildJsonArray {
            add("AUTH")
            add("AuthChallenge")
        }

        val actual = jsonMessage.toString().parseIncomingMessage()
        actual should beInstanceOf<NostrIncomingMessage.AuthMessage>()
    }

    @org.junit.Test
    fun `COUNT message parsed as CountMessage`() {
        val jsonMessage = buildJsonArray {
            add("COUNT")
            add(Uuid.random().toString())
            add(
                buildJsonObject {
                    put("count", 123)
                },
            )
        }

        val actual = jsonMessage.toString().parseIncomingMessage()
        actual should beInstanceOf<NostrIncomingMessage.CountMessage>()
    }
}
