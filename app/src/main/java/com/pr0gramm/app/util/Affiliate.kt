package com.pr0gramm.app.util

import android.content.Context
import android.util.Base64
import com.pr0gramm.app.model.config.Config
import com.pr0gramm.app.services.config.ConfigService

object Affiliate {
    private val reAffiliate by memorize<Context, Regex> { context ->
        try {
            ConfigService.get(context).reAffiliate.toRegex()
        } catch (err: Exception) {

            // need to return something
            Config().reAffiliate.toRegex()
        }
    }

    fun get(context: Context, url: String): String? {
        return if (reAffiliate(context).containsMatchIn(url)) {
            return url
        } else {
            null
        }
    }
}