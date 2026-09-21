package ro.blazemessenger.app.data.story

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import ro.blazemessenger.app.config.BackendConfig
import ro.blazemessenger.app.data.NotConfiguredException
import ro.blazemessenger.app.data.toStory
import ro.blazemessenger.app.domain.model.Story

@Singleton
class StoryRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val config: BackendConfig,
) {
    fun observeActive(): Flow<List<Story>> = callbackFlow {
        if (!config.configured) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val now = System.currentTimeMillis()
        val query = firestore.collection("stories")
            .whereGreaterThan("expiresAt", now)
            .orderBy("expiresAt", Query.Direction.ASCENDING)
            .limit(80)
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.map { it.toStory() }.orEmpty())
        }
        awaitClose { registration.remove() }
    }

    suspend fun publish(text: String, mediaPath: String): String {
        if (!config.configured) throw NotConfiguredException()
        val uid = auth.currentUser?.uid ?: error("signed-out")
        val now = System.currentTimeMillis()
        val ref = firestore.collection("stories").document()
        ref.set(
            mapOf(
                "authorId" to uid,
                "text" to text.trim().take(180),
                "mediaPath" to mediaPath,
                "createdAt" to now,
                "expiresAt" to now + 24L * 60L * 60L * 1000L,
                "viewerCount" to 0,
            ),
        ).await()
        return ref.id
    }

    suspend fun markViewed(storyId: String) {
        if (!config.configured) return
        val uid = auth.currentUser?.uid ?: return
        val story = firestore.collection("stories").document(storyId)
        val viewer = story.collection("viewers").document(uid)
        val existing = viewer.get().await()
        if (existing.exists()) return
        viewer.set(mapOf("at" to System.currentTimeMillis())).await()
        story.update("viewerCount", FieldValue.increment(1)).await()
    }

    suspend fun delete(storyId: String) {
        if (!config.configured) throw NotConfiguredException()
        firestore.collection("stories").document(storyId).delete().await()
    }
}
