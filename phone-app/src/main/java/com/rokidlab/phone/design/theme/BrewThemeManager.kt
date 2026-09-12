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
 */
object BrewThemeManager {
    private const val TAG = "BrewThemeManager"
    private const val PREFS_NAME = "brew_theme_prefs"
    private const val KEY_CURRENT_THEME = "current_theme"

    private lateinit var prefs: SharedPreferences

    private var _currentTheme by mutableStateOf(BrewTheme.COOL_BLUE)

    val currentTheme: BrewTheme get() = _currentTheme

    /**
     * 当前配色
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
        // 默认主题应为冰蓝（COOL_BLUE）。字符串资源 theme_cool_blue_desc 明确标注为"浅蓝冷调默认主题"，
        // 此前默认值误写成 VELVET_DARK，导致首次启动（无保存记录）时启动后不是冰蓝。
        val saved = prefs.getString(KEY_CURRENT_THEME, BrewTheme.COOL_BLUE.name)
        _currentTheme = try {
            BrewTheme.valueOf(saved ?: BrewTheme.COOL_BLUE.name)
        } catch (e: Exception) {
            BrewTheme.COOL_BLUE
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
