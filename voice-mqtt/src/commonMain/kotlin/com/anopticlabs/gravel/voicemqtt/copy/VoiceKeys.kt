package com.anopticlabs.gravel.voicemqtt.copy

/**
 * Every string this feature can put in front of the user or say aloud, by its resource name.
 *
 * One constant per `<string>` in `src/androidMain/res/values/strings.xml`, and the same names in
 * `values-fr/strings.xml`. The names are the resource names on purpose: a constant and its resource cannot drift
 * apart without a test noticing, because [VoiceCopyKeysTest] compares this set with the two locale files and
 * with the Android lookup table one for one. The prefix `voice_` keeps the whole feature's copy greppable in a
 * resource file that an upstream review sees whole.
 */
object VoiceKeys {

    // Settings > Voice Assistant: the screen itself.
    const val SECTION_CONNECTION = "voice_section_connection"
    const val STATUS_CONNECTED = "voice_status_connected"
    const val STATUS_CONNECTING = "voice_status_connecting"
    const val STATUS_DISCONNECTED = "voice_status_disconnected"
    const val HINT_CONNECTION_UNSET = "voice_hint_connection_unset"
    const val TOGGLE_LOOP_TITLE = "voice_toggle_loop_title"
    const val TOGGLE_LOOP_DESCRIPTION = "voice_toggle_loop_description"
    const val FIELD_BROKER_ADDRESS = "voice_field_broker_address"
    const val FIELD_PORT = "voice_field_port"
    const val TOGGLE_TLS_TITLE = "voice_toggle_tls_title"
    const val TOGGLE_TLS_DESCRIPTION = "voice_toggle_tls_description"
    const val TOGGLE_SELF_SIGNED_TITLE = "voice_toggle_self_signed_title"
    const val TOGGLE_SELF_SIGNED_DESCRIPTION = "voice_toggle_self_signed_description"
    const val SECTION_CREDENTIALS = "voice_section_credentials"
    const val FIELD_USERNAME = "voice_field_username"
    const val FIELD_PASSWORD = "voice_field_password"
    const val BUTTON_SHOW = "voice_button_show"
    const val BUTTON_HIDE = "voice_button_hide"
    const val FIELD_PASSWORD_NOTE = "voice_field_password_note"
    const val SECTION_TOPICS = "voice_section_topics"
    const val FIELD_INGRESS_TOPIC = "voice_field_ingress_topic"
    const val HINT_INGRESS_TOPIC = "voice_hint_ingress_topic"
    const val FIELD_EGRESS_TOPIC = "voice_field_egress_topic"
    const val HINT_EGRESS_TOPIC = "voice_hint_egress_topic"
    const val SECTION_SPEAKING = "voice_section_speaking"
    const val TOGGLE_SPEAK_REPLIES_TITLE = "voice_toggle_speak_replies_title"
    const val TOGGLE_SPEAK_REPLIES_DESCRIPTION = "voice_toggle_speak_replies_description"
    const val TOGGLE_SPEAK_UNSOLICITED_TITLE = "voice_toggle_speak_unsolicited_title"
    const val TOGGLE_SPEAK_UNSOLICITED_DESCRIPTION = "voice_toggle_speak_unsolicited_description"
    const val TOGGLE_HEADSET_ONLY_TITLE = "voice_toggle_headset_only_title"
    const val TOGGLE_HEADSET_ONLY_DESCRIPTION = "voice_toggle_headset_only_description"
    const val PICKER_DEVICE_LINE = "voice_picker_device_line"
    const val PICKER_ANY_DEVICE = "voice_picker_any_device"
    const val HINT_DEVICE_HEADSET_ONLY = "voice_hint_device_headset_only"
    const val HINT_DEVICES_PERMISSION = "voice_hint_devices_permission"
    const val SPEECH_LAST_NOT_PLAYED = "voice_speech_last_not_played"
    // What arrived on the reply filter: the inventory, spoken or not.
    const val SECTION_CAPTURED = "voice_section_captured"
    const val CAPTURE_BOUND = "voice_capture_bound"
    const val CAPTURE_EMPTY = "voice_capture_empty"
    const val CAPTURE_ENTRY = "voice_capture_entry"
    const val CAPTURE_MATCHED = "voice_capture_matched"
    const val CAPTURE_UNMATCHED = "voice_capture_unmatched"
    const val CAPTURE_NO_TOPIC = "voice_capture_no_topic"

    const val SECTION_RECORDINGS = "voice_section_recordings"
    const val TOGGLE_DISABLE_APP_REMINDERS_TITLE = "voice_toggle_disable_app_reminders_title"
    const val TOGGLE_DISABLE_APP_REMINDERS_DESCRIPTION = "voice_toggle_disable_app_reminders_description"
    const val SPEAKING_CHANNEL = "voice_speaking_channel"
    const val SPEAKING_TITLE = "voice_speaking_title"
    const val SPEAKING_BODY = "voice_speaking_body"
    const val SPEAKING_STOP = "voice_speaking_stop"
    const val BUTTON_SAVE_AND_RECONNECT = "voice_button_save_and_reconnect"
    const val ACTION_TEST_SENT = "voice_action_test_sent"
    const val BUTTON_SEND_TEST = "voice_button_send_test"
    const val FILING_APPENDED = "voice_filing_appended"
    const val FILING_NOT_LISTED = "voice_filing_not_listed"
    const val FILING_ARCHIVED = "voice_filing_archived"
    const val FILING_CHANGED_AFTER_RETRY = "voice_filing_changed_after_retry"
    const val FILING_EMPTY_ANSWER = "voice_filing_empty_answer"
    const val FILING_STORAGE_UNAVAILABLE = "voice_filing_storage_unavailable"
    // The answer arrived before its recording had settled as a note, and no note was ever linked to it.
    const val FILING_NO_NOTE_LINKED = "voice_filing_no_note_linked"
    const val FILING_NO_SINK = "voice_filing_no_sink"

    // The speech engine's own state, shown under the switches.
    const val ENGINE_START_FAILED = "voice_engine_start_failed"
    const val ENGINE_INIT_FAILED = "voice_engine_init_failed"
    const val ENGINE_DEFAULT = "voice_engine_default"
    const val ENGINE_DEFAULT_NAMED = "voice_engine_default_named"
    const val DEVICE_UNNAMED = "voice_device_unnamed"

    // Why a sentence was held back, in words for the screen (the log lines stay English).
    const val NOT_PLAYED_REPLIES_OFF = "voice_not_played_replies_off"
    const val NOT_PLAYED_NO_BLUETOOTH_OUTPUT = "voice_not_played_no_bluetooth_output"
    const val NOT_PLAYED_DEVICE_NOT_CONNECTED = "voice_not_played_device_not_connected"
    const val NOT_PLAYED_NO_OFFLINE_VOICE = "voice_not_played_no_offline_voice"
    const val NOT_PLAYED_NO_USABLE_VOICE = "voice_not_played_no_usable_voice"

    // Why no voice could speak at all, in words for the screen (the log lines stay English).
    const val REFUSAL_NO_VOICES = "voice_refusal_no_voices"
    const val REFUSAL_NETWORK_VOICES_ONLY = "voice_refusal_network_voices_only"
    const val REFUSAL_NO_OFFLINE_VOICE_FOR_LANGUAGE = "voice_refusal_no_offline_voice_for_language"
    const val REFUSAL_NO_VOICE_FOR_LANGUAGE = "voice_refusal_no_voice_for_language"
    const val REFUSAL_PINNED_VOICE_NETWORK_ONLY = "voice_refusal_pinned_voice_network_only"
    const val REFUSAL_VOICE_NOT_ACCEPTED = "voice_refusal_voice_not_accepted"

    // Every sentence the loop can say aloud.
    const val SPOKEN_FAILURE = "voice_spoken_failure"
    const val SPOKEN_TIMEOUT = "voice_spoken_timeout"
    const val SPOKEN_NOT_CONNECTED = "voice_spoken_not_connected"
    const val SPOKEN_BUSY = "voice_spoken_busy"
    const val SPOKEN_EMPTY_TRANSCRIPT = "voice_spoken_empty_transcript"
    const val SPOKEN_TOO_LONG = "voice_spoken_too_long"
    const val SPOKEN_TOO_LARGE = "voice_spoken_too_large"
    const val SPOKEN_BROKEN_REQUEST = "voice_spoken_broken_request"
    const val SPOKEN_TEST_MESSAGE = "voice_spoken_test_message"

    // Inline field errors, checked before the save button is offered.
    const val ERROR_HOST_BLANK = "voice_error_host_blank"
    const val ERROR_HOST_SPACE = "voice_error_host_space"
    const val ERROR_PORT_BLANK = "voice_error_port_blank"
    const val ERROR_PORT_RANGE = "voice_error_port_range"
    const val ERROR_TOPIC_BLANK = "voice_error_topic_blank"
    const val ERROR_TOPIC_EDGE_SPACE = "voice_error_topic_edge_space"
    const val ERROR_TOPIC_EDGE_SLASH = "voice_error_topic_edge_slash"
    const val ERROR_TOPIC_EMPTY_LEVEL = "voice_error_topic_empty_level"
    const val ERROR_TOPIC_WILDCARD_FORBIDDEN = "voice_error_topic_wildcard_forbidden"
    const val ERROR_TOPIC_WILDCARD_MALFORMED = "voice_error_topic_wildcard_malformed"
    const val ERROR_TOPIC_SPACE = "voice_error_topic_space"

    // The connection's own failures, shown under the state line.
    const val CONNECTION_ENDED = "voice_connection_ended"
    const val CONNECTION_AUTH_REFUSED = "voice_connection_auth_refused"
    const val CONNECTION_NO_ANSWER = "voice_connection_no_answer"
    const val CONNECTION_BROKER_UNAVAILABLE = "voice_connection_broker_unavailable"
    const val CONNECTION_UNREACHABLE_CODE = "voice_connection_unreachable_code"
    const val CONNECTION_UNREACHABLE = "voice_connection_unreachable"

    /** Every key this feature ships, so the resource files and the lookup table can be held to it. */
    val all: Set<String> = setOf(
        SECTION_CONNECTION,
        STATUS_CONNECTED,
        STATUS_CONNECTING,
        STATUS_DISCONNECTED,
        HINT_CONNECTION_UNSET,
        TOGGLE_LOOP_TITLE,
        TOGGLE_LOOP_DESCRIPTION,
        FIELD_BROKER_ADDRESS,
        FIELD_PORT,
        TOGGLE_TLS_TITLE,
        TOGGLE_TLS_DESCRIPTION,
        TOGGLE_SELF_SIGNED_TITLE,
        TOGGLE_SELF_SIGNED_DESCRIPTION,
        SECTION_CREDENTIALS,
        FIELD_USERNAME,
        FIELD_PASSWORD,
        BUTTON_SHOW,
        BUTTON_HIDE,
        FIELD_PASSWORD_NOTE,
        SECTION_TOPICS,
        FIELD_INGRESS_TOPIC,
        HINT_INGRESS_TOPIC,
        FIELD_EGRESS_TOPIC,
        HINT_EGRESS_TOPIC,
        SECTION_SPEAKING,
        TOGGLE_SPEAK_REPLIES_TITLE,
        TOGGLE_SPEAK_REPLIES_DESCRIPTION,
        TOGGLE_SPEAK_UNSOLICITED_TITLE,
        TOGGLE_SPEAK_UNSOLICITED_DESCRIPTION,
        SECTION_CAPTURED,
        CAPTURE_BOUND,
        CAPTURE_EMPTY,
        CAPTURE_ENTRY,
        CAPTURE_MATCHED,
        CAPTURE_UNMATCHED,
        CAPTURE_NO_TOPIC,
        TOGGLE_HEADSET_ONLY_TITLE,
        TOGGLE_HEADSET_ONLY_DESCRIPTION,
        PICKER_DEVICE_LINE,
        PICKER_ANY_DEVICE,
        HINT_DEVICE_HEADSET_ONLY,
        HINT_DEVICES_PERMISSION,
        SPEECH_LAST_NOT_PLAYED,
        SECTION_RECORDINGS,
        TOGGLE_DISABLE_APP_REMINDERS_TITLE,
        TOGGLE_DISABLE_APP_REMINDERS_DESCRIPTION,
        SPEAKING_CHANNEL,
        SPEAKING_TITLE,
        SPEAKING_BODY,
        SPEAKING_STOP,
        BUTTON_SAVE_AND_RECONNECT,
        ACTION_TEST_SENT,
        BUTTON_SEND_TEST,
        FILING_APPENDED,
        FILING_NOT_LISTED,
        FILING_ARCHIVED,
        FILING_CHANGED_AFTER_RETRY,
        FILING_EMPTY_ANSWER,
        FILING_STORAGE_UNAVAILABLE,
        FILING_NO_NOTE_LINKED,
        FILING_NO_SINK,
        ENGINE_START_FAILED,
        ENGINE_INIT_FAILED,
        ENGINE_DEFAULT,
        ENGINE_DEFAULT_NAMED,
        DEVICE_UNNAMED,
        NOT_PLAYED_REPLIES_OFF,
        NOT_PLAYED_NO_BLUETOOTH_OUTPUT,
        NOT_PLAYED_DEVICE_NOT_CONNECTED,
        NOT_PLAYED_NO_OFFLINE_VOICE,
        NOT_PLAYED_NO_USABLE_VOICE,
        REFUSAL_NO_VOICES,
        REFUSAL_NETWORK_VOICES_ONLY,
        REFUSAL_NO_OFFLINE_VOICE_FOR_LANGUAGE,
        REFUSAL_NO_VOICE_FOR_LANGUAGE,
        REFUSAL_PINNED_VOICE_NETWORK_ONLY,
        REFUSAL_VOICE_NOT_ACCEPTED,
        SPOKEN_FAILURE,
        SPOKEN_TIMEOUT,
        SPOKEN_NOT_CONNECTED,
        SPOKEN_BUSY,
        SPOKEN_EMPTY_TRANSCRIPT,
        SPOKEN_TOO_LONG,
        SPOKEN_TOO_LARGE,
        SPOKEN_BROKEN_REQUEST,
        SPOKEN_TEST_MESSAGE,
        ERROR_HOST_BLANK,
        ERROR_HOST_SPACE,
        ERROR_PORT_BLANK,
        ERROR_PORT_RANGE,
        ERROR_TOPIC_BLANK,
        ERROR_TOPIC_EDGE_SPACE,
        ERROR_TOPIC_EDGE_SLASH,
        ERROR_TOPIC_EMPTY_LEVEL,
        ERROR_TOPIC_WILDCARD_FORBIDDEN,
        ERROR_TOPIC_WILDCARD_MALFORMED,
        ERROR_TOPIC_SPACE,
        CONNECTION_ENDED,
        CONNECTION_AUTH_REFUSED,
        CONNECTION_NO_ANSWER,
        CONNECTION_BROKER_UNAVAILABLE,
        CONNECTION_UNREACHABLE_CODE,
        CONNECTION_UNREACHABLE,
    )
}
