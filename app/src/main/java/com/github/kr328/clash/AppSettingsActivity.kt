package com.github.kr328.clash

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.design.AppSettingsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.ApplicationObserver
import com.github.kr328.clash.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class AppSettingsActivity : BaseActivity<AppSettingsDesign>(), Behavior {
    override suspend fun main() {
        val design = AppSettingsDesign(
            this,
            uiStore,
            ServiceStore(this),
            this,
            clashRunning,
            ::onHideIconChange,
            ::onCheckUpdate,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    ApplicationObserver.createdActivities.forEach {
                        it.recreate()
                    }
                }
            }
        }
    }

    override var autoRestart: Boolean
        get() {
            val status = packageManager.getComponentEnabledSetting(
                RestartReceiver::class.componentName
            )

            return status == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        set(value) {
            val status = if (value)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            packageManager.setComponentEnabledSetting(
                RestartReceiver::class.componentName,
                status,
                PackageManager.DONT_KILL_APP,
            )
        }

    private fun onHideIconChange(hide: Boolean) {
        val newState = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        packageManager.setComponentEnabledSetting(
            ComponentName(this, mainActivityAlias),
            newState,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun onCheckUpdate() {
        launch {
            val result = UpdateChecker.checkForUpdate(this@AppSettingsActivity)
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { releaseInfo ->
                        if (releaseInfo != null) {
                            AlertDialog.Builder(this@AppSettingsActivity)
                                .setTitle(getString(R.string.update_available))
                                .setMessage(getString(R.string.update_available_message, releaseInfo.versionName))
                                .setPositiveButton(getString(R.string.download)) { _, _ ->
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseInfo.downloadUrl)))
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        } else {
                            AlertDialog.Builder(this@AppSettingsActivity)
                                .setTitle(getString(R.string.no_update_available))
                                .setMessage(getString(R.string.already_latest_version))
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                    },
                    onFailure = { error ->
                        AlertDialog.Builder(this@AppSettingsActivity)
                            .setTitle(getString(R.string.check_update_failed))
                            .setMessage(error.message ?: getString(R.string.unknown_error))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                )
            }
        }
    }
}