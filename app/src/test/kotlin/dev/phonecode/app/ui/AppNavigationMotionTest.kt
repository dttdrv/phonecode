package dev.phonecode.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppNavigationMotionTest {

    @Test
    fun modelSetupIsModalWhileSettingsDestinationsRemainHierarchical() {
        assertEquals("MODAL", routeMotion("model-setup"))
        listOf("settings", "skills", "mcp").forEach { route ->
            assertEquals("$route should remain a horizontal hierarchy destination", "HIERARCHY", routeMotion(route))
        }
    }

    private fun routeMotion(route: String): String {
        val method = Class.forName("dev.phonecode.app.ui.PhoneCodeAppKt")
            .declaredMethods
            .firstOrNull { it.name == "navigationMotionFor" }
        assertNotNull("PhoneCodeApp must expose its route-motion classification for regression coverage", method)
        method!!.isAccessible = true
        return method.invoke(null, route).toString()
    }
}
