package dev.wilhelms.gradle.insight

import com.google.gson.JsonSyntaxException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServiceParseFailureTest {
    @Test
    fun `maven parser does not swallow invalid json`() {
        val service = MavenCentralService(ServiceContext())
        assertFailsWith<JsonSyntaxException> {
            service.parse("""{"response":{"docs":[{"v":"1.0.0","timestamp":123}]}""", "1.0.0")
        }
    }

    @Test
    fun `deps dev parser does not swallow invalid json`() {
        val service = DepsDevService(ServiceContext())
        assertFailsWith<JsonSyntaxException> {
            service.parse(DepsDevRaw("""{"dependentCount": 1""", """{"advisoryKeys": []}""", null))
        }
    }
}
