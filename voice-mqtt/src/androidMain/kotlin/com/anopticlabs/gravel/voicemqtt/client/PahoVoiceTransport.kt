package com.anopticlabs.gravel.voicemqtt.client

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.voicemqtt.copy.VoiceCopy
import com.anopticlabs.gravel.voicemqtt.copy.VoiceKeys
import com.anopticlabs.gravel.voicemqtt.copy.VoiceText
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The MQTT transport, on Eclipse Paho's MQTT v3 client.
 *
 * Why this library: it is a small plain-JVM client on Maven Central, with no Android Service, no background
 * component and no GMS/Firebase anywhere in its graph (the fork's whole point), and it speaks MQTT 3.1.1,
 * which is what the broker on 1883 offers. HiveMQ's client would work too but brings Netty, several megabytes
 * of extra machinery for one socket. The KMP consequence of either choice is the same and is not negotiable:
 * both are JVM libraries, so this module has an Android target only and no module with an iOS target may
 * depend on it from common code.
 *
 * QoS and session choice, which the operator settled from an earlier working client on this route:
 * publish at QoS 1 and subscribe at QoS 1, on a **persistent session** (`cleanSession = false` with a clientId
 * kept across app starts), so a reply published while the connection blips is delivered when it comes back
 * instead of being lost with the subscription. QoS 1 can duplicate a delivery; that is harmless here because
 * replies are paired on requestId and a duplicate is dropped unread.
 */
class PahoVoiceTransport(
    private val clientId: String,
    private val stateFlow: MutableStateFlow<ConnectionState>,
    /** The phone's copy: the failure reasons below are shown under the state line, in the phone's language. */
    private val text: VoiceText,
    private val publishTimeoutMillis: Long = 10_000L,
) : VoiceTransport {

    private val lock = Mutex()
    private var client: MqttAsyncClient? = null

    @Volatile
    private var listener: ((payload: ByteArray) -> Unit)? = null

    /** The filter the current session is subscribed to, re-subscribed on every reconnect. */
    @Volatile
    private var egressFilter: String? = null

    val state: StateFlow<ConnectionState> = stateFlow.asStateFlow()

    override fun setIncoming(listener: (payload: ByteArray) -> Unit) {
        this.listener = listener
    }

    override suspend fun connect(settings: VoiceSettings): ConnectionState = withContext(Dispatchers.IO) {
        lock.withLock {
            closeLocked()
            egressFilter = settings.egressTopic
            stateFlow.value = ConnectionState(ConnectionPhase.CONNECTING)
            try {
                val created = MqttAsyncClient(settings.serverUri, clientId, MemoryPersistence())
                created.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverUri: String?) {
                        // automaticReconnect brings the socket back; the subscription is ours to restore.
                        subscribe(created)
                        stateFlow.value = ConnectionState(ConnectionPhase.CONNECTED)
                        logger.i { "voice mqtt connected (reconnect=$reconnect)" }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        stateFlow.value = ConnectionState(ConnectionPhase.DISCONNECTED, describe(cause))
                        logger.w { "voice mqtt connection lost: ${cause?.message.orEmpty().take(120)}" }
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.payload ?: return
                        listener?.invoke(payload)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
                })
                client = created
                created.connect(connectOptions(settings))?.waitForCompletion(CONNECT_TIMEOUT_MILLIS)
                subscribe(created)
                stateFlow.value = ConnectionState(ConnectionPhase.CONNECTED)
            } catch (e: Exception) {
                // The message a wrong host or a refused connection produces, in words the user can act on.
                stateFlow.value = ConnectionState(ConnectionPhase.DISCONNECTED, describe(e))
                logger.w { "voice mqtt connect failed: ${e::class.simpleName} ${describe(e)}" }
            }
            stateFlow.value
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        lock.withLock { closeLocked() }
        stateFlow.value = ConnectionState(ConnectionPhase.DISCONNECTED)
    }

    override suspend fun publish(topic: String, payload: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val current = client
        if (current == null || !current.isConnected) return@withContext false
        try {
            val message = MqttMessage(payload).apply { qos = QOS }
            current.publish(topic, message)?.waitForCompletion(publishTimeoutMillis)
            true
        } catch (e: Exception) {
            logger.w { "voice mqtt publish refused: ${e::class.simpleName}" }
            false
        }
    }

    private fun subscribe(client: MqttAsyncClient) {
        val filter = egressFilter ?: return
        if (!client.isConnected) return
        try {
            client.subscribe(filter, QOS)?.waitForCompletion(CONNECT_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            logger.w { "voice mqtt subscribe refused: ${e::class.simpleName}" }
        }
    }

    private fun closeLocked() {
        val current = client ?: return
        client = null
        try {
            if (current.isConnected) current.disconnect()?.waitForCompletion(CONNECT_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            logger.w { "voice mqtt disconnect failed: ${e::class.simpleName}" }
        }
        try {
            current.close()
        } catch (e: Exception) {
            logger.w { "voice mqtt client close failed: ${e::class.simpleName}" }
        }
    }

    private fun connectOptions(settings: VoiceSettings): MqttConnectOptions = MqttConnectOptions().apply {
        // Persistent session: the broker keeps this phone's subscription across a brief reconnect.
        isCleanSession = false
        isAutomaticReconnect = true
        keepAliveInterval = 30
        connectionTimeout = 10
        maxInflight = 10
        mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
        if (settings.username.isNotEmpty()) userName = settings.username
        if (settings.password.isNotEmpty()) password = settings.password.toCharArray()
        if (settings.tls) {
            socketFactory = socketFactory(settings.trustSelfSigned)
            if (settings.trustSelfSigned) {
                // Only with the user's explicit opt-in: a self-signed broker cannot pass hostname checks.
                isHttpsHostnameVerificationEnabled = false
                sslHostnameVerifier = HostnameVerifier { _, _ -> true }
                logger.w { "voice mqtt: self-signed broker accepted on the user's word" }
            }
        }
    }

    /** Validating by default: the platform's own trust anchors. Only the opt-in trusts everything. */
    private fun socketFactory(trustSelfSigned: Boolean): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        if (!trustSelfSigned) {
            context.init(null, null, SecureRandom())
            return context.socketFactory
        }
        val trustAny = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        context.init(null, trustAny, SecureRandom())
        return context.socketFactory
    }

    /** Never includes a credential: the settings' host and port, and the failure's own reason. */
    private fun describe(e: Throwable?): String = when (e) {
        null -> text.of(VoiceCopy(VoiceKeys.CONNECTION_ENDED))
        is MqttException -> when (e.reasonCode) {
            MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),
            MqttException.REASON_CODE_NOT_AUTHORIZED.toInt(),
            -> text.of(VoiceCopy(VoiceKeys.CONNECTION_AUTH_REFUSED))
            MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt() ->
                text.of(VoiceCopy(VoiceKeys.CONNECTION_NO_ANSWER))
            MqttException.REASON_CODE_BROKER_UNAVAILABLE.toInt() ->
                text.of(VoiceCopy(VoiceKeys.CONNECTION_BROKER_UNAVAILABLE))
            else -> text.of(VoiceCopy(VoiceKeys.CONNECTION_UNREACHABLE_CODE, e.reasonCode.toString()))
        }
        // The library's own message is developer text, so it passes through; only the fallback is ours.
        else -> e.message?.take(160) ?: text.of(VoiceCopy(VoiceKeys.CONNECTION_UNREACHABLE))
    }

    private companion object {
        val logger = Logger.withTag("PahoVoiceTransport")
        const val QOS = 1
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
    }
}
