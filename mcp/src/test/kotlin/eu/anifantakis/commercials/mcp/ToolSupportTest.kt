package eu.anifantakis.commercials.mcp

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Argument parsing + the tool-runner's success/error mapping. */
class ToolSupportTest {

    @Test
    fun `Args parses scalar types including numeric-as-string`() {
        val a = Args(buildJsonObject {
            put("s", "hello")
            put("n", 42)
            put("b", true)
            put("ns", "7")
        })
        assertEquals("hello", a.string("s"))
        assertEquals(42, a.int("n"))
        assertEquals(42L, a.long("n"))
        assertTrue(a.bool("b", false))
        assertEquals(7, a.int("ns"))
        assertNull(a.stringOrNull("missing"))
        assertFalse(a.bool("missing", false))
    }

    @Test
    fun `Args throws a clear error for a missing required param`() {
        val a = Args(buildJsonObject { })
        assertFailsWith<McpToolException> { a.string("station") }
        assertFailsWith<McpToolException> { a.long("id") }
    }

    @Test
    fun `runTool serializes the payload as JSON text on success`() = runTest {
        val res = runTool("t") { buildJsonObject { put("ok", true) } }
        assertNull(res.isError)
        assertEquals("""{"ok":true}""", (res.content.single() as TextContent).text)
    }

    @Test
    fun `runTool maps a tool exception to a clean error result`() = runTest {
        val res = runTool("t") { throw McpToolException("bad input") }
        assertEquals(true, res.isError)
        assertEquals("bad input", (res.content.single() as TextContent).text)
    }
}
