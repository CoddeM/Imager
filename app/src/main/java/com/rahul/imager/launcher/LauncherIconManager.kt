package com.rahul.imager.launcher

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.rahul.imager.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Swaps the launcher icon to the one for today's weekday.
 *
 * Android has no API for changing an icon at runtime, so the app instead ships seven
 * `<activity-alias>` entries — one per weekday, each pointing at MainActivity with its own icon —
 * and enables exactly one of them. That is the same trick Duolingo and Reddit use for their
 * seasonal icons; see AndroidManifest.xml for the aliases and mipmap-anydpi-v26/ic_launcher_*.xml
 * for the icons themselves.
 *
 * The swap happens when the app goes to the background, never while it is on screen. Toggling a
 * component the current task was launched from makes Android tear that task down, which on a cold
 * start looks exactly like the app closing itself the moment the user opens it.
 */
@Singleton
class LauncherIconManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val aliases = List(DAY_COUNT) { ComponentName(context, ALIAS_PREFIX + it) }

    /**
     * Starts swapping the icon each time the app is backgrounded.
     *
     * Call once from [Application.onCreate]. Foreground swaps are deliberately not attempted: see
     * the note on this class.
     *
     * DEBUG BUILDS DO NOT SWAP. Android Studio resolves the activity to launch from the merged
     * manifest, which names the Monday alias; once a swap has disabled that alias, every Run ends
     * in "Activity class {…launcher.Day1} does not exist". A daily icon is not worth breaking the
     * Run button, so debug builds instead put the aliases back to their manifest state, which also
     * repairs an install that was already swapped.
     */
    fun startSwappingOnBackground(application: Application) {
        if (BuildConfig.DEBUG) {
            application.registerActivityLifecycleCallbacks(resetOnBackground())
            return
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                // isChangingConfigurations filters out rotations, which also drop the count to 0.
                if (startedActivities == 0 && !activity.isChangingConfigurations) {
                    applyIconForToday()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * Points the launcher at today's icon, doing nothing if it already is.
     *
     * The no-op path matters: every enable/disable is a write to the package manager that makes
     * launchers rebuild their icon, and some of them briefly drop the home screen shortcut while
     * they do. Checking first keeps that to at most once a day.
     */
    private fun applyIconForToday() {
        val packageManager = context.packageManager
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        val current = aliases.indexOfFirst {
            packageManager.getComponentEnabledSetting(it) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        if (current == today) return

        // Enable today's alias BEFORE disabling the others. If the package ever has zero enabled
        // launcher entries, even for an instant, launchers treat the app as uninstalled and the
        // user loses wherever they had placed the icon.
        packageManager.setComponentEnabledSetting(
            aliases[today],
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        aliases.forEachIndexed { index, alias ->
            if (index != today) {
                packageManager.setComponentEnabledSetting(
                    alias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    /**
     * Returns every alias to its manifest default, so the launcher entry Android Studio expects is
     * enabled again.
     *
     * Runs on background for the same reason the swap does: changing the component a foreground
     * task was launched from tears that task down.
     */
    private fun resetOnBackground(): Application.ActivityLifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities == 0 && !activity.isChangingConfigurations) {
                    resetToManifestDefaults()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

    /** Clears every explicit component state, letting the manifest decide again. */
    private fun resetToManifestDefaults() {
        val packageManager = context.packageManager
        val alreadyDefault = aliases.all {
            packageManager.getComponentEnabledSetting(it) ==
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        if (alreadyDefault) return
        aliases.forEach { alias ->
            packageManager.setComponentEnabledSetting(
                alias,
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private companion object {
        /** Indexes match [Calendar.DAY_OF_WEEK] offset from [Calendar.SUNDAY], so 0 is Sunday. */
        const val DAY_COUNT = 7
        const val ALIAS_PREFIX = "com.rahul.imager.launcher.Day"
    }
}
