package com.pr0gramm.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.fragment.app.commit
import com.pr0gramm.app.R
import com.pr0gramm.app.databinding.ActivityConversationBinding
import com.pr0gramm.app.services.ThemeHelper
import com.pr0gramm.app.ui.base.BaseAppCompatActivity
import com.pr0gramm.app.ui.base.bindViews
import com.pr0gramm.app.ui.fragments.conversation.ConversationFragment
import com.pr0gramm.app.util.activityIntent


/**
 * The activity that displays the inbox.
 */
class ConversationActivity : BaseAppCompatActivity("ConversationActivity") {
    private val views by bindViews(ActivityConversationBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.theme.noActionBar)
        super.onCreate(savedInstanceState)

        setContentView(views)
        setSupportActionBar(views.toolbar)

        supportActionBar?.apply {
            setDisplayShowHomeEnabled(true)
            setDisplayHomeAsUpEnabled(true)
        }

        // restore previously selected tab
        if (savedInstanceState == null) {
            handleNewIntent(intent)
        }

        // inboxService.markAsRead(intent.getLongExtra(EXTRA_MESSAGE_TIMESTAMP, 0))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return super.onOptionsItemSelected(item) || when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }

            else -> false
        }
    }

    private fun handleNewIntent(intent: Intent?) {
        if (intent != null && intent.extras != null) {
            val extras = intent.extras ?: return

            val name = extras.getString(EXTRA_CONVERSATION_NAME) ?: return
            val fragment = ConversationFragment().apply { conversationName = name }

            supportFragmentManager.commit {
                add(R.id.content, fragment)
            }
        }
    }

    companion object {
        const val EXTRA_FROM_NOTIFICATION = "ConversationActivity.fromNotification"
        const val EXTRA_CONVERSATION_NAME = "ConversationActivity.name"

        fun start(context: Context, name: String, skipInbox: Boolean = false) {
            val activities = mutableListOf<Intent>()

            if (!skipInbox) {
                activities += activityIntent<InboxActivity>(context)
            }

            activities += activityIntent<ConversationActivity>(context) {
                putExtra(EXTRA_CONVERSATION_NAME, name)
            }

            context.startActivities(activities.toTypedArray())
        }
    }
}
