package com.anopticlabs.gravel.voicemqtt.copy

import android.content.Context
import com.anopticlabs.gravel.voicemqtt.R

/**
 * The feature's copy, read from the Android string resources so it follows the phone's language.
 *
 * Android resources rather than Compose resources: the sentences the speaker says
 * ([com.anopticlabs.gravel.voicemqtt.speech.VoiceLoopSentences], the refusal and suppression explanations) are
 * resolved from a coroutine with no `Composable` anywhere near them, and `Context.getString` is the lookup that
 * works in both places. Everything read here comes from `res/values/strings.xml` and its `values-*`
 * translations, so one phone language picks one file for the screen and the voice alike.
 *
 * The table is explicit rather than a name lookup, so a key with no resource is a compile-time gap a test
 * catches, not a runtime surprise.
 */
class AndroidVoiceText(context: Context) : VoiceText {

    private val resources = context.applicationContext.resources

    override fun of(copy: VoiceCopy): String {
        val id = checkNotNull(resourceOrNull(copy.key)) { "no string resource for ${copy.key}" }
        return if (copy.args.isEmpty()) {
            resources.getString(id)
        } else {
            resources.getString(id, *copy.args.toTypedArray())
        }
    }

    companion object {
        /** The resource that holds [key], or null for a key this build does not know. */
        fun resourceOrNull(key: String): Int? = when (key) {
            VoiceKeys.SECTION_CONNECTION -> R.string.voice_section_connection
            VoiceKeys.STATUS_CONNECTED -> R.string.voice_status_connected
            VoiceKeys.STATUS_CONNECTING -> R.string.voice_status_connecting
            VoiceKeys.STATUS_DISCONNECTED -> R.string.voice_status_disconnected
            VoiceKeys.HINT_CONNECTION_UNSET -> R.string.voice_hint_connection_unset
            VoiceKeys.TOGGLE_LOOP_TITLE -> R.string.voice_toggle_loop_title
            VoiceKeys.TOGGLE_LOOP_DESCRIPTION -> R.string.voice_toggle_loop_description
            VoiceKeys.FIELD_BROKER_ADDRESS -> R.string.voice_field_broker_address
            VoiceKeys.FIELD_PORT -> R.string.voice_field_port
            VoiceKeys.TOGGLE_TLS_TITLE -> R.string.voice_toggle_tls_title
            VoiceKeys.TOGGLE_TLS_DESCRIPTION -> R.string.voice_toggle_tls_description
            VoiceKeys.TOGGLE_SELF_SIGNED_TITLE -> R.string.voice_toggle_self_signed_title
            VoiceKeys.TOGGLE_SELF_SIGNED_DESCRIPTION -> R.string.voice_toggle_self_signed_description
            VoiceKeys.SECTION_CREDENTIALS -> R.string.voice_section_credentials
            VoiceKeys.FIELD_USERNAME -> R.string.voice_field_username
            VoiceKeys.FIELD_PASSWORD -> R.string.voice_field_password
            VoiceKeys.BUTTON_SHOW -> R.string.voice_button_show
            VoiceKeys.BUTTON_HIDE -> R.string.voice_button_hide
            VoiceKeys.FIELD_PASSWORD_NOTE -> R.string.voice_field_password_note
            VoiceKeys.SECTION_TOPICS -> R.string.voice_section_topics
            VoiceKeys.FIELD_INGRESS_TOPIC -> R.string.voice_field_ingress_topic
            VoiceKeys.HINT_INGRESS_TOPIC -> R.string.voice_hint_ingress_topic
            VoiceKeys.FIELD_EGRESS_TOPIC -> R.string.voice_field_egress_topic
            VoiceKeys.HINT_EGRESS_TOPIC -> R.string.voice_hint_egress_topic
            VoiceKeys.SECTION_SPEAKING -> R.string.voice_section_speaking
            VoiceKeys.TOGGLE_SPEAK_REPLIES_TITLE -> R.string.voice_toggle_speak_replies_title
            VoiceKeys.TOGGLE_SPEAK_REPLIES_DESCRIPTION -> R.string.voice_toggle_speak_replies_description
            VoiceKeys.TOGGLE_HEADSET_ONLY_TITLE -> R.string.voice_toggle_headset_only_title
            VoiceKeys.TOGGLE_HEADSET_ONLY_DESCRIPTION -> R.string.voice_toggle_headset_only_description
            VoiceKeys.PICKER_DEVICE_LINE -> R.string.voice_picker_device_line
            VoiceKeys.PICKER_ANY_DEVICE -> R.string.voice_picker_any_device
            VoiceKeys.HINT_DEVICE_HEADSET_ONLY -> R.string.voice_hint_device_headset_only
            VoiceKeys.HINT_DEVICES_PERMISSION -> R.string.voice_hint_devices_permission
            VoiceKeys.SPEECH_LAST_NOT_PLAYED -> R.string.voice_speech_last_not_played
            VoiceKeys.SPEAKING_CHANNEL -> R.string.voice_speaking_channel
            VoiceKeys.SPEAKING_TITLE -> R.string.voice_speaking_title
            VoiceKeys.SPEAKING_BODY -> R.string.voice_speaking_body
            VoiceKeys.SPEAKING_STOP -> R.string.voice_speaking_stop
            VoiceKeys.BUTTON_SAVE_AND_RECONNECT -> R.string.voice_button_save_and_reconnect
            VoiceKeys.ACTION_TEST_SENT -> R.string.voice_action_test_sent
            VoiceKeys.BUTTON_SEND_TEST -> R.string.voice_button_send_test
            VoiceKeys.FILING_APPENDED -> R.string.voice_filing_appended
            VoiceKeys.FILING_NOT_LISTED -> R.string.voice_filing_not_listed
            VoiceKeys.FILING_ARCHIVED -> R.string.voice_filing_archived
            VoiceKeys.FILING_CHANGED_AFTER_RETRY -> R.string.voice_filing_changed_after_retry
            VoiceKeys.FILING_EMPTY_ANSWER -> R.string.voice_filing_empty_answer
            VoiceKeys.FILING_STORAGE_UNAVAILABLE -> R.string.voice_filing_storage_unavailable
            VoiceKeys.ENGINE_START_FAILED -> R.string.voice_engine_start_failed
            VoiceKeys.ENGINE_INIT_FAILED -> R.string.voice_engine_init_failed
            VoiceKeys.ENGINE_DEFAULT -> R.string.voice_engine_default
            VoiceKeys.ENGINE_DEFAULT_NAMED -> R.string.voice_engine_default_named
            VoiceKeys.DEVICE_UNNAMED -> R.string.voice_device_unnamed
            VoiceKeys.NOT_PLAYED_REPLIES_OFF -> R.string.voice_not_played_replies_off
            VoiceKeys.NOT_PLAYED_NO_BLUETOOTH_OUTPUT -> R.string.voice_not_played_no_bluetooth_output
            VoiceKeys.NOT_PLAYED_DEVICE_NOT_CONNECTED -> R.string.voice_not_played_device_not_connected
            VoiceKeys.NOT_PLAYED_NO_OFFLINE_VOICE -> R.string.voice_not_played_no_offline_voice
            VoiceKeys.NOT_PLAYED_NO_USABLE_VOICE -> R.string.voice_not_played_no_usable_voice
            VoiceKeys.REFUSAL_NO_VOICES -> R.string.voice_refusal_no_voices
            VoiceKeys.REFUSAL_NETWORK_VOICES_ONLY -> R.string.voice_refusal_network_voices_only
            VoiceKeys.REFUSAL_NO_OFFLINE_VOICE_FOR_LANGUAGE -> R.string.voice_refusal_no_offline_voice_for_language
            VoiceKeys.REFUSAL_NO_VOICE_FOR_LANGUAGE -> R.string.voice_refusal_no_voice_for_language
            VoiceKeys.REFUSAL_PINNED_VOICE_NETWORK_ONLY -> R.string.voice_refusal_pinned_voice_network_only
            VoiceKeys.REFUSAL_VOICE_NOT_ACCEPTED -> R.string.voice_refusal_voice_not_accepted
            VoiceKeys.SPOKEN_FAILURE -> R.string.voice_spoken_failure
            VoiceKeys.SPOKEN_TIMEOUT -> R.string.voice_spoken_timeout
            VoiceKeys.SPOKEN_NOT_CONNECTED -> R.string.voice_spoken_not_connected
            VoiceKeys.SPOKEN_BUSY -> R.string.voice_spoken_busy
            VoiceKeys.SPOKEN_EMPTY_TRANSCRIPT -> R.string.voice_spoken_empty_transcript
            VoiceKeys.SPOKEN_TOO_LONG -> R.string.voice_spoken_too_long
            VoiceKeys.SPOKEN_TOO_LARGE -> R.string.voice_spoken_too_large
            VoiceKeys.SPOKEN_BROKEN_REQUEST -> R.string.voice_spoken_broken_request
            VoiceKeys.SPOKEN_TEST_MESSAGE -> R.string.voice_spoken_test_message
            VoiceKeys.ERROR_HOST_BLANK -> R.string.voice_error_host_blank
            VoiceKeys.ERROR_HOST_SPACE -> R.string.voice_error_host_space
            VoiceKeys.ERROR_PORT_BLANK -> R.string.voice_error_port_blank
            VoiceKeys.ERROR_PORT_RANGE -> R.string.voice_error_port_range
            VoiceKeys.ERROR_TOPIC_BLANK -> R.string.voice_error_topic_blank
            VoiceKeys.ERROR_TOPIC_EDGE_SPACE -> R.string.voice_error_topic_edge_space
            VoiceKeys.ERROR_TOPIC_EDGE_SLASH -> R.string.voice_error_topic_edge_slash
            VoiceKeys.ERROR_TOPIC_EMPTY_LEVEL -> R.string.voice_error_topic_empty_level
            VoiceKeys.ERROR_TOPIC_WILDCARD_FORBIDDEN -> R.string.voice_error_topic_wildcard_forbidden
            VoiceKeys.ERROR_TOPIC_WILDCARD_MALFORMED -> R.string.voice_error_topic_wildcard_malformed
            VoiceKeys.ERROR_TOPIC_SPACE -> R.string.voice_error_topic_space
            VoiceKeys.CONNECTION_ENDED -> R.string.voice_connection_ended
            VoiceKeys.CONNECTION_AUTH_REFUSED -> R.string.voice_connection_auth_refused
            VoiceKeys.CONNECTION_NO_ANSWER -> R.string.voice_connection_no_answer
            VoiceKeys.CONNECTION_BROKER_UNAVAILABLE -> R.string.voice_connection_broker_unavailable
            VoiceKeys.CONNECTION_UNREACHABLE_CODE -> R.string.voice_connection_unreachable_code
            VoiceKeys.CONNECTION_UNREACHABLE -> R.string.voice_connection_unreachable
            else -> null
        }
    }
}
