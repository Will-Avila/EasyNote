package com.will.noteharbor.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPermissionPolicyTest {
    @Test
    fun requestsForActiveReminderOnAndroid13WhenActivityIsResumed() {
        assertTrue(
            ReminderPermissionPolicy.shouldRequest(
                apiLevel = 35,
                hasActiveReminder = true,
                permissionGranted = false,
                requestInFlight = false,
                alreadyPromptedInActivity = false,
                activityResumed = true,
                activityFinishing = false,
            ),
        )
    }

    @Test
    fun doesNotRequestWhenPermissionAlreadyGrantedOrNoReminderExists() {
        assertFalse(
            ReminderPermissionPolicy.shouldRequest(
                apiLevel = 35,
                hasActiveReminder = false,
                permissionGranted = false,
                requestInFlight = false,
                alreadyPromptedInActivity = false,
                activityResumed = true,
                activityFinishing = false,
            ),
        )
        assertFalse(
            ReminderPermissionPolicy.shouldRequest(
                apiLevel = 35,
                hasActiveReminder = true,
                permissionGranted = true,
                requestInFlight = false,
                alreadyPromptedInActivity = false,
                activityResumed = true,
                activityFinishing = false,
            ),
        )
    }

    @Test
    fun doesNotRequestBelowAndroid13OrOutsideResumedActivity() {
        assertFalse(
            ReminderPermissionPolicy.shouldRequest(
                apiLevel = 32,
                hasActiveReminder = true,
                permissionGranted = false,
                requestInFlight = false,
                alreadyPromptedInActivity = false,
                activityResumed = true,
                activityFinishing = false,
            ),
        )
        assertFalse(
            ReminderPermissionPolicy.shouldRequest(
                apiLevel = 35,
                hasActiveReminder = true,
                permissionGranted = false,
                requestInFlight = false,
                alreadyPromptedInActivity = false,
                activityResumed = false,
                activityFinishing = false,
            ),
        )
    }

    @Test
    fun doesNotDuplicateARequestDuringOneActivitySession() {
        assertFalse(
            ReminderPermissionPolicy.shouldRequest(
                apiLevel = 35,
                hasActiveReminder = true,
                permissionGranted = false,
                requestInFlight = true,
                alreadyPromptedInActivity = false,
                activityResumed = true,
                activityFinishing = false,
            ),
        )
        assertFalse(
            ReminderPermissionPolicy.shouldRequest(
                apiLevel = 35,
                hasActiveReminder = true,
                permissionGranted = false,
                requestInFlight = false,
                alreadyPromptedInActivity = true,
                activityResumed = true,
                activityFinishing = false,
            ),
        )
    }
}