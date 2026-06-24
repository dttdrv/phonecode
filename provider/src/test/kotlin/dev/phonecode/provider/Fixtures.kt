package dev.phonecode.provider

/** Loads a recorded SSE fixture from test resources. */
internal object Fixtures {
    fun load(path: String): String =
        Fixtures::class.java.getResourceAsStream("/fixtures/$path")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("fixture not found: /fixtures/$path")
}
