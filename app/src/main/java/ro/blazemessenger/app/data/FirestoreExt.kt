package ro.blazemessenger.app.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import ro.blazemessenger.app.domain.model.CallRecord
import ro.blazemessenger.app.domain.model.Chat
import ro.blazemessenger.app.domain.model.ChatMessage
import ro.blazemessenger.app.domain.model.IceCandidateDto
import ro.blazemessenger.app.domain.model.MemberState
import ro.blazemessenger.app.domain.model.PrivacySettings
import ro.blazemessenger.app.domain.model.Story
import ro.blazemessenger.app.domain.model.UserProfile

fun Query.snapshots(): Flow<QuerySnapshot> = callbackFlow {
    val registration = addSnapshotListener { value, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        if (value != null) trySend(value)
    }
    awaitClose { registration.remove() }
}

fun DocumentSnapshot.longValue(field: String): Long = when (val value = get(field)) {
    is Long -> value
    is Int -> value.toLong()
    is Double -> value.toLong()
    else -> 0L
}

fun DocumentSnapshot.intValue(field: String): Int = longValue(field).toInt()

fun DocumentSnapshot.stringList(field: String): List<String> {
    return (get(field) as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
}

@Suppress("UNCHECKED_CAST")
fun DocumentSnapshot.intMap(field: String): Map<String, Int> {
    val raw = get(field) as? Map<*, *> ?: return emptyMap()
    return raw.mapNotNull { (key, value) ->
        val name = key as? String ?: return@mapNotNull null
        val number = when (value) {
            is Long -> value.toInt()
            is Int -> value
            is Double -> value.toInt()
            else -> return@mapNotNull null
        }
        name to number
    }.toMap()
}

fun DocumentSnapshot.stringMap(field: String): Map<String, String> {
    val raw = get(field) as? Map<*, *> ?: return emptyMap()
    return raw.mapNotNull { (key, value) ->
        val k = key as? String ?: return@mapNotNull null
        val v = value as? String ?: return@mapNotNull null
        k to v
    }.toMap()
}

fun DocumentSnapshot.toUser(): UserProfile {
    val privacy = get("privacy") as? Map<*, *>
    return UserProfile(
        uid = getString("uid") ?: id,
        email = getString("email") ?: "",
        username = getString("username") ?: "",
        usernameLower = getString("usernameLower") ?: "",
        displayName = getString("displayName") ?: "",
        bio = getString("bio") ?: "",
        photoPath = getString("photoPath") ?: "",
        createdAt = longValue("createdAt"),
        lastSeen = longValue("lastSeen"),
        profileComplete = getBoolean("profileComplete") ?: false,
        privacy = PrivacySettings(
            showOnline = privacy?.get("showOnline") as? Boolean ?: true,
            showLastSeen = privacy?.get("showLastSeen") as? Boolean ?: true,
            readReceipts = privacy?.get("readReceipts") as? Boolean ?: true,
        ),
    )
}

fun UserProfile.toMap(): Map<String, Any> = mapOf(
    "uid" to uid,
    "email" to email,
    "username" to username,
    "usernameLower" to usernameLower,
    "displayName" to displayName,
    "bio" to bio,
    "photoPath" to photoPath,
    "createdAt" to createdAt,
    "lastSeen" to lastSeen,
    "profileComplete" to profileComplete,
    "privacy" to mapOf(
        "showOnline" to privacy.showOnline,
        "showLastSeen" to privacy.showLastSeen,
        "readReceipts" to privacy.readReceipts,
    ),
)

fun DocumentSnapshot.toChat(): Chat = Chat(
    id = id,
    type = getString("type") ?: Chat.TYPE_DIRECT,
    memberIds = stringList("memberIds"),
    adminIds = stringList("adminIds"),
    title = getString("title") ?: "",
    photoPath = getString("photoPath") ?: "",
    createdBy = getString("createdBy") ?: "",
    createdAt = longValue("createdAt"),
    lastMessage = getString("lastMessage") ?: "",
    lastMessageAt = longValue("lastMessageAt"),
    lastMessageSenderId = getString("lastMessageSenderId") ?: "",
    lastMessageType = getString("lastMessageType") ?: ChatMessage.TYPE_TEXT,
    pinnedMessageId = getString("pinnedMessageId") ?: "",
    unreadCounts = intMap("unreadCounts"),
)

fun DocumentSnapshot.toMessage(): ChatMessage = ChatMessage(
    id = id,
    senderId = getString("senderId") ?: "",
    type = getString("type") ?: ChatMessage.TYPE_TEXT,
    text = getString("text") ?: "",
    mediaPath = getString("mediaPath") ?: "",
    mime = getString("mime") ?: "",
    fileName = getString("fileName") ?: "",
    fileSize = longValue("fileSize"),
    replyToId = getString("replyToId") ?: "",
    replyPreview = getString("replyPreview") ?: "",
    replySenderId = getString("replySenderId") ?: "",
    reactions = stringMap("reactions"),
    mentions = stringList("mentions"),
    editedAt = longValue("editedAt"),
    deleted = getBoolean("deleted") ?: false,
    createdAt = longValue("createdAt"),
    forwarded = getBoolean("forwarded") ?: false,
)

fun ChatMessage.toMap(): Map<String, Any> = mapOf(
    "senderId" to senderId,
    "type" to type,
    "text" to text,
    "mediaPath" to mediaPath,
    "mime" to mime,
    "fileName" to fileName,
    "fileSize" to fileSize,
    "replyToId" to replyToId,
    "replyPreview" to replyPreview,
    "replySenderId" to replySenderId,
    "reactions" to reactions,
    "mentions" to mentions,
    "editedAt" to editedAt,
    "deleted" to deleted,
    "createdAt" to createdAt,
    "forwarded" to forwarded,
)

fun DocumentSnapshot.toMember(): MemberState = MemberState(
    uid = getString("uid") ?: id,
    role = getString("role") ?: "member",
    joinedAt = longValue("joinedAt"),
    mutedUntil = longValue("mutedUntil"),
    receiptAt = longValue("receiptAt"),
    deliveredAt = longValue("deliveredAt"),
)

fun DocumentSnapshot.toCall(): CallRecord = CallRecord(
    id = id,
    callerId = getString("callerId") ?: "",
    calleeId = getString("calleeId") ?: "",
    participantIds = stringList("participantIds"),
    type = getString("type") ?: CallRecord.TYPE_VOICE,
    state = getString("state") ?: "ringing",
    createdAt = longValue("createdAt"),
    answeredAt = longValue("answeredAt"),
    endedAt = longValue("endedAt"),
    offer = getString("offer") ?: "",
    answer = getString("answer") ?: "",
    callerName = getString("callerName") ?: "",
    callerPhoto = getString("callerPhoto") ?: "",
)

fun DocumentSnapshot.toIce(): IceCandidateDto = IceCandidateDto(
    id = id,
    from = getString("from") ?: "",
    sdp = getString("sdp") ?: "",
    sdpMid = getString("sdpMid") ?: "",
    sdpMLineIndex = intValue("sdpMLineIndex"),
)

fun DocumentSnapshot.toStory(): Story = Story(
    id = id,
    authorId = getString("authorId") ?: "",
    text = getString("text") ?: "",
    mediaPath = getString("mediaPath") ?: "",
    createdAt = longValue("createdAt"),
    expiresAt = longValue("expiresAt"),
    viewerCount = intValue("viewerCount"),
)
