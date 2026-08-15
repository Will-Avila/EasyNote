package com.will.noteharbor.data

/** Pure decision rule for requesting Android 13+ notification permission. */
object ReminderPermissionPolicy {
    private const val ANDROID_13_API = 33

    fun shouldRequest(
        apiLevel: Int,
        hasActiveReminder: Boolean,
        permissionGranted: Boolean,
        requestInFlight: Boolean,
        alreadyPromptedInActivity: Boolean,
        activityResumed: Boolean,
        activityFinishing: Boolean,
    ): Boolean =
        apiLevel >= ANDROID_13_API &&
            hasActiveReminder &&
            !permissionGranted &&
            !requestInFlight &&
            !alreadyPromptedInActivity &&
            activityResumed &&
            !activityFinishing
}
