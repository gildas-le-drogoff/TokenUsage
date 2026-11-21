package fr.gildasledrogoff.tokenusage.core.notification

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import fr.gildasledrogoff.tokenusage.R
import fr.gildasledrogoff.tokenusage.core.domain.model.LockScreenVisibility

/** Applies the user's lock-screen visibility to a notification (shared by all quota notifications). */
internal fun NotificationCompat.Builder.applyLockScreenVisibility(
    context: Context,
    visibility: LockScreenVisibility,
    channelId: String,
    @StringRes publicTextRes: Int
): NotificationCompat.Builder {
    when (visibility) {
        LockScreenVisibility.SHOW_ALL -> setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        LockScreenVisibility.HIDDEN -> setVisibility(NotificationCompat.VISIBILITY_SECRET)
        LockScreenVisibility.SYSTEM -> {
            setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            setPublicVersion(
                NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_quota)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(publicTextRes))
                    .build()
            )
        }
    }
    return this
}
