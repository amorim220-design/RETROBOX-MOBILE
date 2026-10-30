package com.retrobox.catalog

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP compartilhado (catálogo + downloads + capas).
 *
 * TLS pinning é opcional: se o build definir `RETROBOX_CATALOG_PIN`
 * (`sha256/AAAA...=`) e `RETROBOX_CATALOG_PIN_HOST`, o pin é aplicado somente
 * àquele host — assim as capas (outro host) continuam funcionando.
 */
object HttpClientProvider {

    val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)

        val pin = com.retrobox.BuildConfig.CATALOG_CERT_PIN.trim()
        val host = com.retrobox.BuildConfig.CATALOG_PIN_HOST.trim()
        if (pin.isNotEmpty() && host.isNotEmpty()) {
            runCatching {
                builder.certificatePinner(
                    okhttp3.CertificatePinner.Builder().add(host, pin).build()
                )
            }
        }
        builder.build()
    }
}
