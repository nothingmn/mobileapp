package com.anopticlabs.gravel.notebook.pipeline

import org.koin.core.context.GlobalContext

/**
 * Gravel: the seam the voice loop's "Disable app reminders" switch closes, so :notebook keeps no dependency on
 * the MQTT module and a build without the loop behaves exactly as it did before the switch existed.
 *
 * The app binds an implementation into Koin only when `VOICE_MQTT_ENABLED` is on. The publisher asks this at the
 * moment it decides what a capture becomes; with nothing bound the answer is "reminders are allowed", which is
 * today's behaviour.
 */
interface AppReminderGate {
    /**
     * False when the user has asked the app not to create reminders of its own: a capture that would have been
     * one is kept as a note instead, and nothing is scheduled, armed or stored as a reminder.
     */
    val remindersAllowed: Boolean
}

/**
 * The gate the app bound, or null when the voice loop is off — the same `getOrNull` seam the capture drain uses
 * for [VoiceTurnSink], so an unbound gate is an ordinary state rather than a startup failure.
 */
fun appReminderGateOrNull(): AppReminderGate? = GlobalContext.getOrNull()?.let { koin -> koin.getOrNull<AppReminderGate>() }

/** Whether a capture may become a reminder: only a bound gate that says no changes it. */
fun appRemindersAllowed(): Boolean = appReminderGateOrNull()?.remindersAllowed ?: true
