package dev.phonecode.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPolicyTest {
    @Test
    fun automaticChangesCoverOnlyWorkspaceFileEdits() {
        assertTrue(permissionCanAutoApprove(tool = "edit_file", automaticChanges = true))
        assertTrue(permissionCanAutoApprove(tool = "write_file", automaticChanges = true))
        assertTrue(permissionCanAutoApprove(tool = "apply_patch", automaticChanges = true))
        listOf("bash", "process", "git_commit", "git_push", "git_branch", "extension_write", "shared_files_write", "external_directory")
            .forEach { assertFalse(it, permissionCanAutoApprove(tool = it, automaticChanges = true)) }
        assertFalse(permissionCanAutoApprove(tool = "edit_file", automaticChanges = false))
    }

    @Test
    fun nativeGateAlwaysAsksForMutatingToolsOutsideFileEdits() {
        val mutating = listOf("apply_patch", "bash", "git_push", "extension_write", "github__create_issue")
        assertEquals(
            setOf("read_file", "bash", "git_push", "extension_write", "github__create_issue"),
            alwaysAskTools(userAlwaysAsk = setOf("read_file"), mutatingTools = mutating),
        )
    }
}
