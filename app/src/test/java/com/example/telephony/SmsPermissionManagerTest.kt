package com.example.telephony

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmsPermissionManagerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun requiredPermissions_containsReadAndReceiveSms() {
        val permissions = SmsPermissionManager.getAllRequiredPermissions()
        assertTrue("Must require READ_SMS", permissions.contains(Manifest.permission.READ_SMS))
        assertTrue("Must require RECEIVE_SMS", permissions.contains(Manifest.permission.RECEIVE_SMS))
        assertTrue("Must require SEND_SMS", permissions.contains(Manifest.permission.SEND_SMS))
        assertTrue("Must require READ_CONTACTS", permissions.contains(Manifest.permission.READ_CONTACTS))
    }

    @Test
    fun hasEssentialSmsPermissions_returnsFalse_whenNotGranted() {
        assertFalse(SmsPermissionManager.hasEssentialSmsPermissions(context))
        assertFalse(SmsPermissionManager.canReadOrReceive(context))
    }

    @Test
    fun hasEssentialSmsPermissions_returnsTrue_whenGranted() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)

        assertTrue(SmsPermissionManager.hasPermission(context, Manifest.permission.READ_SMS))
        assertTrue(SmsPermissionManager.hasPermission(context, Manifest.permission.RECEIVE_SMS))
        assertTrue(SmsPermissionManager.hasEssentialSmsPermissions(context))
    }

    @Test
    fun permissionItems_generatesLocalizedRationales() {
        val itemsBn = SmsPermissionManager.getPermissionItems(context, "BN")
        val itemsEn = SmsPermissionManager.getPermissionItems(context, "EN")

        assertEquals(5, itemsBn.size)
        assertEquals(5, itemsEn.size)

        val readItemBn = itemsBn.first { it.permission == Manifest.permission.READ_SMS }
        assertTrue(readItemBn.title.contains("পড়ার অনুমতি"))

        val readItemEn = itemsEn.first { it.permission == Manifest.permission.READ_SMS }
        assertTrue(readItemEn.title.contains("Read SMS"))
    }
}

// Extension helper for convenience
private fun SmsPermissionManager.canReadOrReceive(context: Context): Boolean {
    return hasPermission(context, Manifest.permission.READ_SMS) && hasPermission(context, Manifest.permission.RECEIVE_SMS)
}
