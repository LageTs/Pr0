package com.pr0gramm.app.ui.fragments.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pr0gramm.app.Instant
import com.pr0gramm.app.Logger
import com.pr0gramm.app.R
import com.pr0gramm.app.Settings
import com.pr0gramm.app.api.pr0gramm.Api
import com.pr0gramm.app.db.CachedItemInfo
import com.pr0gramm.app.db.CachedMediaVariant
import com.pr0gramm.app.db.CachedSubtitle
import com.pr0gramm.app.db.FeedItemInfoQueries
import com.pr0gramm.app.feed.ContentType
import com.pr0gramm.app.feed.Feed
import com.pr0gramm.app.feed.FeedException
import com.pr0gramm.app.feed.FeedFilter
import com.pr0gramm.app.feed.FeedItem
import com.pr0gramm.app.feed.FeedManager
import com.pr0gramm.app.feed.FeedService
import com.pr0gramm.app.feed.FeedType
import com.pr0gramm.app.feed.Tags
import com.pr0gramm.app.model.config.Config
import com.pr0gramm.app.services.InMemoryCacheService
import com.pr0gramm.app.services.SeenService
import com.pr0gramm.app.services.UserService
import com.pr0gramm.app.services.preloading.PreloadManager
import com.pr0gramm.app.time
import com.pr0gramm.app.ui.SavedStateAccessor
import com.pr0gramm.app.ui.fragments.CommentRef
import com.pr0gramm.app.util.LazyStateFlow
import com.pr0gramm.app.util.LongSparseArray
import com.pr0gramm.app.util.StringException
import com.pr0gramm.app.util.observeChangeEx
import com.pr0gramm.app.util.rootCause
import com.pr0gramm.app.util.trace
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.ConnectException
import kotlin.math.abs

class FeedViewModel(
    private val savedState: SavedState,
    filter: FeedFilter, loadAroundItemId: Long?,

    private val feedService: FeedService,
    userService: UserService,
    val seenService: SeenService,
    private val inMemoryCacheService: InMemoryCacheService,
    private val preloadManager: PreloadManager,
    private val itemQueries: FeedItemInfoQueries,
) : ViewModel() {

    private val logger = Logger("FeedViewModel")

    val feedState = MutableStateFlow(
        FeedState(
            ready = savedState.feed == null,

            // correctly initialize the state
            feed = savedState.feed?.feed ?: Feed(filter, userService.selectedContentType),
            highlightedItemIds = savedState.highlightedItemIds?.toSet() ?: setOf(),
        )
    )

    private val loader = FeedManager(viewModelScope, feedService, feedState.value.feed)

    init {
        viewModelScope.launch { observeFeedUpdates() }
        viewModelScope.launch { observeSeenService() }
        viewModelScope.launch { observeSettings() }
        viewModelScope.launch { observePreloadState() }

        val placeholderCount = savedState.feed.orEmpty().count { it.placeholder }
        if (placeholderCount > 0) {
            logger.debug { "Schedule restore of $placeholderCount placeholder items." }
            viewModelScope.launch { restoreFeedItems() }

        } else {
            logger.debug { "Schedule new load of feed around item $loadAroundItemId" }

            // start loading the feed
            loader.restart(around = loadAroundItemId)
        }
    }

    private suspend fun observePreloadState() {
        preloadManager.items.sample(1_000).collect { items ->
            feedState.update { previousState -> previousState.copy(preloadedItemIds = items) }
        }
    }

    private suspend fun observeSettings() {
        Settings.changes { markItemsAsSeen }.collect { markAsSeen ->
            feedState.update { previousState -> previousState.copy(markItemsAsSeen = markAsSeen) }
        }
    }

    private suspend fun observeSeenService() {
        seenService.currentGeneration.collect {
            feedState.update { previousState ->
                val seen = previousState.feed.map { it.id }
                    .filterTo(HashSet()) { id -> seenService.isSeen(id) }
                previousState.copy(seen = seen)
            }
        }
    }

    private suspend fun observeFeedUpdates() {
        var lastLoadingSpace: FeedManager.LoadingSpace? = null

        loader.updates.collect { update ->
            trace { "observeFeedUpdates($update)" }

            when (update) {
                is FeedManager.Update.NewFeed -> {
                    val currentState = feedState.value

                    viewModelScope.launch {
                        // update repost info in background
                        refreshRepostInfos(currentState.feed, update.feed)
                    }

                    var autoScrollRef: ConsumableValue<ScrollRef>? = null

                    if (lastLoadingSpace == FeedManager.LoadingSpace.PREV) {
                        val firstOldItem = currentState.feed.firstOrNull()
                        if (firstOldItem != null) {
                            autoScrollRef = ConsumableValue(
                                ScrollRef(
                                    CommentRef(firstOldItem),
                                    keepScroll = true,
                                    smoothScroll = false
                                )
                            )
                        }
                    }

                    val highlightedItemIds = withContext(Dispatchers.Default) {
                        logger.time("Updating highlighted item ids") {
                            findHighlightedItemIds(update.feed, currentState.highlightedItemIds)
                        }
                    }

                    feedState.update { previousState ->
                        val feed = when {
                            previousState.cachedItemsById != null -> {
                                // replace placeholders with loaded items
                                val items = update.feed.mapNotNull { item ->
                                    if (item.placeholder) previousState.cachedItemsById[item.id] else item
                                }

                                update.feed.copy(items = items)
                            }

                            // show feed as is
                            else -> update.feed
                        }

                        previousState.copy(
                            feed = feed,
                            seen = feed.filter { item -> seenService.isSeen(item.id) }
                                .mapTo(HashSet()) { it.id },
                            empty = update.remote && feed.isEmpty(),
                            highlightedItemIds = highlightedItemIds,
                            autoScrollRef = autoScrollRef,
                            error = null,
                            errorConsumable = null,
                            missingContentType = null,
                            loading = null,
                        )
                    }
                }

                is FeedManager.Update.Error -> {
                    val error = update.err

                    logger.error("Error loading the feed", error)

                    feedState.update { previousState ->
                        val baseState = previousState.copy(
                            error = null, errorConsumable = null, missingContentType = null,
                        )

                        when (error) {
                            is FeedException.InvalidContentTypeException -> {
                                baseState.copy(missingContentType = error.requiredType)
                            }

                            is FeedException -> {
                                baseState.copy(errorConsumable = ConsumableValue(error))
                            }

                            else -> {
                                val errorValue: Throwable = when {
                                    error is JsonEncodingException ->
                                        StringException(error, R.string.could_not_load_feed_json)

                                    error.rootCause is ConnectException ->
                                        StringException(error, R.string.could_not_load_feed_https)

                                    else -> error
                                }

                                baseState.copy(error = errorValue)
                            }
                        }
                    }
                }

                is FeedManager.Update.LoadingStarted -> {
                    lastLoadingSpace = update.where
                    feedState.value = feedState.value.copy(loading = update.where)
                }

                FeedManager.Update.LoadingStopped -> {
                    feedState.value = feedState.value.copy(loading = null)
                }
            }
        }
    }

    private fun findHighlightedItemIds(feed: Feed, highlightedItemIds: Set<Long>): Set<Long> {
        val allItemIds = feed.mapTo(HashSet()) { it.id }

        // only build highlighted items if we don't have (a lot of) placeholders
        val placeholderCount = feed.items.count { it.placeholder }
        if (placeholderCount > feed.size / 3) {
            return highlightedItemIds
        }

        // list of items sorted by ascending points
        val topItems = feed.drop(10).dropLast(10)
            .filter { item -> isTopCandidate(item) }
            .sortedByDescending { it.up - it.down }

        // start with items that are actually still in the feed
        val result = highlightedItemIds.filterTo(HashSet()) { itemId -> itemId in allItemIds }

        // list of indices that are actually taken
        val indicesThatAreTaken = feed.mapIndexedNotNullTo(ArrayList()) { index, item ->
            index.takeIf { item.id in result }
        }

        outer@ while (result.size < feed.size / 50) {
            for (item in topItems) {
                // skip already highlighted items
                if (item.id in result) {
                    continue
                }

                val itemIndex = feed.indexOf(item)

                val isNearToAnyHighlightedItem = indicesThatAreTaken.any { takeIndex ->
                    abs(takeIndex - itemIndex) < 12
                }

                if (isNearToAnyHighlightedItem) {
                    continue
                }

                // looks good, lets take this item.
                result += item.id
                indicesThatAreTaken += itemIndex

                continue@outer
            }

            // we did not find a place for any item, give up now.
            break
        }

        return result
    }

    private fun isTopCandidate(item: FeedItem): Boolean {
        // only items with an aspect ratio of more than than 3/2 are candidates.
        if (item.width.toDouble() / item.height < 3.0 / 2.0) {
            return false
        }

        // only images & videos are candidates right now.
        return item.isImage || item.isVideo
    }

    /**
     *  Loads cached placeholder items from the database
     *  */
    private suspend fun restoreFeedItems() {
        val placeholders = feedState.value.feed.filter { it.placeholder }

        val (cachedItems, cachedVariants, cachedSubtitles) = runInterruptible(Dispatchers.IO) {
            logger.time("Loading ${placeholders.size} cached items") {
                val items = placeholders.chunked(256).flatMap { itemsChunk ->
                    val ids = itemsChunk.map { item -> item.id }
                    itemQueries.lookup(ids).executeAsList()
                }

                val variants = placeholders.chunked(256).flatMap { itemsChunk ->
                    val ids = itemsChunk.map { item -> item.id }
                    itemQueries.lookupVariants(ids).executeAsList()
                }

                val subtitles = placeholders.chunked(256).flatMap { itemsChunk ->
                    val ids = itemsChunk.map { item -> item.id }
                    itemQueries.lookupSubtitles(ids).executeAsList()
                }

                val groupedVariants = variants.groupBy { v -> v.itemId }
                val groupedSubtitles = subtitles.groupBy { v -> v.itemId }

                Triple(items, groupedVariants, groupedSubtitles)
            }
        }

        val byId = cachedItems.associateBy(
            keySelector = CachedItemInfo::id,
            valueTransform = { ci ->
                ci.toFeedItem(
                    variants = cachedVariants[ci.id] ?: listOf(),
                    subtitles = cachedSubtitles[ci.id] ?: listOf(),
                )
            },
        )

        feedState.update { previousState ->
            val newItems = feedState.value.feed.mapNotNull { item ->
                if (item.placeholder) byId[item.id] else item
            }

            previousState.copy(
                ready = true,
                cachedItemsById = byId,
                feed = previousState.feed.copy(items = newItems),
            )
        }
    }

    private suspend fun refreshRepostInfos(old: Feed, new: Feed) {
        trace { "refreshRepostInfos" }

        val filter = new.filter
        if (filter.feedType !== FeedType.NEW && filter.feedType !== FeedType.PROMOTED)
            return

        // check if query is too long to actually get the info info
        val queryIsTooLong = filter.tags?.split(Regex("\\s+"))?.count { it.isNotEmpty() }
        if (queryIsTooLong != null && queryIsTooLong >= 5)
            return

        // get the most recent item in the updated feed items
        val newestItem = (new - old).filter { !it.isPinned }.maxByOrNull { new.feedTypeId(it) }
            ?: return

        // add 'repost' to query
        val queryTerm = Tags.joinAnd("! 'repost'", filter.tags)

        // load repost info for the new items, starting at the most recent one
        val query = FeedService.FeedQuery(
            filter = filter.basicWithTags(queryTerm),
            contentTypes = new.contentType,
            older = new.feedTypeId(newestItem)
        )

        if (inMemoryCacheService.refreshRepostsCache(feedService, query)) {
            logger.debug { "Repost info was refreshed, updating state now" }
            feedState.value = feedState.value.copy(repostRefreshTime = System.currentTimeMillis())
        }
    }

    fun replaceCurrentFeed(feed: Feed) {
        feedState.update { previousState -> previousState.copy(feed = feed) }
    }

    fun restart(feed: Feed, aroundItemId: Long?) {
        loader.reset(feed)
        loader.restart(around = aroundItemId)
    }

    fun refresh() {
        loader.reset()
        loader.restart()
    }

    fun triggerLoadNext() {
        feedState.update { previousState ->
            previousState.copy(loading = FeedManager.LoadingSpace.NEXT)
        }

        loader.next()
    }

    fun triggerLoadPrev() {
        feedState.update { previousState ->
            previousState.copy(loading = FeedManager.LoadingSpace.PREV)
        }

        loader.previous()
    }

    fun updateScrollItemId(itemId: Long) {
        if (savedState.lastSavedId != itemId) {
            val feedState = feedState.value

            savedState.feed = feedState.feed.parcelAroundId(itemId)
            savedState.highlightedItemIds = feedState.highlightedItemIds.toLongArray()
            savedState.lastSavedId = itemId
        }
    }

    suspend fun findNextWith(matcher: (state: FeedState, itemId: Long) -> Boolean): FeedItem? {
        return feedState
            .flatMapConcat { feedState ->
                val targetItem = feedState.feed.firstOrNull { item ->
                    matcher(feedState, item.id) && !item.isPinned
                }

                when {
                    feedState.feed.isEmpty() && feedState.feed.isAtEnd -> {
                        flowOf(feedState.feed.lastOrNull())
                    }

                    feedState.feed.size > 4000 -> {
                        // no result within the first few pages
                        throw StringException(
                            "max scroll distance reached",
                            R.string.error_max_scroll_reached
                        )
                    }

                    targetItem != null -> {
                        flowOf(targetItem)
                    }

                    !feedState.isLoading -> {
                        // load next page!
                        triggerLoadNext()
                        emptyFlow()
                    }

                    // emit nothing, wait for next update
                    else -> emptyFlow()
                }
            }
            .firstOrNull()
    }

    class SavedState(handle: SavedStateHandle) : SavedStateAccessor(handle) {
        private var _lastSavedId by savedStateValue("lastSavedId", 0L)

        var feed: Feed.FeedParcel? by savedStateValue("feed")

        var highlightedItemIds: LongArray? by savedStateValue("highlightedItemIds")

        var lastSavedId: Long by observeChangeEx(_lastSavedId) { oldValue, newValue ->
            if (oldValue != newValue) {
                _lastSavedId = newValue
            }
        }
    }

    data class FeedState(
        val ready: Boolean,
        val feed: Feed,
        val seen: Set<Long> = setOf(),
        val errorStr: String? = null,
        val error: Throwable? = null,
        val errorConsumable: ConsumableValue<Throwable>? = null,
        val missingContentType: ContentType? = null,
        val loading: FeedManager.LoadingSpace? = null,
        val repostRefreshTime: Long = 0,
        val adsVisible: Boolean = false,
        val markItemsAsSeen: Boolean = Settings.markItemsAsSeen,
        val preloadedItemIds: LongSparseArray<PreloadManager.PreloadItem> = LongSparseArray(
            initialCapacity = 0
        ),
        val autoScrollRef: ConsumableValue<ScrollRef>? = null,
        val highlightedItemIds: Set<Long> = setOf(),
        val cachedItemsById: Map<Long, FeedItem>? = null,
        val empty: Boolean = false
    ) {
        val isLoading = loading != null
    }
}

private fun CachedItemInfo.toFeedItem(
    variants: List<CachedMediaVariant>,
    subtitles: List<CachedSubtitle>
): FeedItem {
    val mappedVariants = variants.map { v -> Api.Feed.Variant(name = v.name, path = v.path) }
    val mappedSubtitles =
        subtitles.map { s -> Api.Feed.Subtitle(language = s.language, path = s.path) }

    return FeedItem(
        id = id,
        promotedId = promotedId,
        path = image,
        fullsize = fullsize,
        thumbnail = thumbnail,
        user = user,
        userId = userId,
        created = Instant.ofEpochSeconds(created),
        width = width,
        height = height,
        up = up,
        down = down,
        mark = mark,
        flags = flags,
        audio = audio,
        deleted = deleted,
        variants = mappedVariants,
        subtitles = mappedSubtitles,
        placeholder = false,
    )
}

inline fun <T> MutableStateFlow<T>.update(block: (previousState: T) -> T) {
    value = block(value)
}

inline fun <T> LazyStateFlow<T>.update(block: (previousState: T) -> T) {
    send(block(value))
}

