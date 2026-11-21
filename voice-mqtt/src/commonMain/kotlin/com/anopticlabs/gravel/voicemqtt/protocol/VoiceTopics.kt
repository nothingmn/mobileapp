package com.anopticlabs.gravel.voicemqtt.protocol

/** The two topics the protocol names, as defaults only: every one of them is editable in Settings. */
const val DEFAULT_INGRESS_TOPIC = "agentry/v1/gateway/req/voicessistant"
const val DEFAULT_EGRESS_TOPIC = "agentry/v1/gateway/resp/voicessistant/#"

/**
 * Topic shape rules, checked when the user saves rather than when the client connects, so a typo is an
 * inline error on the field instead of a client that silently receives nothing.
 */
object VoiceTopics {

    /**
     * Null when [topic] is a topic the user may keep. The request topic must be one concrete topic (the
     * route's name is the client's whole authority, so a wildcard there addresses nobody), while the reply
     * filter is expected to carry `#`: the reply topic's last segment is server-minted.
     */
    fun refusal(topic: String, allowWildcard: Boolean): String? {
        val trimmed = topic.trim()
        if (trimmed.isEmpty()) return "Enter a topic"
        if (trimmed != topic) return "A topic cannot start or end with a space"
        if (trimmed.startsWith("/") || trimmed.endsWith("/")) return "A topic cannot start or end with /"
        if (trimmed.split('/').any { it.isEmpty() }) return "A topic cannot have an empty level"
        if (!allowWildcard && trimmed.any { it == '+' || it == '#' }) {
            return "The request topic must be one exact topic, without + or #"
        }
        if (allowWildcard && !wildcardsWellFormed(trimmed)) {
            return "# must be the last level and fill it, and + must fill a level"
        }
        if (trimmed.any { it.isWhitespace() }) return "A topic cannot contain a space"
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
