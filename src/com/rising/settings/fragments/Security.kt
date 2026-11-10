/*
 * Copyright (C) 2023-2024 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rising.settings.fragments

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.preference.Preference
import com.android.internal.logging.nano.MetricsProto
import com.android.internal.util.android.SystemRestartUtils
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settingslib.search.SearchIndexable

@SearchIndexable
class Security : SettingsPreferenceFragment(), Preference.OnPreferenceChangeListener {

    companion object {
        private const val HIDE_DEVELOPER_STATUS_KEY = "hide_developer_status_settings"
        private const val HIDE_SCREEN_CAPTURE_STATUS_KEY = "hide_screen_capture_status"
        private const val NO_STORAGE_RESTRICT_KEY = "no_storage_restrict"
        private const val WINDOW_IGNORE_SECURE_KEY = "window_ignore_secure"

        /**
         * For search
         */
        @JvmField
        val SEARCH_INDEX_DATA_PROVIDER = object : BaseSearchIndexProvider(R.xml.rising_settings_security) {
            override fun getNonIndexableKeys(context: Context): List<String> {
                return super.getNonIndexableKeys(context)
            }
        }
    }

    private var mHideDevStatusPref: Preference? = null
    private lateinit var mHideScreenCapturePref: Preference
    private lateinit var mNoStorageRestrictPref: Preference
    private lateinit var mWindowIgnoreSecurePref: Preference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.rising_settings_security)

        mHideDevStatusPref = findPreference(HIDE_DEVELOPER_STATUS_KEY)
        mHideScreenCapturePref = findPreference(HIDE_SCREEN_CAPTURE_STATUS_KEY)!!
        mNoStorageRestrictPref = findPreference(NO_STORAGE_RESTRICT_KEY)!!
        mWindowIgnoreSecurePref = findPreference(WINDOW_IGNORE_SECURE_KEY)!!

        mHideScreenCapturePref.onPreferenceChangeListener = this
        mNoStorageRestrictPref.onPreferenceChangeListener = this
        mWindowIgnoreSecurePref.onPreferenceChangeListener = this
        updateHideDevSummary()
    }

    override fun onResume() {
        super.onResume()
        updateHideDevSummary()
    }

    private fun updateHideDevSummary() {
        val resolver = context?.contentResolver ?: return
        val enabled = Settings.Secure.getInt(
            resolver, Settings.Secure.HIDE_DEVELOPER_STATUS_ENABLED, 0
        ) == 1
        val raw = Settings.Secure.getString(resolver, Settings.Secure.HIDE_DEVELOPER_STATUS)
        val count = raw?.split(",")?.count { it.isNotBlank() } ?: 0
        mHideDevStatusPref?.summary = if (enabled && count > 0) {
            getString(R.string.hide_developer_status_count, count)
        } else {
            getString(R.string.hide_developer_status_summary)
        }
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        return when (preference) {
            mHideScreenCapturePref, mNoStorageRestrictPref, mWindowIgnoreSecurePref -> {
                SystemRestartUtils.showSystemRestartDialog(context)
                true
            }
            else -> false
        }
    }

    override fun getMetricsCategory(): Int {
        return MetricsProto.MetricsEvent.VIEW_UNKNOWN
    }
}
