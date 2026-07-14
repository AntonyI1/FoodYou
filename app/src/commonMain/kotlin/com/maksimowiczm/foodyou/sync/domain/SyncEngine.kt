package com.maksimowiczm.foodyou.sync.domain

interface SyncEngine {
    /** Run one full sync cycle (push local changes, pull remote, reconcile goals). */
    suspend fun sync(): SyncResult

    /** Probe the server's health endpoint with the given credentials. */
    suspend fun testConnection(baseUrl: String, token: String): Boolean
}
