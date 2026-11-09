package com.amresalehin.emregallery.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.amresalehin.emregallery.data.CloudAiConnectionTester
import com.amresalehin.emregallery.data.CloudAiProvider
import com.amresalehin.emregallery.data.CloudProviderStatus
import com.amresalehin.emregallery.data.FlorenceModelManager
import com.amresalehin.emregallery.data.FlorenceModelState
import com.amresalehin.emregallery.data.galleryDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

class AiPreferencesManager(private val context: Context) {

    private val cloudProvidersKey = stringPreferencesKey("cloud_ai_providers_v1")
    private val activeProviderIdKey = stringPreferencesKey("active_cloud_ai_provider_id")

    val cloudProviders: Flow<List<CloudAiProvider>> = context.galleryDataStore.data.map { prefs ->
        val raw = prefs[cloudProvidersKey] ?: return@map emptyList()
        parseProvidersJson(raw)
    }.distinctUntilChanged()

    val activeProviderId: Flow<String?> = context.galleryDataStore.data.map { prefs ->
        prefs[activeProviderIdKey]
    }.distinctUntilChanged()

    val activeProvider: Flow<CloudAiProvider?> = context.galleryDataStore.data.map { prefs ->
        val providers = parseProvidersJson(prefs[cloudProvidersKey] ?: "")
        val activeId = prefs[activeProviderIdKey]
        providers.firstOrNull { it.id == activeId } ?: providers.firstOrNull { it.isActive }
    }.distinctUntilChanged()

    val florenceModelState: Flow<FlorenceModelState> = FlorenceModelManager.modelState

    suspend fun saveProvider(provider: CloudAiProvider) {
        context.galleryDataStore.edit { prefs ->
            val currentList = parseProvidersJson(prefs[cloudProvidersKey] ?: "").toMutableList()
            val existingIndex = currentList.indexOfFirst { it.id == provider.id }

            val updatedProvider = if (currentList.isEmpty() || provider.isActive) {
                // If this is the only provider or marked active, deactivate others
                currentList.indices.forEach { i ->
                    currentList[i] = currentList[i].copy(isActive = false)
                }
                provider.copy(isActive = true)
            } else {
                provider
            }

            if (existingIndex >= 0) {
                currentList[existingIndex] = updatedProvider
            } else {
                currentList.add(updatedProvider)
            }

            prefs[cloudProvidersKey] = serializeProvidersJson(currentList)
            if (updatedProvider.isActive) {
                prefs[activeProviderIdKey] = updatedProvider.id
            }
        }
    }

    suspend fun setActiveProvider(providerId: String) {
        context.galleryDataStore.edit { prefs ->
            val currentList = parseProvidersJson(prefs[cloudProvidersKey] ?: "").map {
                it.copy(isActive = (it.id == providerId))
            }
            prefs[cloudProvidersKey] = serializeProvidersJson(currentList)
            prefs[activeProviderIdKey] = providerId
        }
    }

    suspend fun deleteProvider(providerId: String) {
        context.galleryDataStore.edit { prefs ->
            val currentList = parseProvidersJson(prefs[cloudProvidersKey] ?: "").filterNot { it.id == providerId }
            var activeId = prefs[activeProviderIdKey]
            if (activeId == providerId) {
                activeId = currentList.firstOrNull()?.id
            }
            val remapped = currentList.map {
                it.copy(isActive = (it.id == activeId))
            }
            prefs[cloudProvidersKey] = serializeProvidersJson(remapped)
            if (activeId != null) {
                prefs[activeProviderIdKey] = activeId
            } else {
                prefs.remove(activeProviderIdKey)
            }
        }
    }

    suspend fun updateProviderStatus(
        providerId: String,
        status: CloudProviderStatus,
        message: String?
    ) {
        context.galleryDataStore.edit { prefs ->
            val currentList = parseProvidersJson(prefs[cloudProvidersKey] ?: "").map { p ->
                if (p.id == providerId) {
                    p.copy(
                        status = status,
                        statusMessage = message,
                        lastCheckedTimestamp = System.currentTimeMillis()
                    )
                } else {
                    p
                }
            }
            prefs[cloudProvidersKey] = serializeProvidersJson(currentList)
        }
    }

    suspend fun testProvider(providerId: String): CloudProviderStatus {
        val providers = parseProvidersJson(context.galleryDataStore.data.first()[cloudProvidersKey] ?: "")
        val target = providers.firstOrNull { it.id == providerId } ?: return CloudProviderStatus.UNCONFIGURED

        updateProviderStatus(providerId, CloudProviderStatus.CHECKING, "Testing connection...")
        val result = CloudAiConnectionTester.testConnection(target)
        updateProviderStatus(providerId, result.status, result.message)
        return result.status
    }

    suspend fun testActiveProvider(): CloudProviderStatus {
        val prefs = context.galleryDataStore.data.first()
        val providers = parseProvidersJson(prefs[cloudProvidersKey] ?: "")
        val activeId = prefs[activeProviderIdKey] ?: providers.firstOrNull { it.isActive }?.id
        return if (activeId != null) {
            testProvider(activeId)
        } else {
            CloudProviderStatus.UNCONFIGURED
        }
    }

    private fun parseProvidersJson(jsonStr: String): List<CloudAiProvider> {
        if (jsonStr.isBlank()) return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<CloudAiProvider>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val statusStr = obj.optString("status", CloudProviderStatus.UNCONFIGURED.name)
                val status = runCatching { CloudProviderStatus.valueOf(statusStr) }.getOrDefault(CloudProviderStatus.UNCONFIGURED)
                list.add(
                    CloudAiProvider(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        baseUrl = obj.getString("baseUrl"),
                        apiKey = obj.optString("apiKey", ""),
                        modelId = obj.getString("modelId"),
                        isActive = obj.optBoolean("isActive", false),
                        status = status,
                        statusMessage = obj.optString("statusMessage").ifBlank { null },
                        lastCheckedTimestamp = obj.optLong("lastCheckedTimestamp", 0L)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeProvidersJson(list: List<CloudAiProvider>): String {
        val array = JSONArray()
        list.forEach { p ->
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("baseUrl", p.baseUrl)
                put("apiKey", p.apiKey)
                put("modelId", p.modelId)
                put("isActive", p.isActive)
                put("status", p.status.name)
                put("statusMessage", p.statusMessage ?: "")
                put("lastCheckedTimestamp", p.lastCheckedTimestamp)
            }
            array.put(obj)
        }
        return array.toString()
    }
}
