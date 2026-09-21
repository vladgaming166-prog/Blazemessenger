package ro.blazemessenger.app.data.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.functions.FirebaseFunctions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import ro.blazemessenger.app.config.BackendConfig
import ro.blazemessenger.app.core.UsernamePolicy
import ro.blazemessenger.app.core.UsernameResult
import ro.blazemessenger.app.data.NotConfiguredException
import ro.blazemessenger.app.data.toAppError
import ro.blazemessenger.app.data.user.UserRepository
import ro.blazemessenger.app.domain.model.AppError
import ro.blazemessenger.app.domain.model.UserProfile

@Singleton
class AuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions,
    private val users: UserRepository,
    private val config: BackendConfig,
) {
    val currentUser: FirebaseUser? get() = auth.currentUser

    fun authState(): Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun signUp(email: String, password: String, username: String, displayName: String) {
        ensureConfigured()
        if (!email.contains("@") || email.length < 5) throw IllegalArgumentException("invalid-email")
        if (password.length < 8) throw IllegalArgumentException("weak-password")
        val parsed = UsernamePolicy.validate(username)
        if (parsed !is UsernameResult.Ok) throw IllegalArgumentException("invalid-username")
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        val firebaseUser = result.user ?: error("missing-user")
        try {
            val profile = UserProfile(
                uid = firebaseUser.uid,
                email = email.trim(),
                username = parsed.username,
                usernameLower = parsed.lower,
                displayName = displayName.trim().ifBlank { parsed.username }.take(60),
                createdAt = System.currentTimeMillis(),
                profileComplete = true,
            )
            users.reserveProfile(profile)
            firebaseUser.sendEmailVerification().await()
        } catch (error: Throwable) {
            firebaseUser.delete().await()
            throw error
        }
    }

    suspend fun signIn(email: String, password: String) {
        ensureConfigured()
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun sendPasswordReset(email: String) {
        ensureConfigured()
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    suspend fun sendVerification() {
        ensureConfigured()
        auth.currentUser?.sendEmailVerification()?.await()
    }

    suspend fun reload(): FirebaseUser? {
        ensureConfigured()
        val user = auth.currentUser ?: return null
        user.reload().await()
        return auth.currentUser
    }

    suspend fun signInWithGoogle(context: Context) {
        ensureConfigured()
        val clientId = config.googleWebClientId
        if (clientId.isBlank()) throw GoogleNotConfiguredException()
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val response = CredentialManager.create(context).getCredential(context, request)
        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            error("unexpected-credential")
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(google.idToken, null)
        val result = auth.signInWithCredential(firebaseCredential).await()
        val firebaseUser = result.user ?: error("missing-user")
        val existing = users.profile(firebaseUser.uid)
        if (existing == null) {
            users.reserveProfile(
                UserProfile(
                    uid = firebaseUser.uid,
                    email = firebaseUser.email.orEmpty(),
                    username = "",
                    usernameLower = "",
                    displayName = firebaseUser.displayName?.take(60).orEmpty().ifBlank { "Blaze" },
                    photoPath = "",
                    createdAt = System.currentTimeMillis(),
                    profileComplete = false,
                ),
            )
        }
    }

    suspend fun completeUsername(username: String, displayName: String) {
        ensureConfigured()
        val user = auth.currentUser ?: error("signed-out")
        val parsed = UsernamePolicy.validate(username)
        if (parsed !is UsernameResult.Ok) throw IllegalArgumentException("invalid-username")
        val existing = users.profile(user.uid)
        users.reserveProfile(
            UserProfile(
                uid = user.uid,
                email = user.email.orEmpty(),
                username = parsed.username,
                usernameLower = parsed.lower,
                displayName = displayName.trim().ifBlank { parsed.username }.take(60),
                bio = existing?.bio.orEmpty(),
                photoPath = existing?.photoPath.orEmpty(),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                lastSeen = existing?.lastSeen ?: 0L,
                profileComplete = true,
                privacy = existing?.privacy ?: ro.blazemessenger.app.domain.model.PrivacySettings(),
            ),
            previousUsernameLower = existing?.usernameLower,
        )
    }

    fun signOut() {
        auth.signOut()
    }

    suspend fun reauthenticate(password: String) {
        ensureConfigured()
        val user = auth.currentUser ?: error("signed-out")
        val email = user.email ?: error("missing-email")
        val credential = EmailAuthProvider.getCredential(email, password)
        user.reauthenticate(credential).await()
    }

    suspend fun deleteAccount() {
        ensureConfigured()
        val user = auth.currentUser ?: return
        try {
            functions.getHttpsCallable("deleteAccount").call().await()
        } catch (error: Exception) {
            users.deleteOwnAccountData(user.uid)
            user.delete().await()
            return
        }
        if (auth.currentUser != null) {
            auth.signOut()
        }
    }

    private fun ensureConfigured() {
        if (!config.configured) throw NotConfiguredException()
    }
}

class GoogleNotConfiguredException : Exception()

fun Throwable.authError(): AppError = when (this) {
    is GoogleNotConfiguredException -> AppError.GoogleNotConfigured
    is IllegalArgumentException -> when (message) {
        "invalid-email" -> AppError.InvalidEmail
        "weak-password" -> AppError.WeakPassword
        "invalid-username" -> AppError.InvalidUsername
        else -> this.toAppError()
    }
    else -> this.toAppError()
}
