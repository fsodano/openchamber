package com.fsodano.openchamber.webstorm

import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.net.URI

data class LaunchSpec(
    val command: List<String>,
    val workdir: String,
    val displayCommand: String,
)

object ServerCommand {
    const val HOST = "127.0.0.1"

    fun baseUrl(port: Int): String = "http://$HOST:$port"

    fun isValidPort(port: Int): Boolean = port in 1..65535

    fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    fun isLoopbackUrl(raw: String): Boolean {
        val host = try {
            URI.create(normalizeUrl(raw)).host
        } catch (_: Exception) {
            return false
        }
        return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
    }

    /**
     * The project CLI is executable code from the repository. It is used only
     * when the project is trusted and its web package identifies itself as
     * OpenChamber; otherwise a project that happens to contain
     * `packages/web/bin/cli.js` would run on tool window open.
     */
    fun isOpenChamberProject(projectPath: String): Boolean {
        val packageJson = File(projectPath, "packages/web/package.json")
        if (!packageJson.isFile) {
            return false
        }
        return try {
            packageJson.readText().contains("\"@openchamber/web\"")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds the command that starts the OpenChamber server.
     *
     * A configured command is run through the user's login shell, so PATH
     * entries from nvm, Homebrew and bun work without the IDE knowing about
     * them. The `serve` flags are appended, so the setting holds only the
     * executable or launcher.
     */
    fun resolve(
        configured: String,
        projectPath: String,
        port: Int,
        allowProjectCli: Boolean,
    ): LaunchSpec {
        require(isValidPort(port)) { "Invalid port: $port" }
        val serveArgs = "serve --foreground --host $HOST --port $port"

        if (configured.isNotBlank()) {
            val fullCommand = "${resolveFirstExecutable(configured.trim())} $serveArgs"
            return LaunchSpec(shellCommand(fullCommand), projectPath, fullCommand)
        }

        if (allowProjectCli && isOpenChamberProject(projectPath)) {
            val bun = findExecutable("bun")
            val localCli = File(projectPath, "packages/web/bin/cli.js")
            if (bun != null && localCli.isFile) {
                val command = listOf(bun, localCli.absolutePath, "serve", "--foreground", "--host", HOST, "--port", port.toString())
                return LaunchSpec(command, projectPath, command.joinToString(" "))
            }
        }

        val installed = findExecutable("openchamber")
        if (installed != null) {
            val command = listOf(installed, "serve", "--foreground", "--host", HOST, "--port", port.toString())
            return LaunchSpec(command, projectPath, command.joinToString(" "))
        }

        val bunx = findExecutable("bunx")
        if (bunx != null) {
            val command = listOf(bunx, "@openchamber/web", "serve", "--foreground", "--host", HOST, "--port", port.toString())
            return LaunchSpec(command, projectPath, command.joinToString(" "))
        }

        throw IllegalStateException(
            "No OpenChamber launcher found. Install @openchamber/web, trust the OpenChamber repository, " +
                "or set a server command in Settings | Tools | OpenChamber.",
        )
    }

    /**
     * The IDE may be launched from the OpenChamber desktop app, which injects
     * its own server URL, runtime, dist directory and OpenCode config. A managed
     * server must not inherit those: it would attach the tool window to the
     * desktop instance instead of the project's own server.
     */
    fun sanitizeEnvironment(environment: MutableMap<String, String>) {
        val keep = setOf(
            "OPENCHAMBER_UI_PASSWORD",
            "OPENCHAMBER_OPENCODE_PATH",
            "OPENCHAMBER_OPENCODE_BIN",
        )
        environment.keys.removeIf { name ->
            (name.startsWith("OPENCHAMBER_") && name !in keep) ||
                name == "OPENCODE_CONFIG" ||
                name == "OPENCODE_CONFIG_CONTENT" ||
                name == "OPENCODE_PASSWORD" ||
                name == "OPENCODE_SERVER_PASSWORD"
        }
        environment["OPENCHAMBER_RUNTIME"] = "web"
    }

    private fun resolveFirstExecutable(command: String): String {
        val first = command.substringBefore(' ')
        if (first.isEmpty() || first.contains('/') || first.contains('\\')) {
            return command
        }
        val executable = findExecutable(first) ?: return command
        return executable + command.removePrefix(first)
    }

    fun applyOpenCodeBinary(environment: MutableMap<String, String>) {
        if (!environment["OPENCODE_BINARY"].isNullOrBlank()) {
            return
        }
        findExecutable("opencode")?.let { environment["OPENCODE_BINARY"] = it }
    }

    fun findExecutable(name: String): String? {
        val names = if (SystemInfo.isWindows) {
            listOf("$name.exe", "$name.cmd", "$name.bat", name)
        } else {
            listOf(name)
        }
        val home = System.getProperty("user.home")
        val directories = listOf(
            File(home, ".bun/bin"),
            File(home, ".opencode/bin"),
            File(home, ".local/bin"),
            File("/opt/homebrew/bin"),
            File("/usr/local/bin"),
            File("/Applications/OpenChamber.app/Contents/Resources/opencode-cli"),
        )
        for (directory in directories) {
            for (candidate in names) {
                val file = File(directory, candidate)
                if (isExecutable(file)) {
                    return file.absolutePath
                }
            }
        }

        val path = System.getenv("PATH").orEmpty()
        for (directory in path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue
            }
            for (candidate in names) {
                val file = File(directory, candidate)
                if (isExecutable(file)) {
                    return file.absolutePath
                }
            }
        }
        return null
    }

    private fun isExecutable(file: File): Boolean =
        if (SystemInfo.isWindows) file.isFile else file.canExecute()

    private fun shellCommand(command: String): List<String> =
        if (SystemInfo.isWindows) {
            listOf("cmd.exe", "/d", "/s", "/c", command)
        } else {
            val shell = if (SystemInfo.isMac) {
                "/bin/zsh"
            } else {
                System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: "/bin/sh"
            }
            listOf(shell, "-lc", command)
        }
}
