package ro.blazemessenger.app.data.chat

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import ro.blazemessenger.app.config.BackendConfig
import ro.blazemessenger.app.core.DirectChatIds
import ro.blazemessenger.app.core.MentionParser
import ro.blazemessenger.app.core.MessagePolicy
import ro.blazemessenger.app.data.BlockedException
import ro.blazemessenger.app.data.NotConfiguredException
import ro.blazemessenger.app.data.RateLimitException
import ro.blazemessenger.app.data.snapshots
import ro.blazemessenger.app.data.toChat
import ro.blazemessenger.app.data.toMember
import ro.blazemessenger.app.data.toMessage
import ro.blazemessenger.app.data.user.UserRepository
import ro.blazemessenger.app.domain.model.Chat
import ro.blazemessenger.app.domain.model.ChatMessage
import ro.blazemessenger.app.domain.model.MemberState

@Singleton
class ChatRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val users: UserRepository,
    private val config: BackendConfig,
) {
    private val sendTimes = ArrayDeque<Long>()

    fun observeChats(uid: String): Flow<List<Chat>> = callbackFlow {
        ensureConfigured()
        val query = firestore.collection("chats")
            .whereArrayContains("memberIds", uid)
            .orderBy("lastMessageAt", Query.Direction.DESCENDING)
            .limit(100)
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.map { it.toChat() }.orEmpty())
        }
        awaitClose { registration.remove() }
    }

    fun observeChat(chatId: String): Flow<Chat?> = callbackFlow {
        ensureConfigured()
        val registration = firestore.collection("chats").document(chatId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(if (snapshot != null && snapshot.exists()) snapshot.toChat() else null)
            }
        awaitClose { registration.remove() }
    }

    fun observeLatest(chatId: String, limit: Long = 30): Flow<List<ChatMessage>> {
        ensureConfigured()
        return messages(chatId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .snapshots()
            .map { snapshot -> snapshot.documents.map { document -> document.toMessage() } }
    }

    suspend fun loadOlder(chatId: String, beforeCreatedAt: Long, limit: Long = 30): List<ChatMessage> {
        ensureConfigured()
        val snapshot = messages(chatId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .whereLessThan("createdAt", beforeCreatedAt)
            .limit(limit)
            .get()
            .await()
        return snapshot.documents.map { it.toMessage() }
    }

    fun observeTyping(chatId: String): Flow<List<String>> = callbackFlow {
        ensureConfigured()
        val uid = auth.currentUser?.uid
        val registration = firestore.collection("chats").document(chatId).collection("typing")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val now = System.currentTimeMillis()
                val names = snapshot?.documents?.mapNotNull { doc ->
                    if (doc.id == uid) return@mapNotNull null
                    val at = doc.getLong("at") ?: 0L
                    if (now - at > 4_000) null else doc.getString("name") ?: doc.id
                }.orEmpty()
                trySend(names)
            }
        awaitClose { registration.remove() }
    }

    fun observeMembers(chatId: String): Flow<List<MemberState>> = callbackFlow {
        ensureConfigured()
        val registration = firestore.collection("chats").document(chatId).collection("members")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.map { it.toMember() }.orEmpty())
            }
        awaitClose { registration.remove() }
    }

    suspend fun createDirect(otherUid: String): String {
        ensureConfigured()
        val uid = requireUid()
        if (users.isBlockedEitherWay(uid, otherUid)) throw BlockedException()
        val chatId = DirectChatIds.of(uid, otherUid)
        val ref = firestore.collection("chats").document(chatId)
        firestore.runTransaction { transaction ->
            val existing = transaction.get(ref)
            if (!existing.exists()) {
                val now = System.currentTimeMillis()
                transaction.set(
                    ref,
                    chatShell(
                        type = Chat.TYPE_DIRECT,
                        memberIds = listOf(uid, otherUid).sorted(),
                        adminIds = listOf(uid, otherUid).sorted(),
                        title = "",
                        createdBy = uid,
                        now = now,
                    ),
                )
                listOf(uid, otherUid).forEach { member ->
                    transaction.set(ref.collection("members").document(member), memberShell(member, now, admin = true))
                }
            }
        }.await()
        return chatId
    }

    suspend fun createGroup(title: String, otherUids: List<String>): String {
        ensureConfigured()
        val uid = requireUid()
        val members = (otherUids + uid).distinct()
        require(members.size >= 3)
        val now = System.currentTimeMillis()
        val ref = firestore.collection("chats").document()
        val batch = firestore.batch()
        batch.set(
            ref,
            chatShell(
                type = Chat.TYPE_GROUP,
                memberIds = members,
                adminIds = listOf(uid),
                title = title.trim().take(60),
                createdBy = uid,
                now = now,
            ),
        )
        members.forEach { member ->
            batch.set(
                ref.collection("members").document(member),
                memberShell(member, now, admin = member == uid),
            )
        }
        batch.commit().await()
        return ref.id
    }

    suspend fun sendText(
        chatId: String,
        rawText: String,
        reply: ChatMessage? = null,
        forwarded: Boolean = false,
    ): String {
        ensureConfigured()
        val text = MessagePolicy.normalizedText(rawText)
        if (!MessagePolicy.canSendText(text)) error("invalid-text")
        throttle()
        val uid = requireUid()
        awaitNotBlocked(chatId, uid)
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            senderId = uid,
            type = ChatMessage.TYPE_TEXT,
            text = text,
            replyToId = reply?.id.orEmpty(),
            replyPreview = reply?.preview().orEmpty(),
            replySenderId = reply?.senderId.orEmpty(),
            mentions = MentionParser.mentions(text),
            createdAt = now,
            forwarded = forwarded,
        )
        return writeMessage(chatId, message, preview = text)
    }

    suspend fun sendMedia(
        chatId: String,
        type: String,
        mediaPath: String,
        mime: String,
        fileName: String,
        fileSize: Long,
        caption: String = "",
    ): String {
        ensureConfigured()
        throttle()
        val uid = requireUid()
        awaitNotBlocked(chatId, uid)
        val now = System.currentTimeMillis()
        val message = ChatMessage(
            senderId = uid,
            type = type,
            text = caption.trim(),
            mediaPath = mediaPath,
            mime = mime,
            fileName = fileName,
            fileSize = fileSize,
            createdAt = now,
        )
        val preview = caption.trim().ifBlank { fileName.ifBlank { type } }
        return writeMessage(chatId, message, preview)
    }

    suspend fun editMessage(chatId: String, message: ChatMessage, rawText: String) {
        ensureConfigured()
        val uid = requireUid()
        val text = MessagePolicy.normalizedText(rawText)
        if (!MessagePolicy.canSendText(text)) error("invalid-text")
        if (!MessagePolicy.canEdit(message.createdAt, System.currentTimeMillis(), message.senderId == uid, message.deleted)) {
            error("edit-window")
        }
        messages(chatId).document(message.id).update(
            mapOf(
                "text" to text,
                "editedAt" to System.currentTimeMillis(),
                "mentions" to MentionParser.mentions(text),
            ),
        ).await()
    }

    suspend fun deleteMessage(chatId: String, message: ChatMessage) {
        ensureConfigured()
        val uid = requireUid()
        if (message.senderId != uid) error("not-sender")
        messages(chatId).document(message.id).update(
            mapOf(
                "deleted" to true,
                "text" to "",
                "mediaPath" to "",
            ),
        ).await()
    }

    suspend fun react(chatId: String, messageId: String, emoji: String) {
        ensureConfigured()
        val uid = requireUid()
        val ref = messages(chatId).document(messageId)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            val current = mutableMapOf<String, String>()
            val raw = snapshot.get("reactions") as? Map<*, *>
            raw?.entries?.forEach { entry ->
                val key = entry.key as? String ?: return@forEach
                val value = entry.value as? String ?: return@forEach
                current[key] = value
            }
            if (current[uid] == emoji) current.remove(uid) else current[uid] = emoji
            transaction.update(ref, "reactions", current)
        }.await()
    }

    suspend fun pin(chatId: String, messageId: String) {
        ensureConfigured()
        firestore.collection("chats").document(chatId)
            .update("pinnedMessageId", messageId)
            .await()
    }

    suspend fun setTyping(chatId: String, active: Boolean, displayName: String) {
        if (!config.configured) return
        val uid = auth.currentUser?.uid ?: return
        val ref = firestore.collection("chats").document(chatId).collection("typing").document(uid)
        if (!active) {
            ref.delete()
            return
        }
        ref.set(mapOf("at" to System.currentTimeMillis(), "name" to displayName)).await()
    }

    suspend fun markRead(chatId: String, readReceiptsEnabled: Boolean) {
        ensureConfigured()
        val uid = requireUid()
        val now = System.currentTimeMillis()
        firestore.collection("users").document(uid).collection("readCursors").document(chatId)
            .set(mapOf("lastReadAt" to now))
            .await()
        firestore.collection("chats").document(chatId)
            .update("unreadCounts.$uid", 0)
            .await()
        if (readReceiptsEnabled) {
            firestore.collection("chats").document(chatId).collection("members").document(uid)
                .set(mapOf("receiptAt" to now, "deliveredAt" to now), SetOptions.merge())
                .await()
        } else {
            firestore.collection("chats").document(chatId).collection("members").document(uid)
                .set(mapOf("deliveredAt" to now), SetOptions.merge())
                .await()
        }
    }

    suspend fun markDelivered(chatId: String) {
        if (!config.configured) return
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("chats").document(chatId).collection("members").document(uid)
            .set(mapOf("deliveredAt" to System.currentTimeMillis()), SetOptions.merge())
            .await()
    }

    suspend fun setMuted(chatId: String, until: Long) {
        ensureConfigured()
        val uid = requireUid()
        firestore.collection("chats").document(chatId).collection("members").document(uid)
            .set(mapOf("mutedUntil" to until), SetOptions.merge())
            .await()
    }

    suspend fun leaveGroup(chatId: String) {
        ensureConfigured()
        val uid = requireUid()
        val ref = firestore.collection("chats").document(chatId)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            val members = (snapshot.get("memberIds") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            val admins = (snapshot.get("adminIds") as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            transaction.update(
                ref,
                mapOf(
                    "memberIds" to members.filterNot { it == uid },
                    "adminIds" to admins.filterNot { it == uid },
                ),
            )
            transaction.delete(ref.collection("members").document(uid))
        }.await()
    }

    suspend fun message(chatId: String, messageId: String): ChatMessage? {
        ensureConfigured()
        val snapshot = messages(chatId).document(messageId).get().await()
        return if (snapshot.exists()) snapshot.toMessage() else null
    }

    private suspend fun writeMessage(chatId: String, message: ChatMessage, preview: String): String {
        val ref = messages(chatId).document()
        val batch = firestore.batch()
        batch.set(ref, message.toMap())
        batch.update(
            firestore.collection("chats").document(chatId),
            mapOf(
                "lastMessage" to preview.take(140),
                "lastMessageAt" to message.createdAt,
                "lastMessageSenderId" to message.senderId,
                "lastMessageType" to message.type,
            ),
        )
        batch.commit().await()
        return ref.id
    }

    private suspend fun awaitNotBlocked(chatId: String, uid: String) {
        val chat = firestore.collection("chats").document(chatId).get().await().toChat()
        if (!chat.isGroup) {
            val other = chat.memberIds.firstOrNull { it != uid } ?: return
            if (users.isBlockedEitherWay(uid, other)) throw BlockedException()
        }
    }

    private fun throttle() {
        val now = System.currentTimeMillis()
        while (sendTimes.isNotEmpty() && now - sendTimes.first() > MessagePolicy.BURST_WINDOW_MS) {
            sendTimes.removeFirst()
        }
        if (!MessagePolicy.allowSend(sendTimes, now)) throw RateLimitException()
        sendTimes.addLast(now)
    }

    private fun messages(chatId: String) =
        firestore.collection("chats").document(chatId).collection("messages")

    private fun requireUid(): String = auth.currentUser?.uid ?: error("signed-out")

    private fun ensureConfigured() {
        if (!config.configured) throw NotConfiguredException()
    }

    private fun chatShell(
        type: String,
        memberIds: List<String>,
        adminIds: List<String>,
        title: String,
        createdBy: String,
        now: Long,
    ): Map<String, Any> = mapOf(
        "type" to type,
        "memberIds" to memberIds,
        "adminIds" to adminIds,
        "title" to title,
        "photoPath" to "",
        "createdBy" to createdBy,
        "createdAt" to now,
        "lastMessage" to "",
        "lastMessageAt" to now,
        "lastMessageSenderId" to "",
        "lastMessageType" to ChatMessage.TYPE_TEXT,
        "pinnedMessageId" to "",
    )

    private fun memberShell(uid: String, now: Long, admin: Boolean): Map<String, Any> = mapOf(
        "uid" to uid,
        "role" to if (admin) "admin" else "member",
        "joinedAt" to now,
        "mutedUntil" to 0L,
        "receiptAt" to 0L,
        "deliveredAt" to 0L,
    )
}

private fun ChatMessage.toMap(): Map<String, Any> = mapOf(
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

fun ChatMessage.preview(): String = when {
    deleted -> ""
    text.isNotBlank() -> text.take(80)
    fileName.isNotBlank() -> fileName
    else -> type
}
