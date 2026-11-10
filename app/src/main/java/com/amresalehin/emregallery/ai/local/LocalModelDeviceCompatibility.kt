package com.amresalehin.emregallery.ai.local

import android.content.Context

/**
 * Static device-class compatibility for the local vision models.
 *
 * This intentionally excludes transient free-RAM pressure. A device-compatible model
 * remains selectable; its runtime performs a separate available-memory check before
 * allocating large inference buffers.
 */
object LocalModelDeviceCompatibility {

    fun incompatibilityMessage(context: Context, modelId: String): String? =
        when (modelId.trim().lowercase()) {
            LocalModelRegistry.MINI_CPM_V46_ID ->
                MiniCpmV46RuntimeAdapter.deviceClassRequirementMessage(context)
            LocalModelRegistry.GEMMA_4_E2B_ID ->
                Gemma4RuntimeAdapter.deviceClassRequirementMessage(context)
            LocalModelRegistry.QWEN3_5_ID ->
                Qwen35RuntimeAdapter.deviceClassRequirementMessage(context)
            // LFM2.5-VL-450M has no fixed total-RAM device-class minimum.
            // Its runtime still checks that sufficient free RAM is available before loading.
            LocalModelRegistry.LFM2_5_VL_ID -> null
            else -> null
        }
}
