package com.example.guard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device Administrator receiver that prevents direct uninstallation of Wellbeing Lock
 * from the home screen or Settings while uninstall protection is enrolled.
 */
class StrictDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling Wellbeing Lock Uninstall Shield weakens strict limit enforcement."
    }
}
