package com.strategy.booster.comps

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

class PlayUpdater(
    private val activity: ComponentActivity,
    private val appUpdateType: Int = AppUpdateType.FLEXIBLE
) {
    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    private val updateLauncher =
        activity.registerForActivityResult(StartIntentSenderForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK && appUpdateType == AppUpdateType.IMMEDIATE) {
                // user canceled/failed
            }
        }

    fun checkAndPrompt() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            val isUpdateAvailable =
                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(appUpdateType)

            if (isUpdateAvailable) {
                val params = AppUpdateOptions.newBuilder(appUpdateType).build()
                appUpdateManager.startUpdateFlowForResult(
                    info,
                    updateLauncher,
                    params
                )
            } else {
                maybeCompleteFlexibleUpdate()
            }
        }
    }

    fun onResume() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (appUpdateType == AppUpdateType.IMMEDIATE &&
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
            ) {
                val params = AppUpdateOptions.newBuilder(appUpdateType).build()
                appUpdateManager.startUpdateFlowForResult(info, updateLauncher, params)
            }
            maybeCompleteFlexibleUpdate()
        }
    }

    private fun maybeCompleteFlexibleUpdate() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED &&
                appUpdateType == AppUpdateType.FLEXIBLE
            ) {
                appUpdateManager.completeUpdate()
            }
        }
    }
}
