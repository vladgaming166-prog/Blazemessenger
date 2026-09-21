package ro.blazemessenger.app.config

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import ro.blazemessenger.app.BuildConfig
import ro.blazemessenger.app.R

@Singleton
class BackendConfig @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val configured: Boolean
        get() = runCatching {
            context.getString(R.string.project_id) != BuildConfig.UNCONFIGURED_PROJECT_ID
        }.getOrDefault(false)

    val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

    val turnUrl: String = BuildConfig.TURN_URL
    val turnUsername: String = BuildConfig.TURN_USERNAME
    val turnPassword: String = BuildConfig.TURN_PASSWORD

    val turnConfigured: Boolean
        get() = turnUrl.isNotBlank() && turnUsername.isNotBlank() && turnPassword.isNotBlank()
}
