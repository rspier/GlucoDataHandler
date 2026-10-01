package de.michelinside.glucodatahandler

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import de.michelinside.glucodatahandler.common.utils.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.notifier.*
import de.michelinside.glucodatahandler.common.receiver.ScreenEventReceiver
import de.michelinside.glucodatahandler.common.utils.WakeLockHelper


object ActiveComplicationHandler: NotifierInterface {
    private const val LOG_ID = "GDH.ActiveComplicationHandler"
    private var packageInfo: PackageInfo? = null
    private var complicationClasses = mutableMapOf<Int, ComponentName>()
    private var noComplication = false   // check complications at least one time
    private var alwaysUpdateComplications = true
    private var forceUpdataAll = false

    init {
        Log.d(LOG_ID, "init called")
    }

    fun addComplication(id: Int, component: ComponentName) {
        if(!complicationClasses.containsKey(id)) {
            Log.i(LOG_ID, "Add complication for id $id: ${component.shortClassName}")
            complicationClasses[id] = component
        }
    }

    fun remComplication(id: Int) {
        if(complicationClasses.containsKey(id)) {
            Log.i(LOG_ID, "Remove complication for id $id")
            complicationClasses.remove(id)
            noComplication = complicationClasses.isEmpty()
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun getPackages(context: Context): PackageInfo {
        if (packageInfo == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SERVICES.toLong())
                )
            } else {
                packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SERVICES
                )
            }
        }
        return packageInfo!!
    }

    fun checkAlwaysUpdateComplications(context: Context) {
        val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
        alwaysUpdateComplications = sharedPref.getBoolean(Constants.SHARED_PREF_PHONE_WEAR_SCREEN_OFF_UPDATE, true)
        Log.d(LOG_ID, "Settings changed - always update complications: $alwaysUpdateComplications - display off: ${ScreenEventReceiver.isDisplayOff()}")
    }

    fun canUpdateComplications(dataSource: NotifySource): Boolean {
        Log.d(LOG_ID, "Check update complications called for $dataSource - always update: $alwaysUpdateComplications - display off: ${ScreenEventReceiver.isDisplayOff()}")
        if(alwaysUpdateComplications)
            return dataSource != NotifySource.DISPLAY_STATE_CHANGED
        // else only update if screen is on
        if(ScreenEventReceiver.isDisplayOff()) {
            return false
        }
        if(dataSource == NotifySource.DISPLAY_STATE_CHANGED) {
            // display switched on
            forceUpdataAll = true   // force update of all complications after display is switched on
            return true
        }
        // else update complications
        return true
    }

    override fun OnNotifyData(context: Context, dataSource: NotifySource, extras: Bundle?) {
        Log.d(LOG_ID, "OnNotifyData called for $dataSource")
        if(!canUpdateComplications(dataSource))
            return  // do not update, if display is off
        Thread {
            try {
                WakeLockHelper(context).use {
                    if(ScreenEventReceiver.isDisplayOff()) {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    } else {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    }
                    if (complicationClasses.isNotEmpty()) {
                        Log.i(LOG_ID, "Update " + complicationClasses.size + " complication(s) ${complicationClasses.values.map { it.shortClassName }}.")
                        // upgrade all at once can cause a disappear of icon and images in ambient mode,
                        // so use some delay!
                        complicationClasses.forEach {
                            if (dataSource != NotifySource.TIME_VALUE && !forceUpdataAll)
                                Thread.sleep(50)  // add delay to prevent disappearing complication icons in ambient mode
                            ComplicationDataSourceUpdateRequester
                                .create(
                                    context = context,
                                    complicationDataSourceComponent = it.value
                                )
                                .requestUpdate(it.key)
                        }
                    } else if (!noComplication) {
                        noComplication = true  // disable to prevent re-updating complications, if there is none...
                        val packageInfo = getPackages(context)
                        if(packageInfo.services != null && packageInfo.services!!.isNotEmpty()) {
                            Log.d(LOG_ID, "Got " + packageInfo.services!!.size + " services.")
                            packageInfo.services!!.forEach {
                                val isComplication =
                                    if (dataSource == NotifySource.TIME_VALUE && !forceUpdataAll) {
                                        // only update time complications
                                        TimeComplicationBase::class.java.isAssignableFrom(
                                            Class.forName(
                                                it.name
                                            )
                                        )
                                    } else {
                                        BgValueComplicationService::class.java.isAssignableFrom(
                                            Class.forName(
                                                it.name
                                            )
                                        )
                                    }

                                if (isComplication) {
                                    if (dataSource != NotifySource.TIME_VALUE && !forceUpdataAll)
                                        Thread.sleep(10)
                                    ComplicationDataSourceUpdateRequester
                                        .create(
                                            context = context,
                                            complicationDataSourceComponent = ComponentName(context, it.name)
                                        )
                                        .requestUpdateAll()
                                }
                            }
                        }
                    }
                    forceUpdataAll = false
                }
            } catch (exc: Exception) {
                Log.e(LOG_ID, "Update complication exception: " + exc.toString())
            }
        }.start()
    }
}