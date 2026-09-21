package ro.blazemessenger.app

import android.content.pm.ProviderInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.provider.FirebaseInitProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = BlazeApplication::class)
class StartupTest {
    @Test
    fun mainActivityLaunches() {
        // Robolectric does not install manifest ContentProviders. On a device,
        // FirebaseInitProvider runs before Application.onCreate.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val provider = FirebaseInitProvider()
        val info = ProviderInfo().apply {
            authority = "${context.packageName}.firebaseinitprovider"
            exported = false
        }
        provider.attachInfo(context, info)
        check(FirebaseApp.getApps(context).isNotEmpty()) { "FirebaseApp was not initialized" }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                check(!activity.isFinishing) { "MainActivity finished during startup" }
            }
        }
    }
}
