package ro.blazemessenger.app.data.user

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import ro.blazemessenger.app.config.BackendConfig
import ro.blazemessenger.app.data.NotConfiguredException
import ro.blazemessenger.app.data.UsernameTakenException
import ro.blazemessenger.app.data.longValue
import ro.blazemessenger.app.data.toUser
import ro.blazemessenger.app.domain.model.Presence
import ro.blazemessenger.app.domain.model.PrivacySettings
import ro.blazemessenger.app.domain.model.UserProfile

@Singleton
class UserRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val database: FirebaseDatabase,
    private val auth: FirebaseAuth,
    private val config: BackendConfig,
) {
    private fun requireConfigured() {
        if (!config.configured) throw NotConfiguredException()
    }

    fun observeProfile(uid: String): Flow<UserProfile?> = callbackFlow {
        requireConfigured()
        val registration = firestore.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(if (snapshot != null && snapshot.exists()) snapshot.toUser() else null)
            }
        awaitClose { registration.remove() }
    }

    suspend fun profile(uid: String): UserProfile? {
        requireConfigured()
        val snapshot = firestore.collection("users").document(uid).get().await()
        return if (snapshot.exists()) snapshot.toUser() else null
    }

    suspend fun reserveProfile(profile: UserProfile, previousUsernameLower: String? = null) {
        requireConfigured()
        firestore.runTransaction { transaction ->
            if (profile.usernameLower.isNotBlank()) {
                val nameRef = firestore.collection("usernames").document(profile.usernameLower)
                val existing = transaction.get(nameRef)
                val owner = existing.getString("uid")
                if (existing.exists() && owner != profile.uid) {
                    throw UsernameTakenException()
                }
                transaction.set(nameRef, mapOf("uid" to profile.uid))
            }
            if (!previousUsernameLower.isNullOrBlank() && previousUsernameLower != profile.usernameLower) {
                val oldRef = firestore.collection("usernames").document(previousUsernameLower)
                val old = transaction.get(oldRef)
                if (old.getString("uid") == profile.uid) transaction.delete(oldRef)
            }
            transaction.set(firestore.collection("users").document(profile.uid), profile.toPublicMap())
        }.await()
    }

    suspend fun updateProfile(uid: String, displayName: String, bio: String, photoPath: String?) {
        requireConfigured()
        val updates = mutableMapOf<String, Any>(
            "displayName" to displayName.trim().take(60),
            "bio" to bio.trim().take(200),
        )
        if (photoPath != null) updates["photoPath"] = photoPath
        firestore.collection("users").document(uid).set(updates, SetOptions.merge()).await()
    }

    suspend fun updatePrivacy(uid: String, privacy: PrivacySettings) {
        requireConfigured()
        firestore.collection("users").document(uid).set(
            mapOf(
                "privacy" to mapOf(
                    "showOnline" to privacy.showOnline,
                    "showLastSeen" to privacy.showLastSeen,
                    "readReceipts" to privacy.readReceipts,
                ),
            ),
            SetOptions.merge(),
        ).await()
    }

    suspend fun touchLastSeen(uid: String, visible: Boolean) {
        requireConfigured()
        if (!visible) return
        firestore.collection("users").document(uid)
            .set(mapOf("lastSeen" to System.currentTimeMillis()), SetOptions.merge())
            .await()
    }

    suspend fun search(prefix: String): List<UserProfile> {
        requireConfigured()
        val query = prefix.trim().lowercase()
        if (query.length < 2) return emptyList()
        val end = query + "\uf8ff"
        val snapshot = firestore.collection("users")
            .orderBy("usernameLower")
            .startAt(query)
            .endAt(end)
            .limit(20)
            .get()
            .await()
        return snapshot.documents.map { it.toUser() }
    }

    suspend fun block(otherUid: String) {
        requireConfigured()
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).collection("blocked").document(otherUid)
            .set(mapOf("createdAt" to System.currentTimeMillis()))
            .await()
    }

    suspend fun unblock(otherUid: String) {
        requireConfigured()
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).collection("blocked").document(otherUid).delete().await()
    }

    fun observeBlocked(uid: String): Flow<List<String>> = callbackFlow {
        requireConfigured()
        val registration = firestore.collection("users").document(uid).collection("blocked")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.map { it.id } ?: emptyList())
            }
        awaitClose { registration.remove() }
    }

    suspend fun isBlockedEitherWay(first: String, second: String): Boolean {
        requireConfigured()
        val left = firestore.collection("users").document(first).collection("blocked").document(second).get().await()
        if (left.exists()) return true
        val right = firestore.collection("users").document(second).collection("blocked").document(first).get().await()
        return right.exists()
    }

    suspend fun report(targetUid: String, reason: String, note: String) {
        requireConfigured()
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("reports").add(
            mapOf(
                "reporterId" to uid,
                "targetId" to targetUid,
                "reason" to reason.take(80),
                "note" to note.take(500),
                "createdAt" to System.currentTimeMillis(),
            ),
        ).await()
    }

    fun observePresence(uid: String): Flow<Presence> = callbackFlow {
        requireConfigured()
        val reference = database.getReference("status").child(uid)
        val listener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val state = snapshot.child("state").getValue(String::class.java)
                val hidden = snapshot.child("hidden").getValue(Boolean::class.java) ?: false
                val changed = snapshot.child("lastChanged").getValue(Long::class.java) ?: 0L
                trySend(Presence(online = state == "online" && !hidden, lastChanged = changed, hidden = hidden))
            }

            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                close(error.toException())
            }
        }
        reference.addValueEventListener(listener)
        awaitClose { reference.removeEventListener(listener) }
    }

    fun setPresence(uid: String, online: Boolean, hidden: Boolean) {
        if (!config.configured) return
        val reference = database.getReference("status").child(uid)
        val value = mapOf(
            "state" to if (online && !hidden) "online" else "offline",
            "hidden" to hidden,
            "lastChanged" to ServerValue.TIMESTAMP,
        )
        reference.setValue(value)
        if (online && !hidden) {
            reference.onDisconnect().setValue(
                mapOf(
                    "state" to "offline",
                    "hidden" to false,
                    "lastChanged" to ServerValue.TIMESTAMP,
                ),
            )
        } else {
            reference.onDisconnect().cancel()
        }
    }

    suspend fun saveToken(token: String) {
        requireConfigured()
        val uid = auth.currentUser?.uid ?: return
        firestore.collection("users").document(uid).collection("tokens").document(token)
            .set(mapOf("updatedAt" to System.currentTimeMillis()))
            .await()
    }

    suspend fun deleteOwnAccountData(uid: String) {
        requireConfigured()
        val profile = profile(uid)
        if (profile != null && profile.usernameLower.isNotBlank()) {
            val name = firestore.collection("usernames").document(profile.usernameLower).get().await()
            if (name.getString("uid") == uid) {
                firestore.collection("usernames").document(profile.usernameLower).delete().await()
            }
        }
        val tokens = firestore.collection("users").document(uid).collection("tokens").get().await()
        tokens.documents.forEach { it.reference.delete().await() }
        val blocked = firestore.collection("users").document(uid).collection("blocked").get().await()
        blocked.documents.forEach { it.reference.delete().await() }
        firestore.collection("users").document(uid).delete().await()
        database.getReference("status").child(uid).removeValue().await()
    }

    fun observeReadCursors(uid: String): Flow<Map<String, Long>> = callbackFlow {
        requireConfigured()
        val registration = firestore.collection("users").document(uid).collection("readCursors")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val map = snapshot?.documents?.associate { it.id to it.longValue("lastReadAt") }.orEmpty()
                trySend(map)
            }
        awaitClose { registration.remove() }
    }
}

private fun UserProfile.toPublicMap(): Map<String, Any> = mapOf(
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
