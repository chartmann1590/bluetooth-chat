package com.charles.meshtalk.app.data.feedback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.feedbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "feedback_bug_reports")

class BugReportRepo(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val key = stringPreferencesKey("bug_reports_list")

    val bugReports: Flow<List<BugReport>> = context.feedbackDataStore.data.map { prefs ->
        val raw = prefs[key] ?: "[]"
        try {
            json.decodeFromString<List<BugReport>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveBugReport(report: BugReport) {
        context.feedbackDataStore.edit { prefs ->
            val raw = prefs[key] ?: "[]"
            val list = try {
                json.decodeFromString<MutableList<BugReport>>(raw)
            } catch (_: Exception) {
                mutableListOf()
            }
            val existing = list.indexOfFirst { it.number == report.number }
            if (existing >= 0) {
                list[existing] = report
            } else {
                list.add(0, report)
            }
            prefs[key] = json.encodeToString(list)
        }
    }

    suspend fun updateBugReports(reports: List<BugReport>) {
        val sorted = reports.sortedByDescending { it.number }
        context.feedbackDataStore.edit { prefs ->
            prefs[key] = json.encodeToString(sorted)
        }
    }

    suspend fun getBugReportsList(): List<BugReport> {
        val raw = context.feedbackDataStore.data.map { prefs ->
            prefs[key] ?: "[]"
        }
        var result: List<BugReport> = emptyList()
        raw.collect { value ->
            result = try {
                json.decodeFromString(value)
            } catch (_: Exception) {
                emptyList()
            }
            return@collect
        }
        return result
    }
}
