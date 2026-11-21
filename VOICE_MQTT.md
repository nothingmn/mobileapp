# The voice loop (MQTT in, speech out)

A Gravel-only feature, off unless a build asks for it. Every recording the Notebook transcribes is published
to an Agentry instance over MQTT; every answer that comes back is spoken through a Bluetooth headset. Nothing
is sent and nothing is spoken until the user turns it on in **Settings > Voice Assistant**.

This note is for a reviewer who has not read the wire protocol. The protocol itself is authoritative and lives
in the Agentry project (`agentry-voice-client-protocol.md`); where this note and that document disagree, the
document wins.

## The build flag

`VOICE_MQTT_ENABLED` is a BuildKonfig field in `voice-mqtt/build.gradle.kts`, the same mechanism `:util` uses
for `GOOGLE_AUTH_ENABLED` and the other auth flags. It defaults to **false**.

```
./gradlew :androidApp:assembleDebug                        # flag off (the default everywhere)
./gradlew :androidApp:assembleDebug -PvoiceMqttEnabled=true # flag on
```

It may also be set as `voiceMqttEnabled=true` in `local.properties`, or as the environment variable
`VOICE_MQTT_ENABLED=true`. With the flag off:

- `:voice-mqtt` is not a dependency of the app graph at all (`composeApp/build.gradle.kts` adds it only when the
  flag is on), and a no-op stand-in bridge is compiled in place of the real one — so no MQTT client, no speech
  engine, no settings screen implementation and none of their classes reach the APK. Verified by unzipping a
  flag-off APK's `classes*.dex`: zero occurrences of `com/anopticlabs/gravel/voicemqtt` and zero of
  `org/eclipse/paho`. `:pebble` still carries the plain settings interfaces and the layout of the Text to Speech
  page, because they are common code with no engine in them; with nothing bound the lookup returns null, the
  Settings item never enters the list and the route draws nothing;
- nothing binds the two seams, so `VoiceMqttSettings` and `VoiceTurnSink` have no implementation;
- the Settings entry is not built at all (`voiceMqtt?.available == true` is false, so the item never enters the
  list, and its section appears only where an item names it);
- the navigation route draws nothing and nothing navigates to it;
- the capture drain's two callbacks are the defaults (`{ null }` and `{ _, _, _ -> }`), so a capture runs down
  exactly the path it ran before this feature existed.

`VOICE_MQTT_ENABLED` is still inside the module as a BuildKonfig field, and the bridge checks it as well: with
the module on a classpath but the field false, the app registers an empty Koin module and starts nothing.

That is the shape an upstream PR ships in: inert unless someone asks for it.

## The two topics

| Direction | Default | Editable |
|---|---|---|
| publish a turn | `agentry/v1/gateway/req/voicessistant` | yes, one concrete topic (no `+` or `#`) |
| subscribe for answers | `agentry/v1/gateway/resp/voicessistant/#` | yes, wildcards expected |

Nothing else is published or subscribed: no event plane, no private webhook. The client's identity on this
broker comes from the topic route and nothing else — the broker is anonymous, and the payload is deliberately
incapable of carrying an identity.

The publish envelope has exactly five root keys and no more:

```json
{"version":1,
 "sessionId":"<uuid v4, lowercase>",
 "requestId":"<uuid v4, lowercase>",
 "text":"TURN ON THE GARAGE LIGHTS",
 "interaction":{"input":"speech_to_text","output":"text_to_speech","responseStyle":"voice_concise"}}
```

The text is the transcript the Notebook produced for that recording, unchanged, published before any text
model sees it. The three `interaction` literals are assertions about the device; the server validates them and
carries none of them forward.

## The turn, as implemented

- **Refusals are local and audible.** A blank transcript (already impossible upstream: the pipeline refuses a
  recording with no speech, and the loop refuses a blank string as a second gate), a text over 1024
  characters, or a payload over 8192 bytes never leaves the phone; the user hears why. Nothing is silently
  dropped.
- **One session until five idle minutes pass.** A `sessionId` is reused for a continuous conversation and
  minted fresh when the last turn is more than five minutes old, because the server's thread for a session
  resets under the client at that point.
- **A retry reuses the requestId.** A transport failure publishes the same `requestId`, `sessionId` and text
  again, with back-off, for as long as the server's ten-minute cached-answer window is open. Minting a new
  requestId would run the turn twice, which is the one mistake the protocol calls out by name.
- **Answers are paired on `requestId`, never on `correlationId`.** A correlation id is server-minted, one per
  session and reused across turns, so a message that matches nothing outstanding is dropped without being read
  for text. That also keeps another phone's traffic on the same broker out of this phone's speaker.
- **Every failure ends in a sentence.** A reply that does not arrive inside 60 seconds, an error reply, or a
  turn that cannot be published all produce a spoken line. The server's opaque error code is logged and never
  spoken. `busy` (the 256-turn in-flight cap) is not a failure to announce: the same requestId is published
  again after a back-off.
- **No echo-back.** The loop speaks only the server's reply text. It never re-speaks the transcript it just
  published, and never speaks the transcript as a confirmation.

## When a reply is spoken

Two independent switches, both on by default and both the user's to change, applied where the sound would come
out:

- **Speak the replies** — whether replies are spoken at all. Off means the loop never speaks: the reply still
  arrives and is still written into the note. That silence is the user's own choice.
- **Only play through a Bluetooth headset** — whether playback is restricted to Bluetooth. On, a Bluetooth
  audio output must be connected *at the moment of speaking*, checked with
  `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` filtered to `TYPE_BLUETOOTH_A2DP`, `TYPE_BLUETOOTH_SCO` and
  `TYPE_BLE_HEADSET`. A paired-but-switched-off headset is not an output, so the phone's speaker does not take
  its place: the reply is suppressed and the reason is recorded. Off, a reply plays through whatever output is
  active, the phone's speaker included.

| speak replies | headset only | a Bluetooth output is connected | what happens |
|---|---|---|---|
| on | on | yes | the reply is spoken |
| on | on | no | silent; the reason is logged and recorded |
| on | off | yes | the reply is spoken |
| on | off | no | the reply is spoken on the active output (the phone's speaker) |
| off | either | either | nothing is spoken |

The device picker narrows playback to one already-paired Bluetooth audio device (default: any of them), and it
is only consulted while headset only is on. Choosing a device does not make it speak when it is disconnected;
it only says which connected device is acceptable.

A suppressed reply is a recorded fact, not a silence with no trace: one log line naming the reason and one entry
in the speaker's run history, which the settings screen shows as "Last reply was not played: …". It is never
read aloud as an error — there is usually nowhere to say it.

An install from the first increment kept its behaviour across the change: that build stored a headset choice
and no switch (it forced the switch on in code), so such a file reads back as *speak replies, headset only,
that device*, and its old keys are removed on the next save.

Two further rules:

- **The microphone wins.** While the phone is recording, a reply waits. A newer line replaces an older one
  still waiting (they are stale answers), and a line still waiting after 30 seconds is dropped rather than
  queued behind a long recording. So: queued, newest only, dropped at the cap.
- **Audio focus is `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`**, taken when the utterance starts and given back when
  it finishes, so a reply never fights music or a podcast.

## The speaking notification, and its Stop button

While a reply is being spoken the phone shows an ongoing notification that says so, and carries one button:
**Stop**.

- **What it shows.** A title saying the reply is being spoken, and one line of the reply's own beginning —
  the first ~80 characters, collapsed to a single line and ellipsised where it was cut. The app's notification
  icon, on a low-importance channel named for speaking, silent, no timestamp, not dismissable while it is up.
- **When it appears.** When an utterance actually begins — after the switch, the headset check and the voice
  check have all allowed it, and just as the engine is handed the sentence. A reply that is held back (spoken
  replies off, no Bluetooth output with headset only on, no offline voice, no usable voice) never posts it, and
  never starts the service behind it. A reply that arrives while one is being spoken (the engine flushes the
  old line) re-posts the same notification with the newer beginning instead of stacking a second one beside it.
- **When it disappears.** When the utterance ends, in every sense: the engine saying it finished, the engine
  reporting an error, the engine being replaced (a change of engine or voice on the settings screen), the
  process dying, or the user pressing Stop. The removal is the service leaving the foreground with
  `STOP_FOREGROUND_REMOVE`, so the notification cannot outlive the sound.
- **What Stop does.** It aborts the utterance being spoken and clears anything queued behind it, gives the
  audio focus back, and takes the notification down with the service. It is safe to press at any moment,
  including when nothing is being spoken — that is a no-op. The reply is still filed into its note: stopping the
  sound does not undo what the answer did.

**Why a foreground service rather than a bare ongoing notification.** A plain ongoing notification is a
one-way post: nothing takes it down if the process is killed, the engine hangs up, or the speech path throws,
so the operator would be left with a "speaking" notice for a reply nobody is speaking. A short-lived foreground
service owns the notification instead, which means the framework itself removes it whenever the service ends —
including when the app is killed mid-sentence. The service holds no work of its own; the engine does the
speaking, and the service holds the notice and the Stop action.

The Stop action is an explicit `PendingIntent` back to that service (an action string, not a broadcast), which
is what makes it work at any point without the module registering a receiver dynamically; the service then
calls the speaker's `stop()`, aborts the utterance, clears the queue and removes the notice.

The service is declared `mediaPlayback`, unexported, with `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`. That type is the accurate one: this is audio the user asked for, playing
while the app is not on screen, and there is no work being processed (`mediaProcessing`) or device being talked
to (`connectedDevice`) behind it. It needs no runtime permission and no `MediaSession` — the notification has a
Stop and the app has no transport controls to publish. Android 17 requires a running foreground service, not of
type `shortService`, for an app that touches audio while it is not visible; without one the reply would be
silently suppressed on that release with the phone in a pocket.

Starting that service is **best effort**, and deliberately so: the reply arrives from the broker asynchronously,
so the service may be started while the app is in the background, which Android can refuse. A refusal is logged
and the reply is still spoken; only the notice and its Stop button are missing. The same is true if the user has
turned notifications off — the speech is unaffected.

**Nothing of this exists with the flag off.** The service, the notification and the permission that type costs
live in this module's manifest, and with `VOICE_MQTT_ENABLED` false the module is not in the app's dependency
graph at all, so none of it reaches the merged manifest: no service entry, no added permission, no channel, and
no code that could post a notification. That is checked at the artefact level — a flag-off APK's manifest has
no speaking service and its dex has no `com/anopticlabs/gravel/voicemqtt` reference — not inferred from the
flag.

## Reply filed into the note

The answer is spoken *and* kept. Once it has been spoken, the loop appends it into the note that recording
produced: the capture drain settles a recording as a note before the turn is published, so the loop already
holds `requestId → note` in memory when that requestId's answer arrives.

What lands in the note is a quote block, one blank line below the note's own text:

```
> Reply 3:47 PM
> The garage lights are on.
```

- **The stamp is the device's own.** The first line carries one word and the local time in the phone's short
  time format (`DateTimeFormatter.ofLocalizedTime(SHORT)`), never a hardcoded pattern, so it reads the way the
  phone's clock does. The word lives in one constant (`REPLY_QUOTE_LABEL`) for the localisation pass.
- **A multi-line answer is one quotation.** Every line of the answer continues as its own `> ` line, and a blank
  line inside it becomes a bare `>`, so the reader renders the whole answer as the single block it is.
- **Only the content changes.** The note's title, folder, filename and date are untouched; its modified time
  moves to the append, which is what puts the note at the top of its folder.

The write is **hash-guarded** (`NoteStore.editNote`, never the blind `updateNote`): the loop reads the note's
listed hash, appends to the content it read, and writes only if the note still carries that hash. If something
else rewrote the note in between, it re-reads and retries **once**; if that also loses, the append is dropped
and recorded rather than guessed at.

Two cases drop the append and leave the note exactly as it was, on purpose:

- **the note is gone** — deleted since the recording settled, or its id no longer resolves in the store;
- **the note is archived.**

Neither is resurrected: nothing is un-deleted, nothing is moved out of the archive, and no note is created. The
answer belongs to the note the recording made, or to nowhere. An answer with no words in it is refused before
the note is even read, so it never leaves a stamp with nothing under it.

**Speaking does not depend on any of this.** The sentence goes to the speaker first and the append is handed
off afterwards, so an answer is heard whether or not its note still exists or can be written; the append can
never suppress, delay or undo the speech. Each attempt leaves one entry in the loop's filing history — *filed
into "Garage"*, or the reason it was not — which the settings screen shows as the last line above the save
button.

## Speech output and privacy

The reply is spoken by the phone's own text-to-speech engine, and **which voice** that engine uses is decided
by one rule, in `VoiceSelection` (common Kotlin, proved by the host tests): the reply may only be spoken by a
voice that synthesises **on this phone**. Android marks such a voice `Voice.isNetworkConnectionRequired() ==
false`; the ones marked true send the text to the engine's cloud to be spoken, and are refused. There is no
fallback to a network voice and none to the engine's own default voice — the engine's default is exactly what
an unconfigured phone would use, which on this phone is Google's engine with network voices available.

Each utterance also asks the engine for **embedded synthesis**,
`TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS` with network synthesis declined, in the parameters passed
to `speak`. That hint is not the guarantee: the guarantee is the voice, because an engine that ignores the hint
still cannot send the text of a voice that needs no connection.

Everything about this lives on **Settings > AI > Text to Speech**, next to Speech Recognition and LLM. The
Voice Assistant page keeps the broker, the topics and the two speaking switches; it does not hold the engine.

- **Offline speech** (default **on**, the subtitle reads *Local only — voices that never send text to a
  server*): on means only an on-phone voice may speak, and embedded synthesis is requested with every
  utterance. Turned off, the engine's cloud voices are allowed, and the screen says so in one sentence under
  the switch: *replies are then synthesised by this engine's cloud service, so the text of every reply leaves
  this phone.* Even then a local voice is preferred (it is quicker and works on a plane), and a cloud voice is
  used only when no local one exists for the language.
- **Engine**: the installed engines, found with `TextToSpeech.getEngines()` — on this phone
  `com.google.android.tts` and `com.samsung.android.ptts.enus` — defaulting to the phone's own default engine.
  The choice is stored and used at the next utterance; changing it rebuilds the engine on the spot, so no app
  restart is needed, and the broker connection is untouched (none of these values is used by the transport).
- **Voice**: the voices the chosen engine offers, listed with the on-phone ones first. Only an on-phone voice
  can be picked while offline speech is on. Default is the best on-phone voice for the phone's language
  (a different region of the same language is acceptable; a different language is not).
- **What the screen says**: the voice in use, in words that state where the words become sound ("synthesised on
  this phone" or "synthesised by the engine's cloud service"); when a pinned voice is gone, that too; and when
  nothing can speak, the reason.

**When there is no offline voice.** With offline speech on and no on-phone voice for the language, nothing is
spoken and the phone does not quietly pick a network voice. The screen names the problem in plain words —
*No offline voice is installed for English — download one in Android's text-to-speech settings* — and each
reply that could not be spoken is recorded the same way a headset suppression is: one log line and one
`VoiceSpeechHistory` entry (`NO_OFFLINE_VOICE`), which the Voice Assistant page shows as "Last reply was not
played: …". With offline speech off, a cloud voice is used if the engine has one for the language; if it has
none at all, that is recorded with its own reason (`NO_USABLE_VOICE`) rather than left as a silence.

**Verifying it yourself, in airplane mode.** Switch airplane mode on and press **Send test message** on the
Voice Assistant page. The turn cannot be published, so the loop speaks its failure sentence — and that
sentence, spoken with no network at all, proves the engine is synthesising on the phone. Nothing it said
could have reached a server, because there was no connection to reach one. A phone with no offline voice shows
the same failure as a recorded suppression and the reason on screen instead of a voice.

**An install from the previous increment** keeps both switches and its pinned headset exactly as they were,
and reads back with offline speech **on**, the phone's default engine and the best on-phone voice for the
language — the stricter of the two choices, not the looser one. Its engine and voice keys did not exist, so
nothing is inherited and nothing is guessed.

## The settings screen

Settings > Voice Assistant holds every value the client uses at connect time, all persisted, all editable:
broker address, port, TLS on/off, an explicit opt-in to trust a self-signed certificate (default off), username,
password, the request topic and the reply filter. It shows connected/disconnected with the last failure in
words, so a wrong host is diagnosable from the phone, and saving reconnects on the spot — no app restart.

- **TLS off** connects plainly to the port given (`tcp://host:port`). **TLS on** uses `ssl://` with the platform's
  own trust anchors; only the opt-in accepts a self-signed certificate, and it says so on the switch.
- **Topics are validated at save time**, inline: the request topic must be one concrete topic, the reply filter
  may carry a wildcard in the right place. A typo is a field error, not a client that silently hears nothing.
- **The password is never written in the clear.** It is sealed with AES/GCM/256 by a key generated in the
  Android Keystore (alias `gravel_voice_mqtt_secret`, non-exportable) and stored as `base64(iv):base64(cipher)`
  in the app's private preferences file; a keystore that has been invalidated empties the field rather than
  storing plaintext. `VoiceSettings.toString()` redacts both credentials, no logging call in the module logs a
  setting value at all, so neither logcat nor a crash report nor the run history can carry the password. (This
  is the `EncryptedSharedPreferences` job done on the platform Keystore directly, because `androidx.security-crypto`
  is deprecated upstream and would be a new dependency for a dozen lines.)
- **The test button** publishes a fixed sentence and speaks whatever comes back, which is the whole loop without
  needing to record anything.

## Library choice, and what it costs elsewhere

**Eclipse Paho, MQTT v3 client** (`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`). It is small, on
Maven Central, has no Android `Service` or background component to babysit, speaks MQTT 3.1.1 (what the broker
offers), and brings no Google Play Services or Firebase anywhere in its graph — which is the fork's whole point.
HiveMQ's client would also work but brings Netty for one socket.

**The KMP consequence:** both candidates are JVM libraries, so `:voice-mqtt` has an Android target only and
nothing here compiles for iOS. Any module with an iOS target must therefore not depend on it from common code —
which is why `:pebble` holds only an interface (`VoiceMqttSettings`) and looks it up with `getOrNull`, and why
the app graph in `composeApp` is the only place the module is touched.

QoS and session: publishes at **QoS 1**, subscribes at **QoS 1**, on a **persistent session**
(`cleanSession = false`, with a client id minted once and kept). The broker therefore holds this phone's
subscription across a brief reconnect instead of losing a reply published during the gap. A QoS 1 duplicate is
harmless: a second answer to an answered requestId is dropped unread.

## Notes for the next increment

When a capture settles as a note, the drain hands the turn's `requestId` and that note's id and title to the
loop, which keeps the association in memory (`VoiceMqttController.rememberNote`) until that requestId's answer
is filed into the note and the link is dropped.

Confirmation prompts (the protocol's mid-turn human-in-the-loop) are deliberately **not** implemented; the
protocol says not to build them into v1.

## Localisation

The feature's own words follow the phone's language. Two mechanisms, because the two modules are not the same
kind of module, and the tests hold both to the same rules.

- **`:voice-mqtt` — Android string resources**, `src/androidMain/res/values/strings.xml` and
  `values-fr/strings.xml`, read through a `Context` by `AndroidVoiceText`. Android resources rather than Compose
  resources because half the copy is not drawn: the sentences the speaker says
  (`VoiceLoopSentences`), a refusal's reason, a suppression's reason, and the connection's failure line are all
  resolved from a coroutine with no `Composable` anywhere near them, and `Context.getString` is the lookup that
  works in both places. The rules that decide *what* to say stay pure Kotlin (`PlaybackPolicy`, `VoiceSelection`,
  `VoiceTopics`, `VoiceSettings`) and hand back a `VoiceCopy` — a resource key plus its arguments — so a rule can
  never hold an English sentence. `VoiceKeys` names every key; `AndroidVoiceText` maps each one to its
  `R.string`.
- **`:pebble` — Compose Multiplatform string resources**, `src/commonMain/composeResources/values/strings.xml`
  and `values-fr/strings.xml`, resolved with `stringResource`. This module has iOS targets, so Compose resources
  are the mechanism it already ships (the app's own strings live in the same file); `:pebble` cannot depend on
  the Android-only module for anything. The three sentences the Text to Speech screen assembles from a fact
  (`cloudSpeechNote`, `speechInUseLine`, `pinnedVoiceNote`) hand back a `SpeechCopy` and are resolved by the
  screen, and the AI section's item is resolved in `rememberSettingsItemsState` because the item list is built
  inside a `remember`.

**Locales shipped:** English (`values/`) and French (`values-fr/`), 87 keys in the voice module and 21 in the
Text to Speech screen, every one prefixed `voice_` so the feature's copy reads as one block in a review.
French is standard French with Canadian vocabulary where the word genuinely differs — *jumelé* for a paired
headset, *infonuagique* for a cloud service, *téléphone* throughout — and keeps the technical terms French has
no settled word for: **broker**, **Bluetooth**, **TLS**, **Keystore**, **MQTT**, **joker** for a topic wildcard,
**sujet** for a topic. No string in either module embeds a count, so there are no plural resources; if one
appears, it gets `<plurals>` (Android) and a quantity string (Compose) rather than concatenation.

**Adding a language:** copy `values/strings.xml` to `values-<tag>/strings.xml` (`values-fr` is the worked
example) in the module concerned and translate every key. Nothing else is needed: the platform picks the file
from the phone's language, and a test fails the build if a key is missing or if a translation drops a format
argument.

**What is deliberately still English:**

- **The rest of the app.** Upstream's UI is English-only and out of scope for this feature; in `:pebble`'s
  `values-fr` only the `voice_*` keys are translated and everything else falls back to `values/`, which a test
  asserts. In `:voice-mqtt`'s resources only this feature's keys exist at all.
- **Developer text**: log tags and log lines (`PlaybackPolicy.logLine`, `VoiceSelection.logLine`, every
  `logger.*` call), MQTT topics and the other protocol constants, the redacted `VoiceSettings.toString()`, and
  the `spokenWhere` phrase that only ever reaches a log line. A scan test fails on any user-facing English
  sentence left in the Kotlin, with exactly these on its allowlist.
- **Upstream's settings plumbing this feature passes through**: the `Section` enum's names (`Voice Assistant`,
  `AI`, `Text to Speech` as a section title), the top bar title set in `composeApp`'s `AndroidVoiceMqtt`, and the
  Voice Assistant item's own title and description in the settings list. Those are upstream strings in upstream
  files; localising them here would restructure code this change does not own.

**The tests that enforce it** (both run in `testAndroidHostTest`, no phone needed): `VoiceCopyKeysTest` and
`TextToSpeechCopyKeysTest` compare the two locale files key for key, hold the code's key set to the files, check
that each translation keeps the same format arguments, assert that only this feature's keys are translated in
`:pebble`, and scan the Kotlin that draws or says the copy for a leftover English sentence.

## Running the tests

```
./gradlew :voice-mqtt:testAndroidHostTest
```

89 tests, no device and no broker needed: the wire contract (five root keys, three frozen literals, UUID v4,
1024-character and 8192-byte refusals), the five-minute session reset, the same-requestId retry inside ten
minutes, reply pairing on `requestId` (a mismatched or empty one is ignored), and the turn as a whole against a
fake transport (a retry carries the same requestId, a timeout is spoken, a blank transcript is refused with a
sentence, an error reply never speaks its code). The speaking matrix, the pinned device, the legacy-value
migration and the paired-but-disconnected case are pure-Kotlin tests over the same task, and so is the voice
rule: a network-only engine is refused, one on-phone voice among network ones is chosen, a language with no
on-phone voice gives the named reason, a pinned network voice is refused while offline speech is on, and a
preferences file from the previous increment keeps both switches and its headset and reads back offline-only.

The module's own test task is enough; nothing in it needs `adb`. Fifteen of those tests are the speaking
notification — it appears on the first utterance and never on a suppressed one, a second utterance updates the
one notice rather than stacking another, and finishing, Stop and an engine failure each end in its removal,
with Stop when nothing is speaking a no-op — plus the module manifest that declares the service and the
permission its type costs. Seven of the localisation enforcement tests described above are the remainder of
that count.
