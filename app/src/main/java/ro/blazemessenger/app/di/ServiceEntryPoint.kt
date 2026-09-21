package ro.blazemessenger.app.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ro.blazemessenger.app.call.CallCoordinator
import ro.blazemessenger.app.data.chat.ChatRepository
import ro.blazemessenger.app.data.media.MediaRepository
import ro.blazemessenger.app.data.prefs.SettingsRepository
import ro.blazemessenger.app.notify.NotificationCenter

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServiceEntryPoint {
    fun coordinator(): CallCoordinator
    fun chats(): ChatRepository
    fun media(): MediaRepository
    fun notifications(): NotificationCenter
    fun settings(): SettingsRepository
}
