package org.jellyfin.mobile.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

private const val PREFERENCES_DATASTORE_NAME = "settings"

/**
 * The app-wide [DataStore] holding all user-facing preferences.
 *
 * It transparently migrates the legacy default [android.content.SharedPreferences]
 * (`<packageName>_preferences`) on first access, preserving all existing values.
 * The legacy file is intentionally kept on disk after migration.
 */
internal val Context.appPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = PREFERENCES_DATASTORE_NAME,
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(context, "${context.packageName}_preferences"),
        )
    },
)
