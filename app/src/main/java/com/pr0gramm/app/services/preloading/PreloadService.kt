package com.pr0gramm.app.services.preloading

import android.annotation.SuppressLint
import android.app.IntentService
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.pr0gramm.app.*
import com.pr0gramm.app.api.pr0gramm.asThumbnail
import com.pr0gramm.app.feed.FeedItem
import com.pr0gramm.app.services.NotificationService.Types
import com.pr0gramm.app.services.OnceEvery
import com.pr0gramm.app.services.UriHelper
import com.pr0gramm.app.util.*
import com.pr0gramm.app.util.di.LazyInjectorAware
import com.pr0gramm.app.util.di.PropertyInjector
import com.pr0gramm.app.util.di.instance
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.util.concurrent.TimeUnit


/**
 * This service handles preloading and resolving of preloaded images.
 */
class PreloadService : IntentService("PreloadService"), LazyInjectorAware {
    override val injector: PropertyInjector = PropertyInjector()

    private val httpClient: OkHttpClient by instance()
    private val preloadManager: PreloadManager by instance()

    @Volatile
    private var canceled: Boolean = false

    private var jobId: Long = 0
    private val updateNotificationSchedule = OnceEvery(Duration.millis(500))

    private val preloadCache: File by lazy {
        File(cacheDir, "preload").also { path ->
            if (!path.exists() && path.mkdirs()) {
                logger.debug { "preload directory created at ${this}" }
            }
        }
    }

    private val notification by lazy {
        NotificationCompat.Builder(this, Types.Preload.channel)
            .setContentTitle(getString(R.string.preload_ongoing))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setTicker("")
    }

    override fun onCreate() {
        super.onCreate()

        injector.inject(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // send out the initial notification and bring the service into foreground mode!
        startForeground(Types.Preload.id, notification.build())

        if (intent?.getLongExtra(EXTRA_CANCEL, -1) == jobId) {
            canceled = true
            return Service.START_NOT_STICKY
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onHandleIntent(intent: Intent?) {
        checkNotMainThread()

        val items = parseFeedItemsFromIntent(intent)
        if (intent == null || items.isEmpty())
            return

        jobId = System.currentTimeMillis()
        canceled = false

        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PreloadService::class.java).putExtra(EXTRA_CANCEL, jobId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // update notification
        show {
            val icon = R.drawable.ic_white_action_clear
            addAction(icon, getString(R.string.cancel), cancelIntent)

            setProgress(100 * items.size, 0, false)
        }

        // create a wake lock
        val wakeLock = getSystemService<PowerManager>()!!.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "pr0:preloadService"
        )

        try {
            logger.info { "Acquire wake lock for at most 10 minutes" }
            wakeLock.use(10, TimeUnit.MINUTES) {
                val allowOnlyOnMobile = !intent.getBooleanExtra(
                    EXTRA_ALLOW_ON_MOBILE, false
                )

                preloadItemsLocked(items, allowOnlyOnMobile)
            }

        } catch (error: Throwable) {
            notification.setContentTitle(getString(R.string.preload_failed))

        } finally {
            logger.info { "Preloading finished" }

            // clear the action button in the notification
            clearNotificationActions()

            show {
                setSmallIcon(android.R.drawable.stat_sys_download_done)
                setSubText(null)
                setProgress(0, 0, false)
                setOngoing(false)
                setContentIntent(null)
            }

            stopForeground(false)
        }
    }

    class Item(
        val id: Long,
        val mediaUri: Uri,
        val thumbUri: Uri,
        val fullThumbUri: Uri?,
    )

    private fun preloadItemsLocked(items: List<Item>, allowOnlyOnMobile: Boolean) {
        var statsFailed = 0
        var statsDownloaded = 0
        val startTime = Instant.now()

        var idx = 0
        while (idx < items.size && !canceled) {
            if (allowOnlyOnMobile && AndroidUtility.isOnMobile(this))
                break

            val item = items[idx]
            try {
                val mediaUri = item.mediaUri
                val mediaFile = if (mediaUri.isLocalFile) mediaUri.toFile() else cacheFileForUri(mediaUri)

                val thumbUri = item.thumbUri
                val thumbFile = if (thumbUri.isLocalFile) thumbUri.toFile() else cacheFileForUri(thumbUri)

                // update the notification
                maybeShow {
                    setProgress(items.size, idx, false)
                }

                if (!mediaUri.isLocalFile) {
                    download(3 * idx, 3 * items.size, mediaUri, mediaFile)
                }

                if (!thumbUri.isLocalFile) {
                    download(3 * idx + 1, 3 * items.size, thumbUri, thumbFile)
                }

                var fullThumbFile: File? = null
                if (item.fullThumbUri != null) {
                    val fullThumbUri = item.fullThumbUri
                    val fullThumbFileTemp =
                        if (fullThumbUri.isLocalFile) thumbUri.toFile() else cacheFileForUri(thumbUri)

                    try {
                        if (!fullThumbUri.isLocalFile) {
                            download(3 * idx + 2, 3 * items.size, fullThumbUri, fullThumbFileTemp)
                        }

                        fullThumbFile = fullThumbFileTemp

                    } catch (err: IOException) {
                        // if the extended thumbnail is not available, just ignore the error.
                        logger.warn { "Full thumbnail $fullThumbUri not available" }
                    }
                }

                // store entry in the database
                preloadManager.store(
                    PreloadManager.PreloadItem(
                        item.id, startTime, mediaFile, thumbFile, fullThumbFile
                    )
                )

                statsDownloaded += 1

            } catch (err: IOException) {
                statsFailed += 1
                logger.warn("Could not preload image id=" + item.id, err)
            }

            idx++
        }

        // doing cleanup
        doCleanup(olderThan = startTime - Duration.days(1))

        // setting end message
        showEndMessage(statsDownloaded, statsFailed)
    }

    @SuppressLint("RestrictedApi")
    private fun clearNotificationActions() {
        notification.mActions.clear()
    }

    private fun parseFeedItemsFromIntent(intent: Intent?): List<Item> {
        val serialized = intent?.extras?.getByteArray(SERIALIZED_EXTRA_LIST_OF_ITEMS)
            ?: return listOf()
        return deserializeItems(serialized)
    }

    private fun showEndMessage(downloaded: Int, failed: Int) {
        val contentText = mutableListOf<String>()
        contentText += getString(R.string.preload_sub_downloaded, downloaded)

        if (failed > 0) {
            contentText += getString(R.string.preload_sub_failed, failed)
        }

        if (canceled) {
            contentText += getString(R.string.preload_canceled)
        }

        show {
            setProgress(0, 0, true)
            setContentTitle(getString(R.string.preload_finished))
            setContentText(contentText.joinToString(", "))
        }
    }

    /**
     * Cleaning old files before the given threshold.
     */
    private fun doCleanup(olderThan: Instant) {
        show {
            setContentText(getString(R.string.preload_cleanup))
            setProgress(1, 0, true)
        }

        preloadManager.deleteOlderThan(olderThan)
    }

    private fun download(index: Int, total: Int, uri: Uri, targetFile: File) {

        // if the file exists, we dont need to download it again
        if (targetFile.exists()) {
            logger.debug { "File $targetFile already exists" }

            if (!targetFile.updateTimestamp()) {
                logger.warn { "Could not touch file $targetFile" }
            }

            return
        }

        val tempFile = File(targetFile.path + ".tmp")
        try {
            download(uri, tempFile) { progress ->
                val progressCurrent = (100 * (index + progress)).toInt()
                val progressTotal = 100 * total

                val msg = if (canceled) {
                    getString(R.string.preload_sub_finished)
                } else {
                    getString(R.string.preload_fetching, uri.path)
                }

                maybeShow {
                    setContentText(msg)
                    setProgress(progressTotal, progressCurrent, false)
                }
            }

            if (!tempFile.renameTo(targetFile))
                throw IOException("Could not rename file")

        } catch (error: Throwable) {
            if (!tempFile.delete())
                logger.warn { "Could not remove temporary file" }

            throw error
        }
    }

    private inline fun show(config: NotificationCompat.Builder.() -> Unit) {
        notification.config()
        NotificationManagerCompat
            .from(this)
            .notify(Types.Preload.id, notification.build())
    }

    private inline fun maybeShow(config: NotificationCompat.Builder.() -> Unit) {
        updateNotificationSchedule { show(config) }
    }

    private fun download(uri: Uri, targetFile: File, progress: (Float) -> Unit) {
        logger.info { "Start downloading $uri to $targetFile" }

        val request = Request.Builder().get().url(uri.toString()).build()
        val response = httpClient.newCall(request).execute()

        response.body?.use { body ->
            val contentLength = body.contentLength()

            body.byteStream().use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    if (contentLength < 0) {
                        progress(0.0f)
                        inputStream.copyTo(outputStream)
                        progress(1.0f)
                    } else {
                        copyWithProgress(progress, contentLength, inputStream, outputStream)
                    }
                }
            }
        }
    }

    /**
     * Copies from the input stream to the output stream.
     * The progress is written to the given observable.
     */
    private fun copyWithProgress(
        progress: (Float) -> Unit, contentLength: Long,
        inputStream: InputStream, outputStream: OutputStream
    ) {

        var totalCount: Long = 0
        readStream(inputStream) { buffer, count ->
            outputStream.write(buffer, 0, count)

            totalCount += count.toLong()
            progress(totalCount.toFloat() / contentLength)
        }
    }

    /**
     * Name of the cache file for the given [Uri].
     */
    private fun cacheFileForUri(uri: Uri): File {
        return File(
            preloadCache, uri.toString()
                .replaceFirst("https?://".toRegex(), "")
                .replace("[^0-9a-zA-Z.]+".toRegex(), "_")
        )
    }

    companion object {
        private val logger = Logger("PreloadService")

        private const val SERIALIZED_EXTRA_LIST_OF_ITEMS = "PreloadService.listOfItems"
        private const val EXTRA_CANCEL = "PreloadService.cancel"
        private const val EXTRA_ALLOW_ON_MOBILE = "PreloadService.allowOnMobile"

        fun preload(context: Context, items: List<FeedItem>, allowOnMobile: Boolean) {
            val intent = Intent(context, PreloadService::class.java)
            intent.putExtra(EXTRA_ALLOW_ON_MOBILE, allowOnMobile)
            intent.putExtra(SERIALIZED_EXTRA_LIST_OF_ITEMS, serializeItems(context, items))
            ContextCompat.startForegroundService(context, intent)
        }

        private fun deserializeItems(bytes: ByteArray): List<Item> {
            return Serde.deserialize(bytes) { input ->
                listOfSize(input.readInt()) {
                    Item(
                        id = input.readLong(),
                        mediaUri = input.readUTF().toUri(),
                        thumbUri = input.readUTF().toUri(),
                        fullThumbUri = input.readUTF().ifEmpty { null }?.toUri(),
                    )
                }
            }
        }

        private fun serializeItems(context: Context, items: List<FeedItem>): ByteArray {
            val uris = UriHelper.of(context)

            val bytes = logger.time("Serialize items") {
                Serde.serialize { out ->
                    out.writeInt(items.size)

                    for (item in items) {
                        out.writeLong(item.id)
                        out.writeUTF(uris.media(item).toString())
                        out.writeUTF(uris.thumbnail(item.asThumbnail()).toString())
                        out.writeUTF(if (item.isVideo) uris.fullThumbnail(item.asThumbnail()).toString() else "")
                    }
                }
            }

            logger.info { "Serialized ${items.size} items as ${bytes.size} bytes" }

            return bytes
        }
    }
}
