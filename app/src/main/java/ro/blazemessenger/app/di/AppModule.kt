package ro.blazemessenger.app.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun auth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides
    @Singleton
    fun storage(): FirebaseStorage = FirebaseStorage.getInstance()

    @Provides
    @Singleton
    fun database(): FirebaseDatabase = FirebaseDatabase.getInstance()

    @Provides
    @Singleton
    fun functions(): FirebaseFunctions = FirebaseFunctions.getInstance("europe-west1")
}
