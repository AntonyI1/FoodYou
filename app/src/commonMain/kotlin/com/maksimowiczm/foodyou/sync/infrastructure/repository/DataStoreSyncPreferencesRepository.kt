package com.maksimowiczm.foodyou.sync.infrastructure.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maksimowiczm.foodyou.common.infrastructure.datastore.AbstractDataStoreUserPreferencesRepository
import com.maksimowiczm.foodyou.common.infrastructure.datastore.set
import com.maksimowiczm.foodyou.sync.domain.SyncPreferences

internal class DataStoreSyncPreferencesRepository(dataStore: DataStore<Preferences>) :
    AbstractDataStoreUserPreferencesRepository<SyncPreferences>(dataStore) {

    override fun Preferences.toUserPreferences(): SyncPreferences =
        SyncPreferences(
            serverUrl = this[Keys.ServerUrl] ?: "",
            enabled = this[Keys.Enabled] ?: false,
            cursor = this[Keys.Cursor],
            lastSyncEpochSeconds = this[Keys.LastSync],
            lastError = this[Keys.LastError],
            goalKcal = this[Keys.GoalKcal],
            goalProteinG = this[Keys.GoalProtein],
            goalCarbsG = this[Keys.GoalCarbs],
            goalFatG = this[Keys.GoalFat],
        )

    override fun MutablePreferences.applyUserPreferences(updated: SyncPreferences) {
        this[Keys.ServerUrl] = updated.serverUrl
        this[Keys.Enabled] = updated.enabled
        this[Keys.Cursor] = updated.cursor
        this[Keys.LastSync] = updated.lastSyncEpochSeconds
        this[Keys.LastError] = updated.lastError
        this[Keys.GoalKcal] = updated.goalKcal
        this[Keys.GoalProtein] = updated.goalProteinG
        this[Keys.GoalCarbs] = updated.goalCarbsG
        this[Keys.GoalFat] = updated.goalFatG
    }

    private object Keys {
        val ServerUrl = stringPreferencesKey("sync:server_url")
        val Enabled = booleanPreferencesKey("sync:enabled")
        val Cursor = stringPreferencesKey("sync:cursor")
        val LastSync = longPreferencesKey("sync:last_sync_epoch")
        val LastError = stringPreferencesKey("sync:last_error")
        val GoalKcal = doublePreferencesKey("sync:goal_kcal")
        val GoalProtein = doublePreferencesKey("sync:goal_protein")
        val GoalCarbs = doublePreferencesKey("sync:goal_carbs")
        val GoalFat = doublePreferencesKey("sync:goal_fat")
    }
}
