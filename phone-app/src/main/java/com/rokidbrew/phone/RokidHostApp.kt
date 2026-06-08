package com.rokidbrew.phone

enum class RokidHostApp(
    val id: String,
    val label: String,
    val displayName: String,
    val shortLabel: String,
    val packageName: String,
) {
    GLOBAL(
        id = "global",
        label = "Rokid AI",
        displayName = "Rokid AI Global",
        shortLabel = "Global",
        packageName = "com.rokid.sprite.global.aiapp",
    ),
    CHINA(
        id = "china",
        label = "Rokid AI CN",
        displayName = "Rokid AI CN",
        shortLabel = "CN",
        packageName = "com.rokid.sprite.aiapp",
    );

    companion object {
        val DEFAULT = GLOBAL

        fun fromId(id: String?): RokidHostApp {
            return values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}
