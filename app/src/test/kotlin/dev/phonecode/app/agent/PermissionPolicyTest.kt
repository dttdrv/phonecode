package dev.phonecode.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun automaticChangesNeverBypassExternalDirectoryReadApproval() {
        assertTrue(permissionCanAutoApprove(tool = "write", automaticChanges = true))
        assertFalse(permissionCanAutoApprove(tool = "external_directory", automaticChanges = true))
        assertFalse(permissionCanAutoApprove(tool = "write", automaticChanges = false))
    }
}
