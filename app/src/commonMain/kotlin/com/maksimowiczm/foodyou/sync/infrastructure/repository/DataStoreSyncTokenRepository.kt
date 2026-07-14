package com.maksimowiczm.foodyou.sync.infrastructure.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import com.maksimowiczm.foodyou.common.crypto.MasterCrypto
import com.maksimowiczm.foodyou.sync.domain.SyncTokenRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Bearer token encrypted with the hardware-backed master key, mirroring OpenFoodFacts creds. */
internal class DataStoreSyncTokenRepository(
    private val dataStore: DataStore<Preferences>,
    private val masterCrypto: MasterCrypto,
) : SyncTokenRepository {

    override suspend fun setToken(token: String) {
        dataStore.edit { it[TOKEN_KEY] = masterCrypto.encrypt(token.encodeToByteArray()) }
    }

    override suspend fun getToken(): String? {
        val encrypted = dataStore.data.first()[TOKEN_KEY] ?: return null
        return masterCrypto.decrypt(encrypted).decodeToString()
    }

    override fun hasToken(): Flow<Boolean> = dataStore.data.map { TOKEN_KEY in it }

    private companion object {
        val TOKEN_KEY = byteArrayPreferencesKey("sync:token")
    }
}
