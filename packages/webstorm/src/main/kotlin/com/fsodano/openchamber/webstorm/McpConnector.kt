package com.fsodano.openchamber.webstorm

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit

object McpConnector {
    const val SERVER_NAME = "webstorm"

    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        // See OpenChamberServer: h2c upgrade hangs, and loopback must bypass proxies.
        .version(HttpClient.Version.HTTP_1_1)
        .proxy(HttpClients.loopbackAwareProxySelector())
        .cookieHandler(cookies)
        .build()

    data class Result(
        val ok: Boolean,
        val message: String,
        val mcpListening: Boolean,
    )

    fun endpoint(port: Int, transport: String): String {
        val path = if (transport == "stream") "stream" else "sse"
        return "http://${ServerCommand.HOST}:$port/$path"
    }

    fun isListening(port: Int): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ServerCommand.HOST, port), 500)
            }
            true
        } catch (_: Exception) {
            false
        }

    fun connect(baseUrl: String, directory: String?, mcpPort: Int, transport: String): Result {
        if (!ServerCommand.isLoopbackUrl(baseUrl)) {
            return Result(
                ok = false,
                message = "WebStorm's MCP server is local. Use a local OpenChamber server to register it.",
                mcpListening = false,
            )
        }
        if (!isListening(mcpPort)) {
            return Result(
                ok = false,
                message = "WebStorm's MCP Server is not running. Enable it in Settings | Tools | MCP Server, then retry.",
                mcpListening = false,
            )
        }

        val password = System.getenv("OPENCHAMBER_UI_PASSWORD")?.trim().orEmpty()
        val authToken = if (password.isEmpty()) null else login(baseUrl, password)
        val ideToken = System.getenv("IJ_MCP_AUTH_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }

        val endpoint = endpoint(mcpPort, transport)
        val createBody = payload(endpoint, includeScope = true, transport = transport, ideToken = ideToken)
        val create = send(baseUrl, directory, "POST", createBody, authToken)
        if (create.statusCode() in 200..299) {
            return Result(true, "OpenCode now uses WebStorm's MCP tools at $endpoint.", true)
        }

        if (create.body().contains("already exists", ignoreCase = true)) {
            val updateBody = payload(endpoint, includeScope = false, transport = transport, ideToken = ideToken)
            val update = send(baseUrl, directory, "PATCH", updateBody, authToken)
            if (update.statusCode() in 200..299) {
                return Result(true, "OpenCode now uses WebStorm's MCP tools at $endpoint.", true)
            }
            return Result(false, failureMessage("update", update), true)
        }

        return Result(false, failureMessage("create", create), true)
    }

    fun connectAsync(
        project: Project,
        baseUrl: String,
        directory: String?,
        quietSuccess: Boolean,
        attempt: Int = 0,
    ) {
        AppExecutorUtil.getAppExecutorService().execute {
            val settings = OpenChamberSettings.getInstance().state
            val mcpPort = settings.mcpPort.takeIf(ServerCommand::isValidPort) ?: 64342
            val result = try {
                connect(baseUrl, directory, mcpPort, settings.mcpTransport)
            } catch (t: Throwable) {
                Result(
                    ok = false,
                    message = "Could not reach OpenChamber at $baseUrl. ${t.message ?: t.javaClass.simpleName}",
                    mcpListening = false,
                )
            }
            if (project.isDisposed) {
                return@execute
            }
            if (!result.ok && !result.mcpListening && attempt < 2) {
                AppExecutorUtil.getAppScheduledExecutorService().schedule({
                    connectAsync(project, baseUrl, directory, quietSuccess, attempt + 1)
                }, 3, TimeUnit.SECONDS)
                return@execute
            }
            if (result.ok) {
                if (!quietSuccess) {
                    notify(project, result.message, NotificationType.INFORMATION)
                }
                return@execute
            }

            val actions = mutableListOf<NotificationAction>()
            if (!result.mcpListening) {
                actions += NotificationAction.createSimple("Open MCP Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project)
                }
            }
            actions += NotificationAction.createSimple("Retry") {
                connectAsync(project, baseUrl, directory, quietSuccess = false)
            }
            notify(project, result.message, NotificationType.WARNING, actions)
        }
    }

    fun notifyNoServer(project: Project) {
        notify(
            project,
            "Start the OpenChamber server before connecting WebStorm's MCP server.",
            NotificationType.WARNING,
        )
    }

    private fun login(baseUrl: String, password: String): String? {
        val body = buildString {
            append("{\"password\":\"")
            append(escapeJson(password))
            append("\",\"issueClientToken\":true,\"dedupeKey\":\"openchamber-webstorm-plugin\",\"clientLabel\":\"OpenChamber WebStorm plugin\"}")
        }
        val response = sendRequest("$baseUrl/auth/session", "POST", body, null)
        if (response.statusCode() !in 200..299) {
            return null
        }
        return Regex("\"clientToken\"\\s*:\\s*\"([^\"]+)\"").find(response.body())?.groupValues?.get(1)
    }

    private fun send(
        baseUrl: String,
        directory: String?,
        method: String,
        body: String,
        token: String?,
    ): HttpResponse<String> {
        val uri = "$baseUrl/api/config/mcp/$SERVER_NAME" + directoryQuery(directory)
        return sendRequest(uri, method, body, token)
    }

    private fun sendRequest(uri: String, method: String, body: String, token: String?): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(uri))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        val request = builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    internal fun payload(
        url: String,
        includeScope: Boolean,
        transport: String,
        ideToken: String?,
    ): String = buildString {
        append("{\"type\":\"remote\",\"url\":\"")
        append(escapeJson(url))
        append("\",\"oauth\":false")
        append(",\"protocol\":\"").append(if (transport == "stream") "auto" else "legacy").append('"')
        if (!ideToken.isNullOrBlank()) {
            append(",\"headers\":{\"Authorization\":\"Bearer ").append(escapeJson(ideToken)).append("\"}")
        }
        if (includeScope) {
            append(",\"scope\":\"user\"")
        }
        append('}')
    }

    private fun directoryQuery(directory: String?): String {
        if (directory.isNullOrBlank()) {
            return ""
        }
        return "?directory=" + URLEncoder.encode(directory, StandardCharsets.UTF_8)
    }

    private fun failureMessage(action: String, response: HttpResponse<String>): String {
        val detail = response.body().trim().take(300)
        return when (response.statusCode()) {
            401, 403 -> "OpenChamber requires a UI password. Start WebStorm with OPENCHAMBER_UI_PASSWORD set, or add the server from OpenChamber Settings | MCP."
            else -> "Could not $action the WebStorm MCP entry (HTTP ${response.statusCode()}). $detail"
        }
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun notify(
        project: Project,
        content: String,
        type: NotificationType,
        actions: List<NotificationAction> = emptyList(),
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("OpenChamber")
            .createNotification("OpenChamber", content, type)
        actions.forEach(notification::addAction)
        notification.notify(project)
    }
}
