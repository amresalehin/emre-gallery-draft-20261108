package com.amresalehin.emregallery.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.amresalehin.emregallery.data.galleryDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Manages secure credentials, locked folder PIN hash, and biometric toggles.
 */
class VaultPreferencesManager(private val context: Context) {

    private val lockedFolderPinKey = stringPreferencesKey("locked_folder_pin_hash")
    private val lockedFolderBiometricKey = booleanPreferencesKey("locked_folder_biometric_enabled")

    val lockedFolderPinHash: Flow<String?> = context.galleryDataStore.data.map {
        it[lockedFolderPinKey]
    }.distinctUntilChanged()

    val lockedFolderBiometricEnabled: Flow<Boolean> = context.galleryDataStore.data.map {
        it[lockedFolderBiometricKey] ?: true
    }.distinctUntilChanged()

    suspend fun setLockedFolderPin(pin: String) {
        val hash = hashPin(pin.trim())
        context.galleryDataStore.edit { prefs ->
            prefs[lockedFolderPinKey] = hash
        }
    }

    suspend fun verifyLockedFolderPin(pin: String): Boolean {
        val stored = lockedFolderPinHash.first() ?: return false
        return stored == hashPin(pin.trim())
    }

    suspend fun clearLockedFolderPin() {
        context.galleryDataStore.edit { prefs ->
            prefs.remove(lockedFolderPinKey)
        }
    }

    suspend fun setLockedFolderBiometricEnabled(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[lockedFolderBiometricKey] = enabled
        }
    }

    private fun hashPin(pin: String): String {
        val salt = "EmreGallerySecureSalt2026"
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest((salt + pin).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
