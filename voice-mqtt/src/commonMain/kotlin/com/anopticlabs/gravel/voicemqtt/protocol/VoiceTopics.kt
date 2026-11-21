package com.anopticlabs.gravel.voicemqtt.protocol

import com.anopticlabs.gravel.voicemqtt.copy.VoiceCopy
import com.anopticlabs.gravel.voicemqtt.copy.VoiceKeys

/**
 * Topic shape rules, checked when the user saves rather than when the client connects, so a typo is an
 * inline error on the field instead of a client that silently receives nothing.
 *
 * There are no topic defaults here on purpose: both topics come from the build (`voiceMqttRequestTopic`,
 * `voiceMqttResponseTopic`, blank in this repository) or from the user, and an unset one is a field error
 * rather than a topic this code chose.
 *
 * A refusal is copy, not text: the rule is pure Kotlin proved by the host tests, and the screen resolves the key
 * in the phone's language.
 */
object VoiceTopics {

    /**
     * Null when [topic] is a topic the user may keep. The request topic must be one concrete topic (the
     * route's name is the client's whole authority, so a wildcard there addresses nobody), while the reply
     * filter is expected to carry `#`: the reply topic's last segment is server-minted.
     */
    fun refusal(topic: String, allowWildcard: Boolean): VoiceCopy? {
        val trimmed = topic.trim()
        if (trimmed.isEmpty()) return VoiceCopy(VoiceKeys.ERROR_TOPIC_BLANK)
        if (trimmed != topic) return VoiceCopy(VoiceKeys.ERROR_TOPIC_EDGE_SPACE)
        if (trimmed.startsWith("/") || trimmed.endsWith("/")) return VoiceCopy(VoiceKeys.ERROR_TOPIC_EDGE_SLASH)
        if (trimmed.split('/').any { it.isEmpty() }) return VoiceCopy(VoiceKeys.ERROR_TOPIC_EMPTY_LEVEL)
        if (!allowWildcard && trimmed.any { it == '+' || it == '#' }) {
            return VoiceCopy(VoiceKeys.ERROR_TOPIC_WILDCARD_FORBIDDEN)
        }
        if (allowWildcard && !wildcardsWellFormed(trimmed)) {
            return VoiceCopy(VoiceKeys.ERROR_TOPIC_WILDCARD_MALFORMED)
        }
        if (trimmed.any { it.isWhitespace() }) return VoiceCopy(VoiceKeys.ERROR_TOPIC_SPACE)
        return null
    }

    private fun wildcardsWellFormed(topic: String): Boolean {
        val levels = topic.split('/')
        return levels.withIndex().all { (index, level) ->
            when {
                level == "#" -> index == levels.lastIndex
                level.contains('#') -> false
                level.contains('+') -> level == "+"
                else -> true
            }
        }
    }
}
