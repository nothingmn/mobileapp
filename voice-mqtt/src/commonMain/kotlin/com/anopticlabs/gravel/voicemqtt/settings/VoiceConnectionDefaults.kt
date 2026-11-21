package com.anopticlabs.gravel.voicemqtt.settings

/**
 * What one build baked in, read from the BuildKonfig fields `voice-mqtt/build.gradle.kts` fills from
 * `gradle.properties` or a `-P...` on the command line.
 *
 * Every value is absent by default, because the repository ships those keys blank: no broker address, no port
 * and no topics. Absent is not a fallback — it means the settings screen starts with the field empty and says
 * the address is needed, rather than the app quietly connecting somewhere. A baked value is only a pre-filled
 * field the user can still change, and what the user stored always wins (see [VoiceConnectionDefaults]).
 *
 * [brokerPort] is the raw text a build supplied rather than an `Int`, because a value that is not a port must
 * leave the field unset instead of failing the build or crashing the app; [VoiceConnectionDefaults.parsedPort]
 * decides what the text means.
 */
data class BakedVoiceDefaults(
    val brokerHost: String? = null,
    val brokerPort: String? = null,
    val tls: Boolean = false,
    val requestTopic: String? = null,
    val responseTopic: String? = null,
) {
    companion object {
        /** Nothing baked in at all: what this repository's own blank keys produce. */
        val NONE = BakedVoiceDefaults()
    }
}

/**
 * The connection values the phone holds, as the store found them.
 *
 * Null means "the user has never saved this field"; anything else is what the user saved, including a field
 * they deliberately emptied. That distinction is the whole point: a blank the user chose must survive a
 * re-read, so it cannot be mistaken for "nothing stored" and replaced by a baked value.
 */
data class StoredConnectionValues(
    val host: String? = null,
    val port: Int? = null,
    val tls: Boolean? = null,
    val requestTopic: String? = null,
    val responseTopic: String? = null,
)

/** What the client actually uses at connect time, once the phone's values and the build's are put together. */
data class ResolvedConnectionValues(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val requestTopic: String,
    val responseTopic: String,
)

/**
 * The precedence rule, in one place so the store, the settings screen and the tests cannot disagree:
 *
 * **a value the user stored beats a value the build baked, and a blank or absent baked value leaves the field
 * unset.**
 *
 * - a stored value wins even when it is blank, because emptying a field is a choice;
 * - otherwise the baked value, trimmed, when it is not blank;
 * - otherwise the field is unset: an empty address, an empty topic, and [UNSET_PORT] for the port.
 *
 * The port is the only field with a derived default, and only once an address exists: a build that names a
 * broker but no usable port is assumed to speak plain MQTT on [PLAIN_MQTT_PORT], while a build that names
 * neither leaves the port unset so the screen asks for both rather than showing a guess. TLS never derives from
 * anything; unset is plain `tcp://`, never `ssl://`.
 */
object VoiceConnectionDefaults {

    /** The port a field has when there is none; the settings screen shows a blank field for it, not a number. */
    const val UNSET_PORT = 0

    /** The port plain MQTT listens on, used only when an address is known and the build named no port. */
    const val PLAIN_MQTT_PORT = 1883

    fun resolve(stored: StoredConnectionValues, baked: BakedVoiceDefaults): ResolvedConnectionValues {
        val host = stored.host ?: baked.brokerHost.trimmedOrNull() ?: ""
        // A port only means something with an address to reach: a build that names a port but no broker leaves
        // the port unset too, rather than showing a number that connects to nowhere.
        val port = stored.port
            ?: if (host.isNotEmpty()) parsedPort(baked.brokerPort) ?: PLAIN_MQTT_PORT else UNSET_PORT
        return ResolvedConnectionValues(
            host = host,
            port = port,
            tls = stored.tls ?: baked.tls,
            requestTopic = stored.requestTopic ?: baked.requestTopic.trimmedOrNull() ?: "",
            responseTopic = stored.responseTopic ?: baked.responseTopic.trimmedOrNull() ?: "",
        )
    }

    /** A baked port only counts when it is a whole number a port can be; anything else means the field is unset. */
    fun parsedPort(text: String?): Int? = text?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
}

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
