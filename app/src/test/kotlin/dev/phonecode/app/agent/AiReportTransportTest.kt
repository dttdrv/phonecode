package dev.phonecode.app.agent

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Test

class AiReportTransportTest {
    @Test
    fun reportClientNeverFollowsRedirects() {
        val client = reportHttpClient(OkHttpClient())

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }
}
