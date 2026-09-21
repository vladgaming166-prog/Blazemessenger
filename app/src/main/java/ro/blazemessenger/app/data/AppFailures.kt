package ro.blazemessenger.app.data

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import ro.blazemessenger.app.domain.model.AppError

class UsernameTakenException : Exception()
class RateLimitException : Exception()
class BlockedException : Exception()
class NotConfiguredException : Exception()

fun Throwable.toAppError(): AppError = when (this) {
    is NotConfiguredException -> AppError.NotConfigured
    is UsernameTakenException -> AppError.UsernameTaken
    is RateLimitException -> AppError.RateLimited
    is BlockedException -> AppError.Blocked
    is FirebaseAuthWeakPasswordException -> AppError.WeakPassword
    is FirebaseAuthUserCollisionException -> AppError.EmailInUse
    is FirebaseAuthInvalidCredentialsException -> AppError.WrongPassword
    is FirebaseAuthInvalidUserException -> AppError.WrongPassword
    is FirebaseAuthRecentLoginRequiredException -> AppError.RecentLoginRequired
    is FirebaseNetworkException -> AppError.Network
    else -> {
        val message = message.orEmpty()
        when {
            message.contains("network", ignoreCase = true) -> AppError.Network
            message.contains("PERMISSION_DENIED", ignoreCase = true) -> AppError.Blocked
            else -> AppError.Message(message.ifBlank { "unknown" })
        }
    }
}
