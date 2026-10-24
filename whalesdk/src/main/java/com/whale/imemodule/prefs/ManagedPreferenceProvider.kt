package com.whale.imemodule.prefs

/**
 * 一组偏好项 + 对应的 UI 元数据。
 *
 * Phase 4 起不再依赖 `androidx.preference.PreferenceScreen`：
 * Compose 侧直接遍历 [managedPreferencesUi] 渲染，取值靠 [managedPreferences]。
 */
abstract class ManagedPreferenceProvider {

    private val _managedPreferences: MutableMap<String, ManagedPreference<*>> = mutableMapOf()

    private val _managedPreferencesUi: MutableList<ManagedPreferenceUi> = mutableListOf()

    val managedPreferences: Map<String, ManagedPreference<*>>
        get() = _managedPreferences

    val managedPreferencesUi: List<ManagedPreferenceUi>
        get() = _managedPreferencesUi

    fun ManagedPreferenceUi.registerUi() {
        _managedPreferencesUi.add(this)
    }

    fun ManagedPreference<*>.register() {
        _managedPreferences[key] = this
    }

}
