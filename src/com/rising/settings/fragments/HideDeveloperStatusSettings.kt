/*
 * Copyright (C) 2023-2026 the risingOS Android Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.rising.settings.fragments

import android.app.ActivityManager
import android.app.AxSandboxManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.android.internal.logging.nano.MetricsProto
import com.android.internal.util.android.HideDeveloperStatusUtils
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import com.android.settings.widget.scrollbar
import com.android.settingslib.spa.framework.theme.SettingsSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HIDE_DEVELOPER_STATUS_KEY = Settings.Secure.HIDE_DEVELOPER_STATUS
private const val HIDE_DEVELOPER_STATUS_ENABLED_KEY = Settings.Secure.HIDE_DEVELOPER_STATUS_ENABLED

class HideDeveloperStatusSettings : SettingsPreferenceFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.hide_developer_status_title)
    }

    override fun getMetricsCategory() = MetricsProto.MetricsEvent.VIEW_UNKNOWN

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            ExpressiveTheme {
                HideDeveloperStatusContent(context = requireContext())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HideDeveloperStatusContent(context: Context) {
    val pm = context.packageManager
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val sandboxManager = remember { context.getSystemService(AxSandboxManager::class.java) }
    val scope = rememberCoroutineScope()

    fun readTargetsSet(): MutableSet<String> {
        val raw = Settings.Secure.getString(context.contentResolver, HIDE_DEVELOPER_STATUS_KEY)
        if (raw.isNullOrBlank()) return mutableSetOf()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
    }

    var globalEnabled by remember {
        val rawEnabled = Settings.Secure.getInt(
            context.contentResolver, HIDE_DEVELOPER_STATUS_ENABLED_KEY, -1
        )
        val initial = if (rawEnabled == -1) {
            val hasExistingTargets = readTargetsSet().isNotEmpty()
            if (hasExistingTargets) {
                Settings.Secure.putInt(
                    context.contentResolver, HIDE_DEVELOPER_STATUS_ENABLED_KEY, 1
                )
            }
            hasExistingTargets
        } else {
            rawEnabled == 1
        }
        mutableStateOf(initial)
    }

    var searchQuery by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    val allApps = remember { mutableStateListOf<AppListEntry>() }

    val hiddenApps = remember {
        context.resources.getStringArray(R.array.hide_developer_status_hidden_apps).toSet()
    }

    fun syncSandboxSpoof(packageName: String, enabled: Boolean) {
        try {
            val userId = UserHandle.myUserId()
            HideDeveloperStatusUtils.getSettingKeysToHide().forEach { key ->
                sandboxManager?.setSpoofSettingEnabled(packageName, key, enabled, userId)
            }
        } catch (_: Exception) {
        }
    }

    fun writeTargetsSet(set: Set<String>) {
        Settings.Secure.putString(
            context.contentResolver,
            HIDE_DEVELOPER_STATUS_KEY,
            set.joinToString(","),
        )
    }

    fun applyChange(packageName: String, enabled: Boolean) {
        val current = allApps.filter { it.isSelected }.map { it.packageName }.toSet()
        writeTargetsSet(current)
        syncSandboxSpoof(packageName, enabled && globalEnabled)
        stopPackage(activityManager, packageName)
    }

    // Load apps (re-runs when showSystemApps changes)
    LaunchedEffect(showSystemApps) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val targetsSet = readTargetsSet()

            val installed = filterInstalledApps(
                pm = pm,
                showSystem = showSystemApps,
                targeted = targetsSet,
                hidden = hiddenApps,
                extraFilter = { app ->
                    !HideDeveloperStatusUtils.isProtectedPackage(app.packageName) &&
                        !app.packageName.contains("android.settings")
                },
            )
                .sortedWith(targetedFirstComparator(pm, targetsSet))
                .map { app ->
                    AppListEntry(
                        packageName = app.packageName,
                        label = pm.getApplicationLabel(app).toString(),
                        icon = runCatching { pm.getApplicationIcon(app) }.getOrNull(),
                        isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        isSelected = app.packageName in targetsSet,
                    )
                }

            // Prune uninstalled packages
            val allInstalledPkgs = pm.getInstalledApplications(0).map { it.packageName }.toSet()
            val pruned = targetsSet.filter { it in allInstalledPkgs }.toSet()
            if (pruned.size < targetsSet.size) {
                writeTargetsSet(pruned)
            }

            withContext(Dispatchers.Main) {
                allApps.clear()
                allApps.addAll(installed)
                isLoading = false
            }
        }
    }

    val filteredApps = remember(searchQuery, allApps.toList()) {
        val q = searchQuery.lowercase()
        allApps.filter { app ->
            q.isEmpty() ||
                app.label.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q)
        }
    }

    val activeCount = allApps.count { it.isSelected }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.navigationBars,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = SettingsSpace.small1),
        ) {
            Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))

            ExpressiveHeaderCard(
                title = stringResource(R.string.hide_developer_status_title),
                subtitle = when {
                    !globalEnabled -> stringResource(R.string.app_spoofing_disabled)
                    activeCount == 0 -> stringResource(R.string.hide_developer_status_none_configured)
                    else -> stringResource(R.string.hide_developer_status_count, activeCount)
                },
                checked = globalEnabled,
                onCheckedChange = { checked ->
                    globalEnabled = checked
                    Settings.Secure.putInt(
                        context.contentResolver,
                        HIDE_DEVELOPER_STATUS_ENABLED_KEY,
                        if (checked) 1 else 0,
                    )
                    val selectedPkgs = allApps.filter { it.isSelected }
                        .map { it.packageName }.toSet()
                    scope.launch(Dispatchers.IO) {
                        selectedPkgs.forEach { pkg -> syncSandboxSpoof(pkg, checked) }
                        killPackages(activityManager, selectedPkgs)
                    }
                },
            ) {
                Icon(
                    Icons.Default.DeveloperMode,
                    contentDescription = null,
                    tint = if (globalEnabled) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }

            Spacer(modifier = Modifier.height(SettingsSpace.extraSmall6))

            ExpressiveAnimatedVisibility(visible = globalEnabled) {
                Column {
                    AppPickerSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        enabled = globalEnabled,
                    )

                    Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))

                    AppPickerActions(
                        showSystemApps = showSystemApps,
                        onToggleSystemApps = { showSystemApps = !showSystemApps },
                        onSelectAll = {
                            val newlySelected = mutableSetOf<String>()
                            filteredApps.forEach { app ->
                                val i = allApps.indexOfFirst { it.packageName == app.packageName }
                                if (i >= 0 && !allApps[i].isSelected) {
                                    allApps[i] = allApps[i].copy(isSelected = true)
                                    newlySelected.add(app.packageName)
                                }
                            }
                            val current = allApps.filter { it.isSelected }
                                .map { it.packageName }.toSet()
                            scope.launch(Dispatchers.IO) {
                                writeTargetsSet(current)
                                newlySelected.forEach { pkg -> syncSandboxSpoof(pkg, true) }
                                killPackages(activityManager, newlySelected)
                            }
                        },
                        onReset = {
                            val previouslySelected = allApps
                                .filter { it.isSelected }
                                .map { it.packageName }
                                .toSet()
                            allApps.indices.forEach { i ->
                                if (allApps[i].isSelected) {
                                    allApps[i] = allApps[i].copy(isSelected = false)
                                }
                            }
                            scope.launch(Dispatchers.IO) {
                                writeTargetsSet(emptySet())
                                previouslySelected.forEach { pkg -> syncSandboxSpoof(pkg, false) }
                                killPackages(activityManager, previouslySelected)
                            }
                        },
                        enabled = globalEnabled,
                    )

                    Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))

                    ExpressiveActionRow(
                        actions = listOf(
                            ExpressiveAction(
                                text = stringResource(R.string.action_add_installed),
                                icon = Icons.Rounded.Add,
                                enabled = globalEnabled,
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val newlyAdded = mutableSetOf<String>()
                                        withContext(Dispatchers.Main) {
                                            allApps.indices.forEach { i ->
                                                val app = allApps[i]
                                                if (!app.isSelected && !app.isSystem) {
                                                    allApps[i] = app.copy(isSelected = true)
                                                    newlyAdded.add(app.packageName)
                                                }
                                            }
                                        }
                                        val current = allApps.filter { it.isSelected }
                                            .map { it.packageName }.toSet()
                                        writeTargetsSet(current)
                                        newlyAdded.forEach { pkg -> syncSandboxSpoof(pkg, true) }
                                        killPackages(activityManager, newlyAdded)
                                        withContext(Dispatchers.Main) {
                                            val msg = context.resources.getQuantityString(
                                                R.plurals.ts_added_installed_count,
                                                newlyAdded.size,
                                                newlyAdded.size,
                                            )
                                            Toast.makeText(
                                                context, msg, Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                            ),
                        ),
                        inset = false,
                    )

                    Spacer(modifier = Modifier.height(SettingsSpace.extraSmall4))

                    AppListAnimatedContent(
                        isLoading = isLoading,
                        isEmpty = filteredApps.isEmpty(),
                        hasSearchQuery = searchQuery.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        emptyContent = {
                            ExpressiveEmptyState(
                                icon = Icons.Default.DeveloperMode,
                                title = stringResource(R.string.hide_developer_status_none_configured),
                                description = stringResource(
                                    R.string.hide_developer_status_empty_description
                                ),
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                    ) {
                        val listState = rememberLazyListState()
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .scrollbar(listState.scrollIndicatorState, Orientation.Vertical),
                            verticalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall1),
                        ) {
                            itemsIndexed(
                                filteredApps,
                                key = { _, app -> app.packageName },
                            ) { index, app ->
                                AppPickerItem(
                                    packageName = app.packageName,
                                    label = app.label,
                                    icon = app.icon,
                                    isSystem = app.isSystem,
                                    checked = app.isSelected,
                                    shapes = ListItemDefaults.segmentedShapes(
                                        index = index,
                                        count = filteredApps.size,
                                    ),
                                    enabled = globalEnabled,
                                    onToggle = { nowTargeted ->
                                        if (isLoading) return@AppPickerItem
                                        val appIndex = allApps.indexOfFirst {
                                            it.packageName == app.packageName
                                        }
                                        if (appIndex < 0) return@AppPickerItem
                                        allApps[appIndex] = allApps[appIndex].copy(
                                            isSelected = nowTargeted
                                        )
                                        scope.launch(Dispatchers.IO) {
                                            applyChange(app.packageName, nowTargeted)
                                        }
                                    },
                                )
                            }
                            item { Spacer(modifier = Modifier.height(80.dp)) }
                        }
                    }
                }
            }
        }
    }
}
