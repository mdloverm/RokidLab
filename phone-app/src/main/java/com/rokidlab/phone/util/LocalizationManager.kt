package com.rokidlab.phone.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 应用内语言切换管理器。
 *
 * 基于 AppCompatDelegate.setApplicationLocales() 实现运行时语言切换，
 * 兼容 Android 5.0+。
 *
 * 默认语言：简体中文 (zh-CN)
 * 支持语言：en (英语)、zh (简体中文)
 */
object LocalizationManager {

    private const val PREFS_NAME = "rokidbrew_locale"
    private const val KEY_LOCALE = "app_locale"

    /** 支持的语言列表 */
    enum class AppLocale(
        val code: String,
        val displayName: String,
        val displayNameEnglish: String,
    ) {
        ZH("zh", "简体中文", "Chinese (Simplified)"),
        EN("en", "English", "English"),
    }

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 获取当前保存的语言代码，默认为 "zh" */
    fun getCurrentLocaleCode(): String = prefs.getString(KEY_LOCALE, "zh") ?: "zh"

    /** 获取当前 AppLocale */
    fun getCurrentLocale(): AppLocale {
        val code = getCurrentLocaleCode()
        return AppLocale.entries.find { it.code == code } ?: AppLocale.ZH
    }

    /** 切换语言并应用（持久化保存） */
    fun setLocale(context: Context, localeCode: String) {
        prefs.edit().putString(KEY_LOCALE, localeCode).apply()
        applyLocale(localeCode)
    }

    /** 应用语言设置（仅运行时，不持久化），用于启动时恢复 */
    fun applyLocale(localeCode: String) {
        val localeList = when (localeCode) {
            "en" -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.forLanguageTags("zh-CN")
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    /** 获取当前运行时的 Locale（用于手动覆写 Context） */
    fun getRuntimeLocale(): Locale {
        return when (getCurrentLocaleCode()) {
            "en" -> Locale.ENGLISH
            else -> Locale.SIMPLIFIED_CHINESE
        }
    }

    /** 覆写指定 Context 的语言（如果需要更精细的控制） */
    fun wrapContext(context: Context): Context {
        val locale = getRuntimeLocale()
        val config = context.resources.configuration
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(config)
    }
}
