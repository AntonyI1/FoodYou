package com.maksimowiczm.foodyou.sync.domain

/**
 * Typed transport failures so the engine/worker can classify retry vs terminal without string
 * matching. HTTP-status-derived; network/timeout failures surface as their underlying IO
 * exceptions (transient) and are not modelled here.
 */
sealed class SyncException(message: String, val retryable: Boolean) : Exception(message) {

    /** 401/403 — the token is missing or wrong. Terminal: retrying won't help. */
    class Unauthorized : SyncException("Unauthorized — check the access token", retryable = false)

    /** Other 4xx — the request was rejected. Terminal. */
    class ClientError(val status: Int) : SyncException("Request rejected ($status)", retryable = false)

    /** 5xx — the server is unhealthy. Transient: worth a retry. */
    class ServerError(val status: Int) : SyncException("Server error ($status)", retryable = true)
}
