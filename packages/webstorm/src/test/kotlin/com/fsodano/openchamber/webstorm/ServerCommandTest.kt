package com.fsodano.openchamber.webstorm

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerCommandTest {

    @Test
    fun `base url uses loopback`() {
        assertEquals("http://127.0.0.1:3001", ServerCommand.baseUrl(3001))
    }

    @Test
    fun `normalizes external urls`() {
        assertEquals("http://localhost:3000", ServerCommand.normalizeUrl("localhost:3000/"))
        assertEquals("https://chamber.example.com", ServerCommand.normalizeUrl(" https://chamber.example.com/ "))
    }

    @Test
    fun `rejects invalid ports`() {
        assertFalse(ServerCommand.isValidPort(0))
        assertFalse(ServerCommand.isValidPort(70000))
        assertTrue(ServerCommand.isValidPort(3001))
    }

    @Test
    fun `configured command appends serve flags through a shell`() {
        val launch = ServerCommand.resolve("openchamber", "/tmp/project", 3011, allowProjectCli = false)
        val shell = launch.command
        assertTrue(shell.first().endsWith("zsh") || shell.first().endsWith("sh") || shell.first().endsWith("cmd.exe"))
        assertTrue(shell.last().contains("openchamber serve --foreground --host 127.0.0.1 --port 3011"))
        assertEquals("/tmp/project", launch.workdir)
    }

    @Test
    fun `configured bare executable resolves to an absolute path`() {
        val bun = ServerCommand.findExecutable("bun") ?: return
        val launch = ServerCommand.resolve("bun /tmp/cli.js", "/tmp/project", 3011, allowProjectCli = false)
        assertTrue(launch.command.last().startsWith(bun), launch.command.last())
    }

    @Test
    fun `refuses an invalid port`() {
        assertFailsWith<IllegalArgumentException> {
            ServerCommand.resolve("openchamber", "/tmp/project", 0, allowProjectCli = false)
        }
    }

    @Test
    fun `recognizes loopback server urls`() {
        assertTrue(ServerCommand.isLoopbackUrl("http://127.0.0.1:3001"))
        assertTrue(ServerCommand.isLoopbackUrl("localhost:3001"))
        assertTrue(ServerCommand.isLoopbackUrl("http://[::1]:3001"))
        assertFalse(ServerCommand.isLoopbackUrl("https://chamber.example.com"))
        assertFalse(ServerCommand.isLoopbackUrl("not a url"))
    }

    @Test
    fun `identifies the openchamber web package`() {
        val project = Files.createTempDirectory("openchamber-project").toFile()
        try {
            val web = project.resolve("packages/web").apply { mkdirs() }
            web.resolve("package.json").writeText("""{"name":"@openchamber/web"}""")
            assertTrue(ServerCommand.isOpenChamberProject(project.absolutePath))
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `does not treat another project as openchamber`() {
        val project = Files.createTempDirectory("other-project").toFile()
        try {
            val web = project.resolve("packages/web").apply { mkdirs() }
            web.resolve("package.json").writeText("""{"name":"some-other-app"}""")
            assertFalse(ServerCommand.isOpenChamberProject(project.absolutePath))
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `finds the installed opencode binary`() {
        val opencode = ServerCommand.findExecutable("opencode") ?: return
        assertTrue(java.io.File(opencode).isFile, opencode)
    }

    @Test
    fun `fills OPENCODE_BINARY without overriding an explicit path`() {
        val environment = mutableMapOf("PATH" to "/usr/bin")
        ServerCommand.applyOpenCodeBinary(environment)
        val found = ServerCommand.findExecutable("opencode")
        if (found != null) {
            assertEquals(found, environment["OPENCODE_BINARY"])
        }

        val explicit = mutableMapOf("OPENCODE_BINARY" to "/custom/opencode")
        ServerCommand.applyOpenCodeBinary(explicit)
        assertEquals("/custom/opencode", explicit["OPENCODE_BINARY"])
    }

    @Test
    fun `removes inherited desktop server environment`() {
        val environment = mutableMapOf(
            "PATH" to "/bin",
            "OPENCHAMBER_DIST_DIR" to "/Applications/OpenChamber.app/web-dist",
            "OPENCHAMBER_RUNTIME" to "desktop",
            "OPENCHAMBER_UI_PASSWORD" to "secret",
            "OPENCODE_CONFIG" to "/tmp/desktop.json",
            "OPENCODE_PASSWORD" to "desktop",
        )
        ServerCommand.sanitizeEnvironment(environment)
        assertEquals("/bin", environment["PATH"])
        assertEquals("web", environment["OPENCHAMBER_RUNTIME"])
        assertEquals("secret", environment["OPENCHAMBER_UI_PASSWORD"])
        assertFalse("OPENCHAMBER_DIST_DIR" in environment)
        assertFalse("OPENCODE_CONFIG" in environment)
        assertFalse("OPENCODE_PASSWORD" in environment)
    }

    @Test
    fun `mcp endpoint maps transports`() {
        assertEquals("http://127.0.0.1:64342/sse", McpConnector.endpoint(64342, "sse"))
        assertEquals("http://127.0.0.1:64342/stream", McpConnector.endpoint(64342, "stream"))
    }

    @Test
    fun `mcp payload pins the protocol for both transports`() {
        val sse = McpConnector.payload("http://127.0.0.1:64342/sse", true, "sse", null)
        assertTrue(sse.contains("\"protocol\":\"legacy\""))
        assertFalse(sse.contains("headers"))

        val stream = McpConnector.payload("http://127.0.0.1:64342/stream", true, "stream", null)
        assertTrue(stream.contains("\"protocol\":\"auto\""))
    }

    @Test
    fun `mcp payload forwards the IDE auth token`() {
        val payload = McpConnector.payload("http://127.0.0.1:64342/sse", true, "sse", "secret")
        assertTrue(payload.contains("\"headers\":{\"Authorization\":\"Bearer secret\"}"))
    }
}
