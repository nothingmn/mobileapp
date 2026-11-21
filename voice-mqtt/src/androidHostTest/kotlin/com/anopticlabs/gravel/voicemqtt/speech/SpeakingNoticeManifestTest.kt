package com.anopticlabs.gravel.voicemqtt.speech

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The manifest the speaking service needs, checked as the file the build packages.
 *
 * A host test cannot read a merged manifest (that needs the packaging step), but the module's own manifest is
 * the half this module owns: the service entry, its type, and the permission that type costs. A build with this
 * module on the classpath merges exactly this file; a build without it merges none of it, which is what the
 * flag-off APK's manifest dump shows.
 */
class SpeakingNoticeManifestTest {

    private val manifest: String = File(moduleDir(), "src/androidMain/AndroidManifest.xml").readText()

    private fun moduleDir(): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "src/androidMain/AndroidManifest.xml").isFile }
            ?: error("could not find the voice-mqtt module from $start")
    }

    @Test
    fun theSpeakingServiceIsDeclaredUnexportedAndAsMediaPlayback() {
        val entry = Regex("""<service\s[^>]*android:name="com\.anopticlabs\.gravel\.voicemqtt\.speech\.SpeakingNoticeService"[^>]*/?>""")
            .find(manifest)
            ?.value
            ?: error("the speaking service is not declared in the module manifest")
        assertTrue(entry.contains("""android:exported="false""""), "only this app starts it: $entry")
        assertTrue(entry.contains("""android:foregroundServiceType="mediaPlayback""""), "the type is what allows it: $entry")
    }

    @Test
    fun thePermissionThatTypeCostsIsDeclared() {
        // The base permission is the app's already; the type-specific one belongs to this module, so it exists
        // only in a build that includes the module.
        assertTrue(manifest.contains("""android:name="android.permission.FOREGROUND_SERVICE""""))
        assertTrue(manifest.contains("""android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK""""))
    }
}
