package com.anopticlabs.gravel.notebook.pipeline

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.alarms.ReminderAlarms
import com.anopticlabs.gravel.notebook.alarms.ReminderNotifier
import com.anopticlabs.gravel.notebook.notes.BuiltNote
import com.anopticlabs.gravel.notebook.notes.noteWriteFolder
import com.anopticlabs.gravel.notebook.notes.reminderAsNote
import com.anopticlabs.gravel.notebook.reminders.ReminderItem
import com.anopticlabs.gravel.notebook.reminders.ReminderProposal
import com.anopticlabs.gravel.notebook.reminders.ReminderRefreshSignal
import com.anopticlabs.gravel.notebook.reminders.VoiceCompletion
import com.anopticlabs.gravel.notebook.reminders.cutToReminderMessage
import com.anopticlabs.gravel.notebook.reminders.mergeParkedForDisplay
import com.anopticlabs.gravel.notebook.reminders.planReminderArm
import com.anopticlabs.gravel.notebook.reminders.shouldPostVoiceCompletionNotification
import com.anopticlabs.gravel.notebook.routing.UNCLASSIFIED_FOLDER
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.ReminderStore
import com.anopticlabs.gravel.notebook.storage.VoiceCompletionStore
import java.util.UUID
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What publishing a capture wrote; the drain settles the capture's row from it. */
sealed interface PublishedCapture {
    data class Parked(val completionId: String) : PublishedCapture

    data class ReminderSet(val reminder: ReminderItem, val scheduled: Boolean, val defaulted: Boolean) : PublishedCapture

    data class NoteSaved(val noteId: String, val title: String, val classificationFailed: Boolean) : PublishedCapture
}

/**
 * Publishes a capture by storing what it asked for (a parked claim, a reminder or a note);
 * a failure of that write propagates, so the capture stays queued. What follows the write (the
 * alarm, a notification, the refresh tick) is best effort and never fails the publish.
 *
 * Gravel: [remindersAllowed] is the voice loop's "Disable app reminders" switch, read at the moment a capture
 * is published. With it closed a capture that asked for a reminder is written as a note with its words intact
 * and nothing is armed; nothing bound — the default, and the state of a build without the loop — leaves a
 * reminder capture running down exactly the path it ran before the switch existed.
 */
class CapturePublisher(
    private val notes: NoteStore,
    private val reminders: ReminderStore,
    private val completions: VoiceCompletionStore,
    private val alarms: ReminderAlarms,
    private val notifier: ReminderNotifier,
    private val refresh: ReminderRefreshSignal,
    private val quickRemindMinutes: () -> Int,
    private val appBackgrounded: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Whether a capture may become a reminder at all; true when nothing is bound. */
    private val remindersAllowed: () -> Boolean = { true },
    /** The zone a converted reminder's note is dated in; see [reminderAsNote]. */
    private val zone: () -> TimeZone = TimeZone::currentSystemDefault,
) {
    // Orders a park's save and notice against readParked's read and cancel.
    private val completionNotice = Mutex()

    /** Strict reads of every store file a publish writes, so one that cannot be used is found before any engine runs. */
    fun checkStores() {
        notes.readMetadata(strict = true)
        notes.readFolders(strict = true)
        notes.readFolderTags(strict = true)
        reminders.readAll(strict = true)
        completions.readAll(strict = true)
    }

    /** [offeredFolders] are the folders the note was routed among; see [noteWriteFolder]. */
    suspend fun publish(built: BuiltCapture, capturedAtMillis: Long, offeredFolders: List<String>): PublishedCapture =
        when (built) {
            is BuiltCapture.Completion -> park(built.utterance, capturedAtMillis)
            // The one point that decides a capture becomes a reminder, and so the one place the switch closes:
            // closed, its words go through the note path instead, which arms no alarm and writes no reminder row.
            is BuiltCapture.Reminder ->
                if (remindersAllowed()) reminder(built.proposal)
                else noteAsReminderKept(built.proposal, capturedAtMillis, offeredFolders)
            is BuiltCapture.Note -> note(built.note, offeredFolders)
        }

    /** A reminder the user asked not to have: its message becomes a note in the unclassified folder, uncut. */
    private suspend fun noteAsReminderKept(proposal: ReminderProposal, capturedAtMillis: Long, offeredFolders: List<String>): PublishedCapture {
        logger.i { "a capture that asked for a reminder was kept as a note: the app's own reminders are switched off" }
        return note(
            reminderAsNote(proposal.message, UNCLASSIFIED_FOLDER, Instant.fromEpochMilliseconds(capturedAtMillis).toLocalDateTime(zone())),
            offeredFolders,
        )
    }

    private suspend fun park(utterance: String, capturedAtMillis: Long): PublishedCapture {
        val completion = VoiceCompletion(newId(), utterance, capturedAtMillis)
        completionNotice.withLock {
            completions.save(completion)
            bestEffort("refresh") { refresh.emit() }
            bestEffort("completion notice") {
                val count = mergeParkedForDisplay(completions.readAll(), emptyList(), completion).size
                if (shouldPostVoiceCompletionNotification(appBackgrounded(), count)) notifier.postCompletionsToReview(count)
            }
        }
        return PublishedCapture.Parked(completion.completionId)
    }

    /**
     * The parked claims, read strictly; with none left, or with [reviewing] (the user has the
     * claims in front of them), the "to review" notification is canceled.
     */
    suspend fun readParked(reviewing: Boolean = false): List<VoiceCompletion> = completionNotice.withLock {
        completions.readAll(strict = true).also {
            if (it.isEmpty() || reviewing) {
                currentCoroutineContext().ensureActive()
                notifier.cancelCompletionsToReview()
            }
        }
    }

    private suspend fun reminder(proposal: ReminderProposal): PublishedCapture {
        val message = cutToReminderMessage(proposal.message)
        val plan = planReminderArm(proposal, quickRemindMinutes(), now())
        val item = reminders.create { id -> ReminderItem(id, plan.millis, message, timeToCheck = plan.toCheck) }
        // A reminder stored but not armed waits for the next re-arm sweep and gets no set notice.
        val scheduled = bestEffort("alarm for reminder ${item.id}") { alarms.arm(item) }
        // Before this refresh: a save or Keep from the row that came ahead of the post would leave the notice up.
        if (scheduled) bestEffort("set notice") { notifier.postSetNotice(item) }
        bestEffort("refresh") { refresh.emit() }
        return PublishedCapture.ReminderSet(item, scheduled, plan.defaulted)
    }

    private suspend fun note(note: BuiltNote, offeredFolders: List<String>): PublishedCapture {
        val folder = noteWriteFolder(note.folder, offeredFolders)
        val noteId = notes.createListedNote(folder, note.filename, note.markdown, title = note.title, date = note.date).id
        return PublishedCapture.NoteSaved(noteId, note.title, note.classificationFailed)
    }

    private inline fun bestEffort(what: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w { "$what failed after the capture was published: ${e::class.simpleName}" }
        false
    }

    private companion object {
        val logger = Logger.withTag("CapturePublisher")
    }
}
