package ro.blazemessenger.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import ro.blazemessenger.app.notify.NotificationCenter
import javax.inject.Inject

@HiltAndroidApp
class BlazeApplication : Application() {
    @Inject lateinit var notifications: NotificationCenter

    override fun onCreate() {
        super.onCreate()
        notifications.ensureChannels()
    }
}
