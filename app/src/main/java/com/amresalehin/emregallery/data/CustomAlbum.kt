package com.amresalehin.emregallery.data

data class CustomAlbum(
    val id: String,
    val name: String,
    val parentPath: String,
    val mediaUris: Set<String>
) {
    val fullPath: String
        get() = if (parentPath.isBlank()) name else "$parentPath / $name"
}
