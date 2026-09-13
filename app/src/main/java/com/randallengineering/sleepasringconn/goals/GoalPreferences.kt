package com.randallengineering.sleepasringconn.goals

import android.content.Context
import android.content.SharedPreferences

object GoalPreferences {
    private const val PREFS_NAME = "user_goals_preferences"
    private const val KEY_TARGET_PREFIX = "target_"
    private const val KEY_ENABLED_PREFIX = "enabled_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getTarget(context: Context, goalType: GoalType): Float {
        val prefs = getPrefs(context)
        return prefs.getFloat(KEY_TARGET_PREFIX + goalType.id, goalType.defaultTarget)
    }

    fun setTarget(context: Context, goalType: GoalType, value: Float) {
        val prefs = getPrefs(context)
        prefs.edit().putFloat(KEY_TARGET_PREFIX + goalType.id, value).apply()
    }

    fun isGoalEnabled(context: Context, goalType: GoalType): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_ENABLED_PREFIX + goalType.id, true)
    }

    fun setGoalEnabled(context: Context, goalType: GoalType, enabled: Boolean) {
        val prefs = getPrefs(context)
        prefs.edit().putBoolean(KEY_ENABLED_PREFIX + goalType.id, enabled).apply()
    }

    fun resetToDefaults(context: Context) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        GoalType.entries.forEach { goal ->
            editor.putFloat(KEY_TARGET_PREFIX + goal.id, goal.defaultTarget)
            editor.putBoolean(KEY_ENABLED_PREFIX + goal.id, true)
        }
        editor.apply()
    }
}
