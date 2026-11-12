package com.fraudcalldetector.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Interceptor for adding API keys or Bearer tokens.
 * Teammates integrating the AI backend should set the auth token here.
 */
class AuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            // TODO: inject API key from secure storage (DataStore / BuildConfig)
            // .header("Authorization", "Bearer $apiKey")
            .build()
        return chain.proceed(request)
    }
}
