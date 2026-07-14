package com.maksimowiczm.foodyou.sync.domain

import kotlinx.coroutines.flow.Flow

/** Stores the bearer token, encrypted at rest via the platform master key. */
interface SyncTokenRepository {
    suspend fun setToken(token: String)

    suspend fun getToken(): String?

    suspend fun clear()

    fun hasToken(): Flow<Boolean>
}
