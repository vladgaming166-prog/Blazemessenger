package ro.blazemessenger.app.data.call

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import ro.blazemessenger.app.config.BackendConfig
import ro.blazemessenger.app.core.CallStates
import ro.blazemessenger.app.data.NotConfiguredException
import ro.blazemessenger.app.data.toCall
import ro.blazemessenger.app.data.toIce
import ro.blazemessenger.app.domain.model.CallRecord
import ro.blazemessenger.app.domain.model.IceCandidateDto

@Singleton
class CallRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val config: BackendConfig,
) {
    suspend fun create(record: CallRecord): String {
        ensureConfigured()
        val ref = firestore.collection("calls").document()
        ref.set(
            mapOf(
                "callerId" to record.callerId,
                "calleeId" to record.calleeId,
                "participantIds" to listOf(record.callerId, record.calleeId),
                "type" to record.type,
                "state" to CallStates.RINGING,
                "createdAt" to record.createdAt,
                "answeredAt" to 0L,
                "endedAt" to 0L,
                "offer" to record.offer,
                "answer" to "",
                "callerName" to record.callerName,
                "callerPhoto" to record.callerPhoto,
            ),
        ).await()
        return ref.id
    }

    suspend fun get(callId: String): CallRecord? {
        ensureConfigured()
        val snapshot = firestore.collection("calls").document(callId).get().await()
        return if (snapshot.exists()) snapshot.toCall() else null
    }

    fun observe(callId: String): Flow<CallRecord?> = callbackFlow {
        ensureConfigured()
        val registration = firestore.collection("calls").document(callId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(if (snapshot != null && snapshot.exists()) snapshot.toCall() else null)
            }
        awaitClose { registration.remove() }
    }

    suspend fun transition(callId: String, next: String, answeredAt: Long? = null, endedAt: Long? = null) {
        ensureConfigured()
        val ref = firestore.collection("calls").document(callId)
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            val current = snapshot.getString("state") ?: return@runTransaction
            val resolved = CallStates.transition(current, next) ?: return@runTransaction
            val updates = mutableMapOf<String, Any>("state" to resolved)
            if (answeredAt != null) updates["answeredAt"] = answeredAt
            if (endedAt != null) updates["endedAt"] = endedAt
            transaction.update(ref, updates)
        }.await()
    }

    suspend fun saveAnswer(callId: String, sdp: String) {
        ensureConfigured()
        firestore.collection("calls").document(callId)
            .set(mapOf("answer" to sdp), SetOptions.merge())
            .await()
    }

    suspend fun addCandidate(callId: String, candidate: IceCandidateDto) {
        ensureConfigured()
        firestore.collection("calls").document(callId).collection("candidates").add(
            mapOf(
                "from" to candidate.from,
                "sdp" to candidate.sdp,
                "sdpMid" to candidate.sdpMid,
                "sdpMLineIndex" to candidate.sdpMLineIndex,
            ),
        ).await()
    }

    fun observeCandidates(callId: String): Flow<List<IceCandidateDto>> = callbackFlow {
        ensureConfigured()
        val registration = firestore.collection("calls").document(callId).collection("candidates")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.map { it.toIce() }.orEmpty())
            }
        awaitClose { registration.remove() }
    }

    fun observeHistory(uid: String): Flow<List<CallRecord>> = callbackFlow {
        ensureConfigured()
        val query = firestore.collection("calls")
            .whereArrayContains("participantIds", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.map { it.toCall() }.orEmpty())
        }
        awaitClose { registration.remove() }
    }

    fun currentUid(): String? = auth.currentUser?.uid

    private fun ensureConfigured() {
        if (!config.configured) throw NotConfiguredException()
    }
}
