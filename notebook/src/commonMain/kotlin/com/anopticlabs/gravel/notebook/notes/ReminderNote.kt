package com.anopticlabs.gravel.notebook.notes

import kotlinx.datetime.LocalDateTime

/**
 * How much of a reminder's words its note's title keeps. The body always keeps every word; the title exists to
 * read in a list, and a filename is made from it.
 */
const val MAX_CONVERTED_TITLE_CHARS = 80

/** The title a note takes when the words that asked for a reminder had none of their own to lend it. */
const val REMINDER_NOTE_FALLBACK_TITLE = "Reminder"

/**
 * The note a capture becomes when it asked for a reminder the user has asked this app not to make.
 *
 * The body is the message exactly as it was said, uncut. A reminder's length cap exists because its message
 * travels inside an alarm intent and a notification; a note has no such journey, so nothing is dropped on the
 * way in — the point of converting instead of refusing is that the words survive.
 */
fun reminderAsNote(message: String, folder: String, now: LocalDateTime): BuiltNote =
    NoteBuilder.typedNote(title = reminderNoteTitle(message), body = message, folder = folder, now = now)

/**
 * The message's first line as a title: runs of whitespace collapsed to one space, then cut at a word boundary to
 * [MAX_CONVERTED_TITLE_CHARS] without splitting a surrogate pair.
 */
fun reminderNoteTitle(message: String): String {
    val firstLine = message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val collapsed = firstLine.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
    if (collapsed.isEmpty()) return REMINDER_NOTE_FALLBACK_TITLE
    if (collapsed.length <= MAX_CONVERTED_TITLE_CHARS) return collapsed
    val max = MAX_CONVERTED_TITLE_CHARS
    val cut = collapsed.substring(0, if (collapsed[max - 1].isHighSurrogate()) max - 1 else max)
    val lastSpace = cut.lastIndexOf(' ')
    return (if (lastSpace > 0) cut.substring(0, lastSpace) else cut).trimEnd()
}

private val WHITESPACE = Regex("\\s+")
