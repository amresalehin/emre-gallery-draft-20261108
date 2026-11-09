package com.amresalehin.emregallery.data

/**
 * Represents a cohesive collection of memories (e.g. "On this day · 2024", "Trip to Paris").
 */
data class StoryGroup(
    val title: String,
    val items: List<MediaItem>
)
