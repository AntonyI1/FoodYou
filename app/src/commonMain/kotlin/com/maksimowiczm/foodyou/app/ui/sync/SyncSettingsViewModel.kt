package com.maksimowiczm.foodyou.app.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maksimowiczm.foodyou.common.domain.userpreferences.UserPreferencesRepository
import com.maksimowiczm.foodyou.sync.domain.SyncEngine
import com.maksimowiczm.foodyou.sync.domain.SyncPreferences
import com.maksimowiczm.foodyou.sync.domain.SyncRunner
import com.maksimowiczm.foodyou.sync.domain.SyncTokenRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class SyncSettingsModel(
    val serverUrl: String = "",
    val enabled: Boolean = false,
    val hasToken: Boolean = false,
    val lastSyncEpochSeconds: Long? = null,
    val lastError: String? = null,
    val syncing: Boolean = false,
)

internal class SyncSettingsViewModel(
    private val preferencesRepository: UserPreferencesRepository<SyncPreferences>,
    private val tokenRepository: SyncTokenRepository,
    private val syncEngine: SyncEngine,
    private val syncRunner: SyncRunner,
) : ViewModel() {

    private val syncing = MutableStateFlow(false)

    val state =
        combine(preferencesRepository.observe(), tokenRepository.hasToken(), syncing) {
                preferences,
                hasToken,
                isSyncing ->
                SyncSettingsModel(
                    serverUrl = preferences.serverUrl,
                    enabled = preferences.enabled,
                    hasToken = hasToken,
                    lastSyncEpochSeconds = preferences.lastSyncEpochSeconds,
                    lastError = preferences.lastError,
                    syncing = isSyncing,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(2_000),
                initialValue = SyncSettingsModel(),
            )

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { preferencesRepository.update { copy(enabled = enabled) } }
    }

    fun syncNow() {
        viewModelScope.launch {
            syncing.value = true
            try {
                syncRunner.runSync()
            } finally {
                syncing.value = false
            }
        }
    }

    /** The persisted server URL, read once to seed the settings text field. */
    suspend fun currentServerUrl(): String = preferencesRepository.observe().first().serverUrl

    /**
     * Normalizes and persists the [url] the user entered (auto-prefixing a scheme so scheme-less
     * input doesn't yield a cryptic Ktor failure), stores a newly entered token, then probes the
     * server. The URL is taken straight from the field rather than read back from preferences, so the
     * value under test is exactly what was typed — the field owns its own state (see
     * SyncSettingsScreen); routing it through the async preferences round-trip is what reset the text
     * cursor and made typing come out reversed.
     */
    suspend fun saveAndTestConnection(url: String, token: String): Boolean {
        val normalized = normalizeUrl(url)
        preferencesRepository.update { copy(serverUrl = normalized) }
        if (token.isNotBlank()) tokenRepository.setToken(token.trim())
        if (normalized.isBlank()) return false
        val savedToken = tokenRepository.getToken() ?: return false
        return syncEngine.testConnection(normalized, savedToken)
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.isBlank() -> ""
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "http://$trimmed"
        }
    }
}
