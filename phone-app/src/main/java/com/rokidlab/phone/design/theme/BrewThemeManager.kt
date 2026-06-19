package com.rokidlab.phone.design.theme

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.rokidlab.phone.R

/**
 * 主题枚举（名称和描述通过 string 资源支持多语言）
 */
enum class BrewTheme(val displayNameResId: Int, val descriptionResId: Int) {
    VELVET_DARK(R.string.theme_velvet_dark, R.string.theme_velvet_dark_desc),
    COOL_BLUE(R.string.theme_cool_blue, R.string.theme_cool_blue_desc),
}

/**
 * 主题管理器
 *
 * 颜色通过 mutableStateOf 暴露，在 @Composable 中读取时会自动触发重组，
 * 因此全局变量如 BrewBg、BrewPanel 等能随主题切换正确更新。
 */
object BrewThemeManager {
    private const val TAG = "BrewThemeManager"
    private const val PREFS_NAME = "brew_theme_prefs"
    private const val KEY_CURRENT_THEME = "current_theme"

    private lateinit var prefs: SharedPreferences

    // 使用 mutableStateOf — Compose 原生状态，读取时自动触发重组
    private var _currentTheme by mutableStateOf(BrewTheme.VELVET_DARK)

    /** 当前主题 (Compose-aware) */
    val currentTheme: BrewTheme get() = _currentTheme

    /**
     * 当前配色 (Compose-aware)
     */
    val currentColors: BrewColors
        get() = when (_currentTheme) {
            BrewTheme.VELVET_DARK -> VelvetDark
            BrewTheme.COOL_BLUE -> CoolBlue
        }

    /**
     * 初始化（需在 Application 或 MainActivity.onCreate 调用一次）
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_CURRENT_THEME, BrewTheme.VELVET_DARK.name)
        _currentTheme = try {
            BrewTheme.valueOf(saved ?: BrewTheme.VELVET_DARK.name)
        } catch (e: Exception) {
            BrewTheme.VELVET_DARK
        }
        Log.d(TAG, "init: currentTheme=${_currentTheme.name}")
    }

    /**
     * 切换主题
     */
    fun switchTheme(theme: BrewTheme) {
        Log.d(TAG, "switchTheme: from=${_currentTheme.name} to=${theme.name}")
        _currentTheme = theme
        prefs.edit().putString(KEY_CURRENT_THEME, theme.name).apply()
        Log.d(TAG, "switchTheme: after switch currentTheme=${_currentTheme.name}")
    }

    /**
     * 获取所有可用主题
     */
    fun getAllThemes(): List<BrewTheme> = BrewTheme.entries
}
