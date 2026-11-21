package com.anopticlabs.gravel.voicemqtt.settings

import com.anopticlabs.gravel.voicemqtt.copy.VoiceKeys
import com.anopticlabs.gravel.voicemqtt.protocol.VoiceTopics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the broker, the port, TLS and the two topics come from: a value the user stored, else a value the build
 * baked in, else nothing at all.
 *
 * The concrete values here are deliberately synthetic (`broker.example`, `example/topic`): no real address or
 * topic belongs in this repository, which is exactly the rule these tests are holding the code to.
 */
class VoiceConnectionDefaultsTest {

    /** What a build that supplies its own broker looks like; `.example` is reserved for exactly this. */
    private val bakedExample = BakedVoiceDefaults(
        brokerHost = "broker.example",
        brokerPort = "1883",
        requestTopic = "example/topic",
        responseTopic = "example/topic/#",
    )

    @Test
    fun aBuildThatBakesNothingLeavesEveryConnectionFieldEmpty() {
        // The shipped state of this repository: blank keys in gradle.properties, so nothing is pre-filled and
        // nothing stands in for a value the user has not given.
        val resolved = VoiceConnectionDefaults.resolve(StoredConnectionValues(), BakedVoiceDefaults.NONE)
        assertEquals("", resolved.host, "no address is invented")
        assertEquals(VoiceConnectionDefaults.UNSET_PORT, resolved.port, "no port is invented")
        assertEquals(false, resolved.tls, "unset TLS is plain tcp")
        assertEquals("", resolved.requestTopic, "no request topic is invented")
        assertEquals("", resolved.responseTopic, "no reply topic is invented")
    }

    @Test
    fun aBakedBrokerBecomesTheInitialFieldValue() {
        val resolved = VoiceConnectionDefaults.resolve(StoredConnectionValues(), bakedExample)
        assertEquals("broker.example", resolved.host)
        assertEquals(1883, resolved.port)
        assertEquals("example/topic", resolved.requestTopic)
        assertEquals("example/topic/#", resolved.responseTopic)
        // And the screen accepts it as it stands, which is what makes it a usable pre-fill.
        val settings = VoiceSettings(
            host = resolved.host,
            port = resolved.port,
            tls = resolved.tls,
            ingressTopic = resolved.requestTopic,
            egressTopic = resolved.responseTopic,
        )
        assertTrue(settings.valid, "a fully baked connection needs no correction: ${settings.errors()}")
    }

    @Test
    fun aBlankOrWhitespaceBakedValueIsUnsetRatherThanADefault() {
        val blank = VoiceConnectionDefaults.resolve(
            StoredConnectionValues(),
            BakedVoiceDefaults(brokerHost = "", brokerPort = "", requestTopic = "", responseTopic = ""),
        )
        assertEquals("", blank.host)
        assertEquals(VoiceConnectionDefaults.UNSET_PORT, blank.port)
        assertEquals("", blank.requestTopic)

        val whitespace = VoiceConnectionDefaults.resolve(
            StoredConnectionValues(),
            BakedVoiceDefaults(brokerHost = "   ", brokerPort = "  ", requestTopic = " ", responseTopic = "\t"),
        )
        assertEquals("", whitespace.host, "whitespace is not an address")
        assertEquals(VoiceConnectionDefaults.UNSET_PORT, whitespace.port)
        assertEquals("", whitespace.requestTopic)
        assertEquals("", whitespace.responseTopic)
    }

    @Test
    fun aStoredValueAlwaysBeatsTheBakedOne() {
        val resolved = VoiceConnectionDefaults.resolve(
            StoredConnectionValues(
                host = "phone.broker.example",
                port = 9000,
                tls = true,
                requestTopic = "user/request",
                responseTopic = "user/reply/#",
            ),
            bakedExample.copy(tls = false),
        )
        assertEquals("phone.broker.example", resolved.host)
        assertEquals(9000, resolved.port)
        assertTrue(resolved.tls, "a stored TLS choice wins over a baked one")
        assertEquals("user/request", resolved.requestTopic)
        assertEquals("user/reply/#", resolved.responseTopic)
    }

    @Test
    fun aStoredOffBeatsABakedOn() {
        val resolved = VoiceConnectionDefaults.resolve(
            StoredConnectionValues(host = "phone.broker.example", tls = false),
            bakedExample.copy(tls = true),
        )
        assertFalse(resolved.tls, "the user turning TLS off is a choice, not an absent value")
    }

    @Test
    fun aFieldTheUserEmptiedStaysEmptyRatherThanFallingBackToTheBakedValue() {
        // Emitting a value the user cleared would overwrite their own choice with the build's, silently.
        val resolved = VoiceConnectionDefaults.resolve(
            StoredConnectionValues(host = "", requestTopic = ""),
            bakedExample,
        )
        assertEquals("", resolved.host)
        assertEquals("", resolved.requestTopic)
        assertEquals("example/topic/#", resolved.responseTopic, "only the cleared fields are affected")
    }

    @Test
    fun aBakedPortThatIsNotANumberIsNotGuessed() {
        assertNull(VoiceConnectionDefaults.parsedPort("eighteen"))
        assertNull(VoiceConnectionDefaults.parsedPort("0"), "zero is not a port")
        assertNull(VoiceConnectionDefaults.parsedPort("70000"), "a port is not above 65535")
        assertEquals(1883, VoiceConnectionDefaults.parsedPort(" 1883 "))
        assertEquals(8883, VoiceConnectionDefaults.parsedPort("8883"))
    }

    @Test
    fun aPortDefaultsOnlyOnceThereIsAnAddress() {
        // A build that names a broker but no usable port gets the plain MQTT port, so the phone connects and can
        // be corrected in Settings; with no address there is nothing to connect to, so the port stays unset.
        val hostOnly = VoiceConnectionDefaults.resolve(
            StoredConnectionValues(),
            BakedVoiceDefaults(brokerHost = "broker.example"),
        )
        assertEquals("broker.example", hostOnly.host)
        assertEquals(VoiceConnectionDefaults.PLAIN_MQTT_PORT, hostOnly.port)

        val badPortWithHost = VoiceConnectionDefaults.resolve(
            StoredConnectionValues(),
            BakedVoiceDefaults(brokerHost = "broker.example", brokerPort = "not-a-port"),
        )
        assertEquals(VoiceConnectionDefaults.PLAIN_MQTT_PORT, badPortWithHost.port)

        val noHost = VoiceConnectionDefaults.resolve(
            StoredConnectionValues(),
            BakedVoiceDefaults(brokerPort = "1883"),
        )
        assertEquals(VoiceConnectionDefaults.UNSET_PORT, noHost.port, "a port with no address is not usable")
    }

    @Test
    fun theTopicsKeepTheRulesTheyAlreadyHad() {
        val concrete = VoiceConnectionDefaults.resolve(
            StoredConnectionValues(),
            BakedVoiceDefaults(requestTopic = "example/topic", responseTopic = "example/topic/#"),
        )
        assertNull(VoiceTopics.refusal(concrete.requestTopic, allowWildcard = false))
        assertNull(VoiceTopics.refusal(concrete.responseTopic, allowWildcard = true))

        // A baked request topic carrying a wildcard is refused like any other: the route names the client, and a
        // wildcard addresses nobody.
        val wildcardRequest = VoiceConnectionDefaults.resolve(
            StoredConnectionValues(),
            BakedVoiceDefaults(requestTopic = "example/+/request", responseTopic = "example/topic/#"),
        )
        assertEquals(
            VoiceKeys.ERROR_TOPIC_WILDCARD_FORBIDDEN,
            VoiceTopics.refusal(wildcardRequest.requestTopic, allowWildcard = false)?.key,
        )
    }

    @Test
    fun anEmptyConnectionIsAVisibleErrorOnEveryFieldRatherThanASilentStart() {
        // What a phone built from this repository shows before the user types anything: each field says what is
        // missing, and the save button is therefore not offered.
        val settings = VoiceSettings()
        assertEquals(VoiceKeys.ERROR_HOST_BLANK, settings.errors()[VoiceField.HOST]?.key)
        assertEquals(VoiceKeys.ERROR_PORT_BLANK, settings.errors()[VoiceField.PORT]?.key)
        assertEquals(VoiceKeys.ERROR_TOPIC_BLANK, settings.errors()[VoiceField.INGRESS_TOPIC]?.key)
        assertEquals(VoiceKeys.ERROR_TOPIC_BLANK, settings.errors()[VoiceField.EGRESS_TOPIC]?.key)
        assertFalse(settings.valid)
    }

    @Test
    fun aPortOutOfRangeIsStillItsOwnError() {
        val settings = VoiceSettings(
            host = "broker.example",
            port = 70000,
            ingressTopic = "example/topic",
            egressTopic = "example/topic/#",
        )
        assertEquals(VoiceKeys.ERROR_PORT_RANGE, settings.errors()[VoiceField.PORT]?.key)
    }
}
