package com.anopticlabs.gravel.voicemqtt.settings

import com.anopticlabs.gravel.voicemqtt.VoiceMqttBuildKonfig

/**
 * What this build baked in, read from the BuildKonfig fields `voice-mqtt/build.gradle.kts` fills from
 * `gradle.properties` or from `-P...` on the command line.
 *
 * The repository ships every one of those keys blank, so the answer here is [BakedVoiceDefaults.NONE] and the
 * settings screen starts with an empty address: the user's own broker is the only one this app will use. A
 * builder who supplies values gets them pre-filled as a starting point, and a value already stored on the phone
 * still wins over them.
 */
fun bakedVoiceDefaults(): BakedVoiceDefaults = BakedVoiceDefaults(
    brokerHost = VoiceMqttBuildKonfig.VOICE_MQTT_BROKER_HOST,
    brokerPort = VoiceMqttBuildKonfig.VOICE_MQTT_BROKER_PORT,
    tls = VoiceMqttBuildKonfig.VOICE_MQTT_TLS,
    requestTopic = VoiceMqttBuildKonfig.VOICE_MQTT_REQUEST_TOPIC,
    responseTopic = VoiceMqttBuildKonfig.VOICE_MQTT_RESPONSE_TOPIC,
)
