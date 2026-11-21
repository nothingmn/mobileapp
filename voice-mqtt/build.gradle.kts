import com.codingfeline.buildkonfig.compiler.FieldSpec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Gravel module: the Agentry voice loop (MQTT in, speech out). Android only, on purpose: the two MQTT
// clients a phone can carry are JVM libraries (Eclipse Paho / HiveMQ), so nothing here compiles for iOS,
// and no module that has an iOS target may depend on it from common code. See VOICE_MQTT.md.
//
// VOICE_MQTT_ENABLED defaults to false, the same BuildKonfig mechanism :util uses for the auth flags, so
// an upstream PR ships inert: with the flag off nothing binds the client, no Settings entry appears and no
// connection is ever made.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.buildKonfig)
}

val properties = Properties().apply {
    try {
        load(rootDir.resolve("local.properties").reader())
    } catch (e: Exception) {
        println("local.properties file not found")
    }
}

// Set with -PvoiceMqttEnabled=true, voiceMqttEnabled=true in local.properties, or GRADLE_PROPERTIES'
// voiceMqttEnabled. Absent means off, which is what an upstream PR must ship.
val voiceMqttEnabled = (
    properties.getProperty("voiceMqttEnabled")
        ?: providers.gradleProperty("voiceMqttEnabled").orNull
        ?: System.getenv("VOICE_MQTT_ENABLED")
    )?.trim()?.equals("true", ignoreCase = true) ?: false

kotlin {
    android {
        namespace = "com.anopticlabs.gravel.voicemqtt"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        androidResources {
            enable = true
        }

        withHostTestBuilder {}
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("kotlin.time.ExperimentalTime")
                optIn("androidx.compose.material3.ExperimentalMaterial3Api")
            }
        }
        commonMain.dependencies {
            implementation(libs.coroutines)
            implementation(libs.serialization)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            // The notebook's reply seam: this module files an answer into the note the recording produced, and
            // takes only the notebook's interface, never its storage. The dependency runs one way on purpose —
            // :notebook must never learn that MQTT exists.
            implementation(project(":notebook"))
            // The notification icon every other Gravel notification uses, so the speaking notice looks like the
            // rest of the app's rather than like a launcher icon squashed into the status bar.
            implementation(project(":util"))
            // Eclipse Paho's MQTT v3 client: plain JVM, Maven Central, no Android Service and no GMS.
            implementation(libs.paho.mqttv3)
        }
    }
}

buildkonfig {
    packageName = "com.anopticlabs.gravel.voicemqtt"
    objectName = "VoiceMqttBuildKonfig"
    exposeObjectWithName = "VoiceMqttBuildKonfig"

    defaultConfigs {
        buildConfigField(FieldSpec.Type.BOOLEAN, "VOICE_MQTT_ENABLED", voiceMqttEnabled.toString())
    }
}
