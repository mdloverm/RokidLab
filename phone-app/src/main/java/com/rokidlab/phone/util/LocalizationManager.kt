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
 * 启动时自动检测手机系统语言：
 *   - 系统语言为 zh → 使用简体中文
 *   - 系统语言为 en → 使用英语
 *   - 其他语种 → 默认使用英语
 */
object LocalizationManager {

    private const val PREFS_NAME = "rokidbrew_locale"
    private const val KEY_LOCALE = "app_locale"
    private const val KEY_INITIALIZED = "locale_initialized"

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

    /**
     * 获取当前语言代码。
     * 首次启动时根据手机系统语言自动选择：zh→中文，其他→英文。
     */
    fun getCurrentLocaleCode(): String {
        val initialized = prefs.getBoolean(KEY_INITIALIZED, false)
        if (!initialized) {
            // 首次启动：检测手机系统语言
            val detected = detectSystemLocale()
            prefs.edit()
                .putString(KEY_LOCALE, detected)
                .putBoolean(KEY_INITIALIZED, true)
                .apply()
            return detected
        }
        return prefs.getString(KEY_LOCALE, "en") ?: "en"
    }

    /** 根据手机系统语言检测应使用的应用语言 */
    private fun detectSystemLocale(): String {
        val systemLocale = Locale.getDefault()
        val lang = systemLocale.language
        return if (lang == "zh") "zh" else "en"
    }

    /** 获取当前 AppLocale */
    fun getCurrentLocale(): AppLocale {
        val code = getCurrentLocaleCode()
        return AppLocale.entries.find { it.code == code } ?: AppLocale.EN
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

    /** 获取当前运行时的 Locale */
    fun getRuntimeLocale(): Locale {
        return when (getCurrentLocaleCode()) {
            "en" -> Locale.ENGLISH
            else -> Locale.SIMPLIFIED_CHINESE
        }
    }

    /** 覆写指定 Context 的语言 */
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
