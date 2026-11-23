# Staying mergeable with upstream

This repository is a fork. Upstream (`david-cant-code/pebble-app-degoogled`) keeps shipping, and this fork keeps a
feature set upstream does not have: the Agentry voice loop, its speaking notice and reply filing, the "Disable app
reminders" switch, the flood-gates capture behaviour, English and French copy, and the ring button as a media
remote. The two lines of work have to keep merging without a rewrite every time.

This file is for whoever does the next sync, six months from now, with none of this session's context. It says what
makes the merge cheap, how to do it, how to read the report that says what will collide, and what to do when
upstream rewrites something this fork hooks into.

The mechanical parts are in `scripts/upstream-sync.sh`. Nothing here needs the operator's phone.

---

## 1. The invariants — why this branch merges at all

Each of these is a property of the fork, not a preference. Break one and the next sync costs days instead of an hour.
Each says how to check it, so a change that would break it is caught while it is being written.

### One leaf module for the voice feature

`:voice-mqtt` holds the loop: transport, protocol, session, speech, settings, copy. It depends on nothing in this
repository except libraries, and **nothing it touches imports it from common code**. `:pebble`, `:notebook` and
`:composeApp`'s common code do not depend on it, because `:pebble` builds for iOS and the MQTT clients are JVM
libraries. Its Android entry points live in `composeApp/src/{voiceMqttEnabled,voiceMqttDisabled}`.

*Check:* `grep -rn 'project(":voice-mqtt")' --include='*.kts' .` returns only `composeApp/build.gradle.kts`, and only
inside the flag-on branch.

*Why it matters here:* upstream edits `:pebble`, `:notebook` and `:composeApp` constantly. A feature that lives in a
leaf module upstream has never heard of cannot conflict with their edits; a feature that lives inside their files
conflicts every release.

### Seams are interfaces plus `getOrNull`, never a dependency

Common code asks for what it needs and treats a missing binding as "this build has not got the feature":

| Where | Seam | Read as |
|---|---|---|
| `:pebble` `com.anopticlabs.gravel.ui` | `VoiceMqttSettings` / `rememberVoiceMqttSettings()` | null when nothing is bound |
| `:pebble` `com.anopticlabs.gravel.ui` | `TextToSpeechSettings` / `rememberTextToSpeechSettings()` | null when nothing is bound |
| `:pebble` `com.anopticlabs.gravel.ui` | `RingButtonSettings` / `rememberRingButtonSettings()` | null when nothing is bound |
| `:notebook` `…notebook.pipeline` | `VoiceTurnSink`, `NoteReplySink`, `AppReminderGate` | `getKoin().getOrNull<…>()?.…` |
| `:composeApp` | `AndroidVoiceMqtt` in two source sets | the disabled one binds nothing |

A seam carries **plain data** across it (strings, booleans, `StateFlow` of a data class), never a type from
`:voice-mqtt` or `:ring`. That is what lets `:pebble` keep its iOS targets and what keeps an upstream refactor of
their own UI from dragging this fork's types along.

*Check:* any `import com.anopticlabs.gravel.voicemqtt.…` under `pebble/`, `notebook/` or `composeApp/src/commonMain`
is a defect; so is any `get<…>()` where the feature is optional (it must be `getOrNull`, or a flag-off build dies at
startup with a Koin resolution error).

*Why it matters here:* the null is also the feature switch. It is why a flag-off build has **no route** to the
voice page and **no entry** in Settings, rather than a hidden entry behind a false boolean.

### One build flag, and it is off

`VOICE_MQTT_ENABLED`, a BuildKonfig value in `:voice-mqtt` and `:composeApp`, read from `local.properties`, then a
Gradle property (`-PvoiceMqttEnabled=true`), then the environment. Absent means off. `:composeApp` uses the same
boolean to choose its source set, so the flag-off build does not merely not-take the branch — the bridge class
holding the branch is not compiled.

*Check:* `./gradlew :androidApp:assembleDebug` (no property) and then verify the dex holds no
`com/anopticlabs/gravel/voicemqtt` — the script does this, see §4.

*Why it matters here:* a flag-off build is what an upstream PR ships, and it is the thing that proves the fork's
change is inert. One flag also means one place to read the state; two flags (a build one and a runtime one) is how a
"disabled" feature keeps a service running.

### Build config defaults are blank

The broker, port, TLS, credentials and topics are configured in the UI. The Gradle properties that can bake a
default are blank in the repository (the same `?.takeIf { it.isNotBlank() }` pattern upstream uses for its auth
flags), so a blank value means "no default" rather than an empty string, and the operator's own LAN never lands in a
tracked file.

*Check:* `grep -n 'voice' gradle.properties` shows `voiceMqttEnabled=false` and the five `voiceMqtt*` keys with
nothing after the `=`. A value there is a fact about the machine that made the build, so none belongs in the
repository; `VoiceConnectionDefaultsTest` holds the resolution order (stored value, then baked default, then empty).

### No Firebase, no Supabase, ever

The fork exists for this. `:experimental` is unplugged in `settings.gradle.kts`; `:firebase-stubs` stands in for the
gitlive artifacts; `FdroidGuardrailsTest` and `AppClasspathSentinelTest` fail the build if an artifact or a
repository comes back. A voice or ring change must not add a dependency that drags either in.

*Check:* `./gradlew :androidApp:testDebugUnitTest` runs both sentinels on the host.

### The ring button is independent of the voice flag

The ring's button (`:ring` `RingButtonClassifier`/`RingButtonController`, bound in
`composeApp/…/RingClientLibIndex.kt`) needs no broker and no Notebook, so it is bound unconditionally and its
settings live behind its own switch, off by default. It is reachable in the shipped default build.

*Check:* `./gradlew :ring:testAndroidHostTest` and the flag-off APK: there is a Settings > General > Ring button
entry in it, and no voice entry.

*Why it matters here:* it is the one feature of this fork that a flag-off build carries. If a future sync ties it to
`VOICE_MQTT_ENABLED`, the operator loses media control in the build he actually installs.

### The touch points on upstream are few, and each is marked

A fork comment starting `// Gravel:` or `// Fork:` marks a line where this fork edits upstream's own file. Those
markers are the conflict surface, listed by the script before it merges anything. Keep them few, keep them one line
where possible, and never reformat around them (a whitespace change turns a one-line conflict into a whole-file
one).

---

## 2. The sync procedure

One branch, one worktree, one commit per upstream release. Do not sync on the branch the operator's phone runs.

1. **Fetch and measure, before anything moves.**
   ```sh
   scripts/upstream-sync.sh --report
   ```
   This prints how far behind and ahead the branch is, the commits each side has, and the files where both changed
   — the conflict surface. Read that list: it is the whole cost of the sync. If a file there is not one you expect,
   stop and find out why before merging.

2. **Work in a scratch worktree created from upstream, not from the fork.** A worktree cannot check out a branch
   that is already checked out elsewhere, which is the safety that keeps the operator's branch out of reach.
   ```sh
   git worktree add /path/to/scratch-sync -b upstream-sync-YYYY-MM-DD origin/master
   ```

3. **Replay the fork's commits onto it, in order.** `git cherry-pick <base>..<fork-tip>` is the tool, chosen over
   `git rebase` because rebase wants the fork branch itself checked out and this fork's branch is the one on the
   operator's phone. Same commits, same order, no chance of moving the live branch.
   ```sh
   cd /path/to/scratch-sync
   git cherry-pick e4284b02..290e6a65   # base..fork-tip, the commits this fork adds
   ```
   Or `scripts/upstream-sync.sh --rebase`, which does steps 2–3 and stops at the first conflict.

4. **Resolve each conflict on its merits.** Never `-X ours`, never `-X theirs`, never `git checkout --theirs .`:
   upstream's accessibility work and this fork's features are both wanted, and a wholesale pick silently deletes one
   of them. For each conflicted file:
   - read both sides' intent from the two commit messages and the surrounding code;
   - keep upstream's *new convention* (a helper, a theme colour, a wrapping rule) and re-express the fork's change
     through it, rather than keeping the fork's older spelling of the same idea;
   - where both added a section to a document, keep both, in the order a reader would expect (a feature section
     beside the feature's own sections, a general rule near the other general rules);
   - `git add` the file, `git cherry-pick --continue`.

5. **Prove it.** From the merged worktree, all of:
   ```sh
   ./gradlew :voice-mqtt:testAndroidHostTest :ring:testAndroidHostTest :notebook:testAndroidHostTest \
             :pebble:testAndroidHostTest :composeApp:testAndroidHostTest --rerun-tasks
   ./gradlew :androidApp:testDebugUnitTest --rerun-tasks      # FixedColorSentinelTest and the guardrail sentinels
   ./gradlew :androidApp:assembleDebug                        # flag off
   ./gradlew :androidApp:assembleDebug -PvoiceMqttEnabled=true # flag on
   ```
   Then the two artefact checks by hand (§4): the flag-off dex holds no `com/anopticlabs/gravel/voicemqtt`, and both
   APKs are listed with their sizes. `--rerun-tasks` is not optional on a sync: a cached green from before the merge
   is not evidence about the merge.

   A fresh worktree has **no submodule contents**. `git submodule update --init --recursive` first, or roughly
   twenty-five source-scanning tests fail with "Submodule … is not checked out, so its tree cannot be scanned" and it
   reads like a regression in the merge.

6. **Record the result** — the worktree branch, its final commit, the conflict list with how each was resolved, the
   test numbers, both build results — in the release record (§5 of the repository's `AGENTS.md`). A sync that is not
   recorded is a sync the next person repeats.

7. **Leave the live branch alone.** `git -C <main checkout> branch --show-current` still names the operator's
   branch, and its tree is untouched. The merged worktree branch is the deliverable; pushing it and moving the phone
   are the operator's call, made separately.

---

## 3. Reading the conflict report

`scripts/upstream-sync.sh --report` prints, in this order:

```
branch:   feature/mqtt-voice at 290e6a65 (17 commits above e4284b02)
upstream: origin/master at b912d171 (12 commits above e4284b02)
behind 12, ahead 17; merge base e4284b02
conflict surface (2 files changed by both):
  DESIGN_NOTES.md
  pebble/.../ui/WatchSettingsScreen.kt
```

- **behind / ahead** are counted against the merge base, not against each other, so both numbers stay meaningful
  after the first sync (a re-run shows `behind 0, ahead 29` once the merged branch is in place).
- **The conflict surface** is `comm -12` of the two `git diff --name-only <base> <side>` lists. It is computed without
  touching a working tree, so it is safe to run on the operator's checkout. A file in that list is a file where a
  *merge* may stop; it is not a promise that it will (two changes in different regions of one file merge cleanly).
  Conversely, a file **absent** from that list cannot conflict, which is why most syncs are cheap.
- **Files only the fork changed** are printed under a second heading. Those are the ones that will replay without
  conflict but can still fail to *compile* if upstream moved something they use. That distinction is the useful one:
  the conflict list predicts the work, the fork-only list predicts the surprises.
- The report also names the modules each side touched, which is the quickest read of "did upstream rewrite a seam
  module?" (§5).

Exit status is 0 when the report was produced, whether or not there is anything to merge.

---

## 4. The artefact checks the script runs

Both are cheap and neither needs a phone:

- **APK size**, per flag state, printed by path and bytes. A multi-megabyte jump in the flag-on build is expected
  (the MQTT client, the speech glue); a jump in the flag-off build is not, and means a dependency edge appeared that
  should not be in the shipped default.
- **Dex contents**, by unzipping `classes*.dex` and counting `com/anopticlabs/gravel/voicemqtt` strings. Flag off
  must be zero. Flag on must be non-zero. A zero in the flag-on build means the source set was not selected; a
  non-zero in the flag-off build means something outside the flag started referencing the module.

A dex string search does not prove a manifest component is absent. When a change adds a service or a receiver inside
the flag, also diff the merged manifests of the two builds (`aapt2 dump xmltree <apk> --file AndroidManifest.xml`).
The voice loop's speaking notice is a service, so this applies to it.

---

## 5. When upstream changes a module this fork seams into

The seams above are the exposure. Upstream owns `:pebble`'s settings screen, `:notebook`'s pipeline and
`:composeApp`'s application wiring; this fork hooks them. When a sync touches one of them, work in this order:

1. **Find out what upstream changed, from their commit, not from the conflict.** `git log -p <base>..origin/master --
   <the module>` and read the messages; upstream's messages in this repository say *why* (they carry measured
   numbers). A helper that replaced a pattern (`fontGrowth`, `widthInOrLongestWord`, a theme colour) means the fork's
   older spelling of the same idea is now the odd one out — re-express the fork's call site through the new helper,
   and if the helper is worth using from a second file, move it beside its sibling rather than copying it.
2. **Re-check the seam, not the feature.** The question is only ever "does the interface still describe something
   upstream still has?" If upstream renamed the list builder this fork adds an item to, the fix is one call site; if
   upstream removed the *screen* the item lives on, that is a decision for the operator, not a merge detail.
3. **Keep the seam's shape.** New fields across a seam are plain data; new capabilities are new interfaces read with
   `getOrNull`, bound only where the flag is on. Resist the temptation to reach from `:pebble` into `:voice-mqtt` for
   "just one type": it breaks the iOS target and it is the single change most likely to make the next sync
   expensive.
4. **If upstream's change and the fork's feature disagree about behaviour** (upstream wraps a value where the fork
   truncates it, upstream takes a colour from the theme where the fork fixed one), upstream wins the convention and
   the fork re-expresses. The fork's features are the product; the fork's *styling* is not.
5. **If upstream adds a test that the fork's own rows must satisfy** (the drawn-text tests over shared settings
   pieces, `FixedColorSentinelTest` over every main Kotlin file), add the fork's case to it where the test can reach
   it, and where it cannot — an instrumented test over a module the test's APK does not include — hold the property
   with a host test in the fork's idiom instead: `SettingsRowsFontConventionsTest` (`:pebble`) checks that every row
   the fork adds is drawn by upstream's wrapping builder and carries no `maxLines`, `lineHeight`, fixed `fontSize` or
   fixed `width`, which is the property the drawn tests measure on a phone.

---

## 6. What this document does not cover

- **iOS.** `:voice-mqtt` is Android-only and neither the script nor the procedure builds for iOS. A seam change that
  leaks an Android type into common code compiles on the host and breaks that target; the `getOrNull` rule is the
  guard, not a test.
- **The instrumented drawn-text tests** (`androidApp/src/androidTest`). They need a phone or an emulator, and the
  procedure here deliberately never touches the operator's phone. They are the measurement; what runs in the build
  is the property behind it (§5.5).
- **The phone.** Installing, sideloading and rolling back are separate procedures with their own record.
- **Pushing.** The merged branch stays local until the operator says otherwise.
