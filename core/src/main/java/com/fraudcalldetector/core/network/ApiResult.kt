package com.fraudcalldetector.core.network

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val code: Int, val message: String) : ApiResult<Nothing>()
    data class Exception(val throwable: Throwable) : ApiResult<Nothing>()

    val isSuccess get() = this is Success
    val isError get() = this is Error || this is Exception

    fun getOrNull(): T? = if (this is Success) data else null

    fun getErrorMessage(): String = when (this) {
        is Error -> message
        is Exception -> throwable.localizedMessage ?: "Unknown error"
        else -> ""
    }
}
