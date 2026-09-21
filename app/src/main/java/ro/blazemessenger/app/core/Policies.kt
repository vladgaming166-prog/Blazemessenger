package ro.blazemessenger.app.core

import java.util.Locale

enum class MediaKind { IMAGE, VIDEO, AUDIO, DOCUMENT }

sealed interface FileCheck {
    data class Ok(val kind: MediaKind) : FileCheck
    data object Unsupported : FileCheck
    data class TooLarge(val limitBytes: Long) : FileCheck
}

object FilePolicy {
    const val MAX_IMAGE = 10L * 1024 * 1024
    const val MAX_VIDEO = 50L * 1024 * 1024
    const val MAX_AUDIO = 15L * 1024 * 1024
    const val MAX_DOCUMENT = 25L * 1024 * 1024

    private val images = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    private val videos = setOf("video/mp4", "video/webm", "video/3gpp")
    private val audio = setOf(
        "audio/mpeg",
        "audio/mp4",
        "audio/aac",
        "audio/ogg",
        "audio/wav",
        "audio/x-wav",
        "audio/mp3",
    )
    private val documents = setOf(
        "application/pdf",
        "application/zip",
        "text/plain",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )

    fun kindFor(mime: String): MediaKind? {
        val normalized = mime.lowercase().substringBefore(';').trim()
        return when (normalized) {
            in images -> MediaKind.IMAGE
            in videos -> MediaKind.VIDEO
            in audio -> MediaKind.AUDIO
            in documents -> MediaKind.DOCUMENT
            else -> null
        }
    }

    fun maxBytes(kind: MediaKind): Long = when (kind) {
        MediaKind.IMAGE -> MAX_IMAGE
        MediaKind.VIDEO -> MAX_VIDEO
        MediaKind.AUDIO -> MAX_AUDIO
        MediaKind.DOCUMENT -> MAX_DOCUMENT
    }

    fun check(mime: String, sizeBytes: Long): FileCheck {
        val kind = kindFor(mime) ?: return FileCheck.Unsupported
        val limit = maxBytes(kind)
        if (sizeBytes < 0 || sizeBytes > limit) return FileCheck.TooLarge(limit)
        return FileCheck.Ok(kind)
    }
}

sealed interface UsernameResult {
    data class Ok(val username: String, val lower: String) : UsernameResult
    data object Invalid : UsernameResult
    data object Reserved : UsernameResult
}

object UsernamePolicy {
    private val pattern = Regex("^[A-Za-z0-9_]{3,20}$")
    private val reserved = setOf(
        "admin", "support", "blaze", "system", "help", "official", "blazemessenger",
    )

    fun validate(raw: String): UsernameResult {
        val username = raw.trim()
        if (!pattern.matches(username)) return UsernameResult.Invalid
        if (username.lowercase() in reserved) return UsernameResult.Reserved
        return UsernameResult.Ok(username, username.lowercase())
    }
}

object MessagePolicy {
    const val MAX_TEXT = 4000
    const val EDIT_WINDOW_MS = 15 * 60 * 1000L
    const val MAX_BURST = 20
    const val BURST_WINDOW_MS = 10_000L

    fun normalizedText(raw: String): String = raw.trim()

    fun canSendText(raw: String): Boolean {
        val text = normalizedText(raw)
        return text.isNotEmpty() && text.length <= MAX_TEXT
    }

    fun canEdit(createdAtMillis: Long, nowMillis: Long, senderIsSelf: Boolean, deleted: Boolean): Boolean {
        if (!senderIsSelf || deleted) return false
        if (createdAtMillis <= 0L || nowMillis < createdAtMillis) return false
        return nowMillis - createdAtMillis <= EDIT_WINDOW_MS
    }

    fun allowSend(previousSendTimes: List<Long>, nowMillis: Long): Boolean {
        val recent = previousSendTimes.count { nowMillis - it <= BURST_WINDOW_MS }
        return recent < MAX_BURST
    }
}

object CallStates {
    const val RINGING = "ringing"
    const val ACCEPTED = "accepted"
    const val DECLINED = "declined"
    const val MISSED = "missed"
    const val ENDED = "ended"
    const val FAILED = "failed"
    const val BUSY = "busy"

    val terminal = setOf(DECLINED, MISSED, ENDED, FAILED, BUSY)

    fun isTerminal(state: String): Boolean = state in terminal

    fun transition(current: String, next: String): String? {
        if (current in terminal) return null
        return when (current) {
            RINGING -> if (next == ACCEPTED || next in terminal) next else null
            ACCEPTED -> if (next == ENDED || next == FAILED) next else null
            else -> null
        }
    }
}

object DirectChatIds {
    fun of(firstUid: String, secondUid: String): String {
        require(firstUid.isNotBlank() && secondUid.isNotBlank())
        require(firstUid != secondUid)
        val (left, right) = if (firstUid < secondUid) firstUid to secondUid else secondUid to firstUid
        return "d_${left}_$right"
    }
}

object MentionParser {
    private val token = Regex("@([A-Za-z0-9_]{1,20})")

    fun mentions(text: String): List<String> {
        return token.findAll(text)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()
    }

    fun mentionsAll(text: String): Boolean = "all" in mentions(text)
}

object ByteFormats {
    fun format(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.1f GB", mb / 1024.0)
    }
}
