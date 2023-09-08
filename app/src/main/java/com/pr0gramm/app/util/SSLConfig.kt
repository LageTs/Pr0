package com.pr0gramm.app.util

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import com.pr0gramm.app.Logger
import com.pr0gramm.app.time
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

//Setting testMode configuration. If set as testMode, the connection will skip certification check
fun OkHttpClient.Builder.configureSSLSocketFactoryAndSecurity(app: Application): OkHttpClient.Builder {
    val logger = Logger("OkHttpSSL")

    // try to install certificates on android 5.1+
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
        if (installGmsTrustCertificates(app, logger)) {
            return this
        }
    }

    // Okay fuck, we don't have the google services available, and ssl doesn't work on older
    // android versions. What do we do now? We allow everything... well, fuck it!
    logger.warn { "Disable validity checking of SSL certificates" }

    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        }
    })

    // Install the all-trusting trust manager
    val sslContext = SSLContext.getInstance("SSL")
    sslContext.init(null, trustAllCerts, SecureRandom())

    // Create an ssl socket factory with our all-trusting manager
    sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)

    // also don't do host name verification either
    // hostnameVerifier { hostname, session -> true }

    return this
}

private fun installGmsTrustCertificates(app: Application, logger: Logger): Boolean {
    return false
}


