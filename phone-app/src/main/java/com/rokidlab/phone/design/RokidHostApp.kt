package com.rokidlab.phone.design

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
enum class RokidHostApp(
    val id: String,
    val label: String,
    val displayName: String,
    val packageName: String,
) {
    GLOBAL(
        id = "global",
        label = "Rokid AI",
        displayName = "Rokid AI Global",
        packageName = "com.rokid.sprite.global.aiapp",
    ),
    CHINA(
        id = "china",
        label = "Rokid AI CN",
        displayName = "Rokid AI CN",
        packageName = "com.rokid.sprite.aiapp",
    );

    companion object {
        val DEFAULT = GLOBAL

        fun fromId(id: String?): RokidHostApp {
            return values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}
