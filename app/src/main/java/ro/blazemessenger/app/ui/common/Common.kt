package ro.blazemessenger.app.ui.common

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import java.io.File
import java.util.Date
import ro.blazemessenger.app.R
import ro.blazemessenger.app.core.ByteFormats
import ro.blazemessenger.app.domain.model.AppError

@Composable
fun AppError.asText(): String = when (this) {
    AppError.NotConfigured -> stringResource(R.string.not_configured)
    AppError.InvalidEmail -> stringResource(R.string.invalid_email)
    AppError.WeakPassword -> stringResource(R.string.weak_password)
    AppError.InvalidUsername -> stringResource(R.string.invalid_username)
    AppError.UsernameTaken -> stringResource(R.string.username_taken)
    AppError.WrongPassword -> stringResource(R.string.wrong_password)
    AppError.EmailInUse -> stringResource(R.string.email_in_use)
    AppError.Network -> stringResource(R.string.network_error)
    AppError.GoogleNotConfigured -> stringResource(R.string.google_not_configured)
    AppError.RecentLoginRequired -> stringResource(R.string.recent_login)
    AppError.Blocked -> stringResource(R.string.blocked_by_other)
    AppError.RateLimited -> stringResource(R.string.generic_error)
    AppError.Permission -> stringResource(R.string.call_permission_needed)
    is AppError.FileTooLarge -> stringResource(R.string.file_too_large, ByteFormats.format(limitBytes))
    AppError.UnsupportedFile -> stringResource(R.string.file_type_blocked)
    is AppError.Message -> text
    AppError.Unknown -> stringResource(R.string.generic_error)
}

fun formatWhen(context: Context, epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString().ifBlank { android.text.format.DateFormat.getTimeFormat(context).format(Date(epochMillis)) }
}

@Composable
fun Avatar(
    label: String,
    photoFile: File?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    if (photoFile != null) {
        AsyncImage(
            model = photoFile,
            contentDescription = stringResource(R.string.cd_avatar),
            modifier = modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.trim().take(1).uppercase().ifBlank { "B" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
fun rememberCachedFile(path: String, load: suspend (String) -> File): File? {
    var file by remember(path) { mutableStateOf<File?>(null) }
    LaunchedEffect(path) {
        if (path.isBlank()) {
            file = null
        } else {
            file = runCatching { load(path) }.getOrNull()
        }
    }
    return file
}

fun Context.viewFile(file: File, mime: String) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(Intent.createChooser(intent, getString(R.string.open_file))) }
}
