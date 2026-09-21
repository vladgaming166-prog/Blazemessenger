package ro.blazemessenger.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import ro.blazemessenger.app.config.BackendConfig
import ro.blazemessenger.app.core.FileCheck
import ro.blazemessenger.app.core.FilePolicy
import ro.blazemessenger.app.core.MediaKind
import ro.blazemessenger.app.data.NotConfiguredException

data class PreparedFile(
    val uri: Uri,
    val mime: String,
    val name: String,
    val size: Long,
    val kind: MediaKind,
    val tempFile: File?,
)

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: FirebaseStorage,
    private val config: BackendConfig,
) {
    fun stage(context: Context, uri: Uri): Uri {
        if (uri.scheme == "file") return uri
        val file = File(context.cacheDir, "stage_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("unreadable")
        return Uri.fromFile(file)
    }

    fun inspect(uri: Uri): PreparedFile {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = queryName(uri) ?: "file"
        val size = querySize(uri)
        return when (val check = FilePolicy.check(mime, size)) {
            is FileCheck.Ok -> PreparedFile(uri, mime, name, size, check.kind, null)
            is FileCheck.TooLarge -> error("too-large:${check.limitBytes}")
            FileCheck.Unsupported -> error("unsupported")
        }
    }

    fun compressImageIfNeeded(source: PreparedFile): PreparedFile {
        if (source.kind != MediaKind.IMAGE || source.mime == "image/gif") return source
        val bytes = context.contentResolver.openInputStream(source.uri)?.use { it.readBytes() } ?: return source
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return source
        val file = File(context.cacheDir, "upload_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        return source.copy(
            uri = Uri.fromFile(file),
            mime = "image/jpeg",
            name = source.name.substringBeforeLast('.') + ".jpg",
            size = file.length(),
            tempFile = file,
        )
    }

    suspend fun upload(
        storagePath: String,
        uri: Uri,
        onProgress: (Float) -> Unit,
    ): String {
        if (!config.configured) throw NotConfiguredException()
        val reference = storage.reference.child(storagePath)
        uploadTask(reference, uri, onProgress)
        return storagePath
    }

    suspend fun cachedFile(storagePath: String, onProgress: (Float) -> Unit = {}): File {
        if (!config.configured) throw NotConfiguredException()
        val target = File(cacheDir(), sha(storagePath))
        if (target.exists() && target.length() > 0) return target
        val partial = File(target.absolutePath + ".part")
        val reference = storage.reference.child(storagePath)
        download(reference, partial, onProgress)
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        return target
    }

    fun cacheSizeBytes(): Long {
        val dir = File(context.cacheDir, "media")
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun clearCache() {
        File(context.cacheDir, "media").deleteRecursively()
    }

    private fun cacheDir(): File = File(context.cacheDir, "media").apply { mkdirs() }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length >= 0) return descriptor.length
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            return input.readBytes().size.toLong()
        }
        return -1
    }

    private fun queryName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private suspend fun uploadTask(reference: StorageReference, uri: Uri, onProgress: (Float) -> Unit) {
        suspendCancellableCoroutine { continuation ->
            val task = reference.putFile(uri)
            task.addOnProgressListener { snapshot ->
                val total = snapshot.totalByteCount.coerceAtLeast(1)
                onProgress(snapshot.bytesTransferred.toFloat() / total.toFloat())
            }
            task.addOnSuccessListener {
                if (continuation.isActive) continuation.resume(Unit)
            }
            task.addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            continuation.invokeOnCancellation { task.cancel() }
        }
    }

    private suspend fun download(reference: StorageReference, target: File, onProgress: (Float) -> Unit) {
        suspendCancellableCoroutine { continuation ->
            val task = reference.getFile(target)
            task.addOnProgressListener { snapshot ->
                val total = snapshot.totalByteCount.coerceAtLeast(1)
                onProgress(snapshot.bytesTransferred.toFloat() / total.toFloat())
            }
            task.addOnSuccessListener {
                if (continuation.isActive) continuation.resume(Unit)
            }
            task.addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            continuation.invokeOnCancellation { task.cancel() }
        }
    }

    private fun sha(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
