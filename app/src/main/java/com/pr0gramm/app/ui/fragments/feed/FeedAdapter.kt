package com.pr0gramm.app.ui.fragments.feed

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pr0gramm.app.R
import com.pr0gramm.app.Settings
import com.pr0gramm.app.api.pr0gramm.Message
import com.pr0gramm.app.api.pr0gramm.asThumbnail
import com.pr0gramm.app.feed.ContentType
import com.pr0gramm.app.feed.FeedItem
import com.pr0gramm.app.services.UriHelper
import com.pr0gramm.app.services.UserInfo
import com.pr0gramm.app.ui.*
import com.pr0gramm.app.ui.views.*
import com.pr0gramm.app.util.*
import com.pr0gramm.app.util.di.injector
import com.squareup.picasso.Picasso

@Suppress("NOTHING_TO_INLINE")
private inline fun idInCategory(cat: Long, idOffset: Long = 0): Long {
    return (idOffset shl 8) or cat
}

class FeedAdapter()
    : DelegateAdapter<FeedAdapter.Entry>(ItemCallback()) {

    init {
        delegates += FeedItemEntryAdapter
        delegates += CommentEntryAdapter
        delegates += UserEntryAdapter
        delegates += UserHintEntryAdapter
        delegates += UserLoadingEntryAdapter
        delegates += SpacerEntryAdapter
        delegates += ErrorAdapterDelegate(R.layout.feed_error)
        delegates += MissingContentTypeEntryAdapter
        delegates += staticLayoutAdapterDelegate<Entry.PlaceholderItem>(R.layout.feed_item_view_placeholder)
        delegates += staticLayoutAdapterDelegate(R.layout.feed_hint_empty, Entry.EmptyHint)
        delegates += staticLayoutAdapterDelegate(R.layout.feed_hint_loading, Entry.LoadingHint)
    }

    class ItemCallback : DiffUtil.ItemCallback<Entry>() {
        override fun areItemsTheSame(oldItem: Entry, newItem: Entry): Boolean {
            return oldItem.id == newItem.id
        }

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: Entry, newItem: Entry): Boolean {
            return oldItem == newItem
        }
    }

    fun findItemNear(index: Int): FeedItem? {
        if (index !in items.indices) {
            return null
        }

        val feedItems = items.listIterator(index).asSequence().filterIsInstance<Entry.Item>()
        return feedItems.firstOrNull()?.item
    }

    sealed class Entry(val id: Long) {
        data class UserHint(val user: UserAndMark, val action: OnUserClickedListener)
            : Entry(idInCategory(0))

        data class UserLoading(val user: UserAndMark)
            : Entry(idInCategory(1))

        data class User(val user: UserInfo, val myself: Boolean, val actions: UserInfoView.UserActionListener)
            : Entry(idInCategory(2))

        data class Error(override val errorText: String)
            : Entry(idInCategory(3)), ErrorAdapterDelegate.Value

        object EmptyHint
            : Entry(idInCategory(4))

        object LoadingHint
            : Entry(idInCategory(5))

        data class Item(val item: FeedItem, val repost: Boolean = false, val preloaded: Boolean, val seen: Boolean, val highlight: Boolean)
            : Entry(idInCategory(6, item.id))

        data class Spacer(val idx: Long, val height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, @LayoutRes val layout: Int? = null)
            : Entry(idInCategory(7, idx))

        data class Ad(val index: Long)
            : Entry(idInCategory(8, index))

        data class Comment(val message: Message, val currentUsername: String?)
            : Entry(idInCategory(9, message.id))

        class MissingContentType(val contentType: ContentType)
            : Entry(idInCategory(10))

        data class PlaceholderItem(val itemId: Long)
            : Entry(idInCategory(11, itemId))
    }

    inner class SpanSizeLookup(private val spanCount: Int) : GridLayoutManager.SpanSizeLookup() {
        init {
            isSpanIndexCacheEnabled = true
        }

        override fun getSpanSize(position: Int): Int {
            val item = getItem(position)

            if (item is Entry.Item && !item.highlight) {
                return 1
            }

            if (item is Entry.PlaceholderItem) {
                return 1
            }

            // default is full width
            return spanCount
        }
    }
}

data class UserAndMark(val name: String, val mark: Int)

private object FeedItemEntryAdapter
    : ListItemTypeAdapterDelegate<FeedAdapter.Entry.Item, FeedAdapter.Entry, FeedItemViewHolder>(FeedAdapter.Entry.Item::class) {

    override fun onCreateViewHolder(parent: ViewGroup): FeedItemViewHolder {
        return FeedItemViewHolder(parent.layoutInflater.inflate(R.layout.feed_item_view) as FrameLayout)
    }

    override fun onBindViewHolder(holder: FeedItemViewHolder, value: FeedAdapter.Entry.Item) {
        holder.bindTo(value)
    }
}

/**
 * View holder for one feed item.
 */
class FeedItemViewHolder(private val container: FrameLayout) : RecyclerView.ViewHolder(container) {
    val imageView: AspectImageView = find(R.id.image)

    // lazy views
    private var flagView: ImageView? = null
    private var overlayView: ImageView? = null

    lateinit var item: FeedItem
        private set

    private fun ensureFlagView(): ImageView {
        return flagView ?: inflateView(R.layout.feed_item_view_flag).also { view ->
            flagView = view
            container.addView(view)
        }
    }

    private fun ensureOverlayView(): ImageView {
        return overlayView ?: inflateView(R.layout.feed_item_view_overlay).also { view ->
            overlayView = view

            // add view directly above the image view
            val idx = container.indexOfChild(imageView) + 1
            container.addView(view, idx)
        }
    }

    private fun inflateView(id: Int): ImageView {
        return container.inflateDetachedChild(id) as ImageView
    }

    private fun setItemFlag(@DrawableRes res: Int) {
        val view = ensureFlagView()
        view.setImageResource(res)
        view.isVisible = true
    }

    private fun setItemOverlay(@DrawableRes res: Int) {
        val view = ensureOverlayView()
        view.setImageResource(res)
        view.isVisible = true
    }

    fun bindTo(entry: FeedAdapter.Entry.Item) {
        val item = entry.item

        val imageUri: Uri?

        if (entry.highlight) {
            imageUri = if (item.isImage) {
                UriHelper.of(itemView.context).media(item, hq = false)
            } else {
                UriHelper.of(itemView.context).fullThumbnail(item.asThumbnail())
            }

            imageView.aspect = entry.item.width.toFloat() / entry.item.height

        } else {
            imageUri = UriHelper.of(itemView.context).thumbnail(item.asThumbnail())
            imageView.aspect = 1f
        }

        val picasso = itemView.context.injector.instance<Picasso>()
        if (!item.placeholder) {
            picasso.load(imageUri)
                    .config(Bitmap.Config.RGB_565)
                    .placeholder(ColorDrawable(0xff333333.toInt()))
                    .into(imageView)
        } else {
            picasso.cancelRequest(imageView)

            // only set some color
            imageView.setImageDrawable(ColorDrawable(0xff886633.toInt()))
        }

        this.itemView.tag = this
        this.item = item

        when {
            entry.repost -> setItemOverlay(R.drawable.ic_repost)
            entry.seen -> setItemOverlay(R.drawable.ic_check)
            else -> overlayView?.isVisible = false
        }

        when {
            entry.item.isPinned -> setItemFlag(R.drawable.feed_pinned)
            entry.preloaded -> setItemFlag(R.drawable.feed_offline)
            else -> flagView?.isVisible = false
        }
    }
}

private object UserHintEntryAdapter
    : ListItemTypeAdapterDelegate<FeedAdapter.Entry.UserHint, FeedAdapter.Entry, UserHintEntryAdapter.ViewHolder>(FeedAdapter.Entry.UserHint::class) {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        return ViewHolder(UserHintView(parent.context))
    }

    override fun onBindViewHolder(holder: ViewHolder, value: FeedAdapter.Entry.UserHint) {
        holder.hintView.update(value.user.name, value.user.mark, value.action)
    }

    class ViewHolder(val hintView: UserHintView) : RecyclerView.ViewHolder(hintView)
}

private object UserLoadingEntryAdapter
    : ListItemTypeAdapterDelegate<FeedAdapter.Entry.UserLoading, FeedAdapter.Entry, UserLoadingEntryAdapter.ViewHolder>(FeedAdapter.Entry.UserLoading::class) {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        return ViewHolder(UserInfoLoadingView(parent.context))
    }

    override fun onBindViewHolder(holder: ViewHolder, value: FeedAdapter.Entry.UserLoading) {
        holder.hintView.update(value.user.name, value.user.mark)
    }

    class ViewHolder(val hintView: UserInfoLoadingView) : RecyclerView.ViewHolder(hintView)
}


private object SpacerEntryAdapter
    : ListItemTypeAdapterDelegate<FeedAdapter.Entry.Spacer, FeedAdapter.Entry, SpacerEntryAdapter.SpacerViewHolder>(FeedAdapter.Entry.Spacer::class) {

    override fun onCreateViewHolder(parent: ViewGroup): SpacerViewHolder {
        return SpacerViewHolder(parent.context)
    }

    override fun onBindViewHolder(holder: SpacerViewHolder, value: FeedAdapter.Entry.Spacer) {
        holder.bindTo(value)
    }

    class SpacerViewHolder(context: Context) : RecyclerView.ViewHolder(FrameLayout(context)) {
        private val view = itemView as FrameLayout

        @LayoutRes
        private var layoutId: Int? = null

        fun bindTo(spacer: FeedAdapter.Entry.Spacer) {
            itemView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                spacer.height
            )

            if (spacer.layout != null && layoutId != spacer.layout) {
                view.removeAllViews()
                view.layoutInflater.inflate(spacer.layout, view, true)
                layoutId = spacer.layout
            }
        }
    }
}

private object CommentEntryAdapter
    : ListItemTypeAdapterDelegate<FeedAdapter.Entry.Comment, FeedAdapter.Entry, CommentEntryAdapter.CommentViewHolder>(FeedAdapter.Entry.Comment::class) {

    override fun onCreateViewHolder(parent: ViewGroup): CommentViewHolder {
        val inflater = parent.layoutInflater
        return CommentViewHolder(inflater.inflate(R.layout.user_info_comment) as MessageView)
    }

    override fun onBindViewHolder(holder: CommentViewHolder, value: FeedAdapter.Entry.Comment) {
        holder.bindTo(value)
    }

    class CommentViewHolder(view: MessageView) : MessageAdapter.MessageViewHolder(view) {
        fun bindTo(entry: FeedAdapter.Entry.Comment) {
            val message = entry.message

            bindTo(message, null, entry.currentUsername)

            itemView.setOnClickListener {
                val context = itemView.context

                // open the post in "new"
                context.startActivity(
                    MainActivity.openItemIntent(
                        context,
                        message.itemId,
                        message.commentId
                    )
                )
            }
        }
    }
}

private object MissingContentTypeEntryAdapter
    : ListItemTypeAdapterDelegate<FeedAdapter.Entry.MissingContentType, FeedAdapter.Entry, MissingContentTypeEntryAdapter.ViewHolder>(FeedAdapter.Entry.MissingContentType::class) {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        return ViewHolder(parent.inflateDetachedChild<View>(R.layout.feed_hint_content_type))
    }

    override fun onBindViewHolder(holder: ViewHolder, value: FeedAdapter.Entry.MissingContentType) {
        val context = holder.itemView.context

        holder.desc.text = buildString {
            append(context.getString(R.string.could_not_load_feed_content_type, value.contentType.name))
            append(" ")
            append(context.getString(R.string.could_not_load_feed_content_type__change, value.contentType.name))
        }

        holder.button.text = context.getString(
                R.string.feed_hint_add_content_type, value.contentType.name,
        )

        holder.button.setOnClickListener {
            addContentType(value.contentType)
        }
    }

    private fun addContentType(contentType: ContentType) {
        val key = when (contentType) {
            ContentType.NSFW -> "pref_feed_type_nsfw"
            ContentType.NSFL -> "pref_feed_type_nsfl"
            else -> "pref_feed_type_sfw"
        }

        // enable the content type
        Settings.edit { putBoolean(key, true) }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val desc = find<TextView>(R.id.error)
        val button = find<Button>(R.id.confirm_button)
    }
}


private object UserEntryAdapter
    : ListItemTypeAdapterDelegate<FeedAdapter.Entry.User, FeedAdapter.Entry, UserEntryAdapter.UserInfoViewHolder>(FeedAdapter.Entry.User::class) {

    override fun onCreateViewHolder(parent: ViewGroup): UserInfoViewHolder {
        return UserInfoViewHolder(UserInfoView(parent.context))
    }

    override fun onBindViewHolder(holder: UserInfoViewHolder, value: FeedAdapter.Entry.User) {
        holder.view.updateUserInfo(value.user.info, value.user.comments, value.myself, value.actions)
    }

    class UserInfoViewHolder(val view: UserInfoView) : RecyclerView.ViewHolder(view)
}

