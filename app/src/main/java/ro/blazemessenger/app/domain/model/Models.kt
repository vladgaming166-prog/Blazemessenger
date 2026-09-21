package ro.blazemessenger.app.domain.model

data class PrivacySettings(
    val showOnline: Boolean = true,
    val showLastSeen: Boolean = true,
    val readReceipts: Boolean = true,
)

data class UserProfile(
    val uid: String = "",
    val email: String = "",
    val username: String = "",
    val usernameLower: String = "",
    val displayName: String = "",
    val bio: String = "",
    val photoPath: String = "",
    val createdAt: Long = 0L,
    val lastSeen: Long = 0L,
    val profileComplete: Boolean = false,
    val privacy: PrivacySettings = PrivacySettings(),
)

data class Presence(
    val online: Boolean = false,
    val lastChanged: Long = 0L,
    val hidden: Boolean = false,
)

data class Chat(
    val id: String = "",
    val type: String = TYPE_DIRECT,
    val memberIds: List<String> = emptyList(),
    val adminIds: List<String> = emptyList(),
    val title: String = "",
    val photoPath: String = "",
    val createdBy: String = "",
    val createdAt: Long = 0L,
    val lastMessage: String = "",
    val lastMessageAt: Long = 0L,
    val lastMessageSenderId: String = "",
    val lastMessageType: String = "text",
    val pinnedMessageId: String = "",
    val unreadCounts: Map<String, Int> = emptyMap(),
) {
    val isGroup: Boolean get() = type == TYPE_GROUP

    companion object {
        const val TYPE_DIRECT = "direct"
        const val TYPE_GROUP = "group"
    }
}

data class MemberState(
    val uid: String = "",
    val role: String = "member",
    val joinedAt: Long = 0L,
    val mutedUntil: Long = 0L,
    val receiptAt: Long = 0L,
    val deliveredAt: Long = 0L,
)

data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val type: String = TYPE_TEXT,
    val text: String = "",
    val mediaPath: String = "",
    val mime: String = "",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val replyToId: String = "",
    val replyPreview: String = "",
    val replySenderId: String = "",
    val reactions: Map<String, String> = emptyMap(),
    val mentions: List<String> = emptyList(),
    val editedAt: Long = 0L,
    val deleted: Boolean = false,
    val createdAt: Long = 0L,
    val forwarded: Boolean = false,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"
        const val TYPE_AUDIO = "audio"
        const val TYPE_FILE = "file"
    }
}

data class CallRecord(
    val id: String = "",
    val callerId: String = "",
    val calleeId: String = "",
    val participantIds: List<String> = emptyList(),
    val type: String = TYPE_VOICE,
    val state: String = "ringing",
    val createdAt: Long = 0L,
    val answeredAt: Long = 0L,
    val endedAt: Long = 0L,
    val offer: String = "",
    val answer: String = "",
    val callerName: String = "",
    val callerPhoto: String = "",
) {
    companion object {
        const val TYPE_VOICE = "voice"
        const val TYPE_VIDEO = "video"
    }
}

data class IceCandidateDto(
    val id: String = "",
    val from: String = "",
    val sdp: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
)

data class Story(
    val id: String = "",
    val authorId: String = "",
    val text: String = "",
    val mediaPath: String = "",
    val createdAt: Long = 0L,
    val expiresAt: Long = 0L,
    val viewerCount: Int = 0,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppLanguage { SYSTEM, EN, RO }

enum class RingtoneChoice { DEFAULT, SOFT, CUSTOM }

data class LocalSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val ringtone: RingtoneChoice = RingtoneChoice.DEFAULT,
    val customRingtoneUri: String = "",
    val ringtoneVolume: Float = 0.8f,
    val vibrateCalls: Boolean = true,
    val messageNotifications: Boolean = true,
    val messagePreview: Boolean = true,
    val messageVibrate: Boolean = true,
    val onboardingDone: Boolean = false,
)

sealed interface AppError {
    data object NotConfigured : AppError
    data object InvalidEmail : AppError
    data object WeakPassword : AppError
    data object InvalidUsername : AppError
    data object UsernameTaken : AppError
    data object WrongPassword : AppError
    data object EmailInUse : AppError
    data object Network : AppError
    data object GoogleNotConfigured : AppError
    data object RecentLoginRequired : AppError
    data object Blocked : AppError
    data object RateLimited : AppError
    data object Permission : AppError
    data class FileTooLarge(val limitBytes: Long) : AppError
    data object UnsupportedFile : AppError
    data class Message(val text: String) : AppError
    data object Unknown : AppError
}
