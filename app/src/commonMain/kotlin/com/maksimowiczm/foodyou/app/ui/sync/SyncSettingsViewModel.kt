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

    fun setServerUrl(url: String) {
        viewModelScope.launch { preferencesRepository.update { copy(serverUrl = url.trim()) } }
    }

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

    /** Persists a newly entered token (if any) and probes the server. Runs sequentially. */
    suspend fun saveAndTestConnection(token: String): Boolean {
        if (token.isNotBlank()) tokenRepository.setToken(token.trim())
        val serverUrl = preferencesRepository.observe().first().serverUrl
        val savedToken = tokenRepository.getToken() ?: return false
        return syncEngine.testConnection(serverUrl, savedToken)
    }
}
