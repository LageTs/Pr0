package com.pr0gramm.app.sync

import com.pr0gramm.app.*
import com.pr0gramm.app.services.*
import com.pr0gramm.app.ui.base.AsyncScope
import com.pr0gramm.app.util.catchAll
import com.pr0gramm.app.util.unless
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis
import kotlin.time.ExperimentalTime


@OptIn(ExperimentalTime::class)
class SyncService(private val userService: UserService,
                  private val notificationService: NotificationService,
                  private val singleShotService: SingleShotService,
                  private val seenService: SeenService,
                  private val seenApiService: SeenApiService) {

    private val logger = Logger("SyncService")

    private val seenSyncLock = AtomicBoolean()


    init {
        AsyncScope.launch {
            userService.loginStates
                    .map { state -> state.id }
                    .distinctUntilChanged()
                    .onStart { delay(1.seconds) }
                    .collect { launch { performSyncSeenService() } }
        }
    }

    suspend fun dailySync() {
        Stats().incrementCounter("jobs.sync-stats")

        logger.info { "Doing some statistics related trackings" }

        catchAll {
            UpdateChecker().queryAll().let { response ->
                if (response is UpdateChecker.Response.UpdateAvailable) {
                    notificationService.showUpdateNotification(response.update)
                }
            }
        }
    }

    suspend fun sync() {
        Stats().time("jobs.sync.time", measureTimeMillis {
            Stats().incrementCounter("jobs.sync")

            if (!userService.isAuthorized) {
                logger.info { "Will not sync now - user is not signed in." }
                return
            }

            logger.info { "Performing a sync operation now" }

            logger.time("Sync operation") {
                catchAll {
                    syncCachedUserInfo()
                }

                catchAll {
                    syncUserState()
                }

                catchAll {
                    syncSeenService()
                }
            }
        })
    }

    private suspend fun syncCachedUserInfo() {
        if (singleShotService.firstTimeToday("update-userInfo")) {
            logger.info { "Update current user info" }

            catchAll {
                userService.updateCachedUserInfo()
            }
        }
    }

    private suspend fun syncUserState() {
        logger.info { "Sync with pr0gramm api" }

        val sync = userService.sync() ?: return

        if (sync.inbox.total > 0) {
            notificationService.showUnreadMessagesNotification()
        } else {
            // remove if no messages are found
            notificationService.cancelForAllUnread()
        }
    }

    private suspend fun syncSeenService() {
        val shouldSync = Settings.backup && if (Settings.markItemsAsSeen) {
            singleShotService.firstTimeInHour("sync-seen")
        } else {
            singleShotService.firstTimeToday("sync-seen")
        }

        if (shouldSync) {
            performSyncSeenService()
        }
    }

    private suspend fun performSyncSeenService() {
        unless(Settings.backup && seenSyncLock.compareAndSet(false, true)) {
            logger.info { "Not starting sync of seen bits." }
            return
        }

        logger.info { "Syncing of seen bits" }

        try {
            seenApiService.update { previous ->
                // merge the previous state into the current seen service
                val noChanges = previous != null && seenService.checkEqualAndMerge(previous)

                if (noChanges) {
                    logger.info { "No seen bits changed, so wont push now" }
                    null
                } else {
                    logger.info { "Seen bits look dirty, pushing now" }
                    seenService.export().takeIf { it.isNotEmpty() }
                }
            }

        } catch (err: SeenApiService.VersionConflictException) {
            // we should just retry.
            logger.warn(err) { "Version conflict during update." }

        } catch (err: Exception) {
            Stats().incrementCounter("seen.sync.error")
            throw err

        } finally {
            seenSyncLock.set(false)
        }
    }
}
