package com.pr0gramm.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.pr0gramm.app.Instant
import com.pr0gramm.app.api.pr0gramm.Message
import com.pr0gramm.app.util.bundle
import com.pr0gramm.app.util.di.injector
import com.pr0gramm.app.util.doInBackground
import kotlinx.coroutines.flow.firstOrNull


/**
 */
class InboxNotificationCanceledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val inboxService: InboxService = context.injector.instance()

        val unreadId: String = intent.getStringExtra(EXTRA_MESSAGE_UNREAD_ID) ?: return
        val timestamp: Instant = intent.getParcelableExtra(EXTRA_MESSAGE_TIMESTAMP) ?: return

        // now mark message as read
        inboxService.markAsRead(unreadId, timestamp)
    }

    companion object {
        private const val EXTRA_MESSAGE_TIMESTAMP = "messageTimestamp"
        private const val EXTRA_MESSAGE_UNREAD_ID = "messageUnreadId"

        fun makeIntent(context: Context, message: Message): Intent {
            return Intent(context, InboxNotificationCanceledReceiver::class.java).apply {
                data = Uri.parse("view://${message.unreadId}")

                replaceExtras(bundle {
                    putString(EXTRA_MESSAGE_UNREAD_ID, message.unreadId)
                    putParcelable(EXTRA_MESSAGE_TIMESTAMP, message.creationTime)
                })
            }
        }
    }
}
