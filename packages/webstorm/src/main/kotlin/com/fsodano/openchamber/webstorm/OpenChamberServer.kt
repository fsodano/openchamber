package com.fsodano.openchamber.webstorm

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OpenChamberServer(private val project: Project) : Disposable {

    enum class Status { STOPPED, STARTING, RUNNING, FAILED }

    data class Snapshot(
        val status: Status = Status.STOPPED,
        val url: String? = null,
        val error: String? = null,
    )

    private val logger = Logger.getInstance(OpenChamberServer::class.java)
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(800))
        // The OpenChamber Node server does not answer an h2c upgrade request,
        // and HttpClient defaults to HTTP/2 for plain http URLs.
        .version(HttpClient.Version.HTTP_1_1)
        .proxy(HttpClients.loopbackAwareProxySelector())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile
    private var snapshot = Snapshot()

    @Volatile
    private var generation = 0

    private var process: Process? = null

    @Volatile
    private var healthMonitor: ScheduledFuture<*>? = null

    val current: Snapshot
        get() = snapshot

    val baseUrl: String?
        get() = snapshot.url?.takeIf { snapshot.status == Status.RUNNING }

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners -= listener
    }

    fun ensureStarted() {
        synchronized(lock) {
            if (snapshot.status == Status.STARTING || snapshot.status == Status.RUNNING) {
                return
            }
        }
        start()
    }

    fun restart() {
        start()
    }

    fun stop() {
        healthMonitor?.cancel(false)
        healthMonitor = null
        val oldProcess: Process?
        synchronized(lock) {
            generation++
            oldProcess = process
            process = null
        }
        stopProcess(oldProcess)
        update(Snapshot(Status.STOPPED))
    }

    private fun start() {
        logger.info("OpenChamber start requested")
        healthMonitor?.cancel(false)
        healthMonitor = null
        val expected: Int
        val oldProcess: Process?
        synchronized(lock) {
            expected = ++generation
            oldProcess = process
            process = null
        }
        update(Snapshot(Status.STARTING))

        AppExecutorUtil.getAppExecutorService().execute {
            try {
                stopProcess(oldProcess)
                runStart(expected)
            } catch (t: Throwable) {
                if (expected == generation) {
                    logger.warn("OpenChamber failed to start", t)
                    update(Snapshot(Status.FAILED, error = t.message ?: t.javaClass.simpleName))
                }
            }
        }
    }

    private fun runStart(expected: Int) {
        val settings = OpenChamberSettings.getInstance().state

        val externalUrl = settings.serverUrl.trim()
        if (externalUrl.isNotEmpty()) {
            val url = ServerCommand.normalizeUrl(externalUrl)
            if (waitForHealth(url, expected, 20_000)) {
                markRunning(url, expected)
            } else if (expected == generation) {
                update(Snapshot(Status.FAILED, error = "No response from $url/health. Check the external server URL."))
            }
            return
        }

        val configuredPort = settings.port.takeIf(ServerCommand::isValidPort) ?: 3001
        if (isHealthy(ServerCommand.baseUrl(configuredPort))) {
            if (expected == generation) {
                markRunning(ServerCommand.baseUrl(configuredPort), expected)
            }
            return
        }

        val port = choosePort(configuredPort)
        val projectPath = project.basePath ?: project.projectFilePath ?: System.getProperty("user.home")
        val allowProjectCli = TrustedProjects.isProjectTrusted(project)
        val launch = ServerCommand.resolve(settings.command, projectPath, port, allowProjectCli)
        logger.info("Starting OpenChamber: ${launch.displayCommand}")

        val builder = ProcessBuilder(launch.command)
            .directory(File(launch.workdir))
            .redirectErrorStream(true)
        ServerCommand.sanitizeEnvironment(builder.environment())
        ServerCommand.applyOpenCodeBinary(builder.environment())
        builder.environment()["OPENCODE_BINARY"]?.takeIf { it.isNotBlank() }?.let {
            logger.info("Using OpenCode binary: $it")
        }
        val started = try {
            builder.start()
        } catch (e: IOException) {
            if (expected == generation) {
                update(
                    Snapshot(
                        Status.FAILED,
                        error = "Could not start the OpenChamber server.\n${launch.displayCommand}\n${e.message ?: ""}",
                    ),
                )
            }
            return
        }

        synchronized(lock) {
            if (expected != generation) {
                stopProcess(started)
                return
            }
            process = started
        }

        val tail = Collections.synchronizedList(ArrayDeque<String>())
        val reader = Thread {
            try {
                started.inputStream.bufferedReader().forEachLine { line ->
                    if (tail.size >= 200) {
                        tail.removeFirst()
                    }
                    tail.addLast(line)
                    logger.debug(line)
                }
            } catch (_: IOException) {
                // The process ended; the exit code below explains it.
            }
        }.apply {
            isDaemon = true
            name = "OpenChamber output"
            start()
        }

        val url = ServerCommand.baseUrl(port)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            if (expected != generation) {
                return
            }
            if (isHealthy(url)) {
                logger.info("OpenChamber health check passed: $url")
                markRunning(url, expected)
                return
            }
            if (!started.isAlive) {
                reader.join(500)
                if (expected == generation) {
                    update(
                        Snapshot(
                            Status.FAILED,
                            error = "The OpenChamber server exited with code ${started.exitValue()}.\n" +
                                snapshotLog(tail).takeLast(12).joinToString("\n"),
                        ),
                    )
                }
                return
            }
            Thread.sleep(300)
        }

        if (expected == generation) {
            stopProcess(started)
            update(
                Snapshot(
                    Status.FAILED,
                    error = "The OpenChamber server did not answer $url/health within 60 seconds.\n" +
                        snapshotLog(tail).takeLast(12).joinToString("\n"),
                ),
            )
        }
    }

    private fun markRunning(url: String, expected: Int) {
        update(Snapshot(Status.RUNNING, url = url))
        startHealthMonitor(url, expected)
    }

    private fun startHealthMonitor(url: String, expected: Int) {
        healthMonitor?.cancel(false)
        val failures = AtomicInteger()
        healthMonitor = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            {
                if (expected != generation || snapshot.status != Status.RUNNING || snapshot.url != url) {
                    return@scheduleWithFixedDelay
                }
                if (isHealthy(url)) {
                    failures.set(0)
                    return@scheduleWithFixedDelay
                }
                if (failures.incrementAndGet() >= 3 && expected == generation && snapshot.status == Status.RUNNING) {
                    healthMonitor?.cancel(false)
                    healthMonitor = null
                    update(
                        Snapshot(
                            Status.FAILED,
                            error = "The OpenChamber server at $url stopped responding. Restart it from the toolbar.",
                        ),
                    )
                }
            },
            5,
            5,
            TimeUnit.SECONDS,
        )
    }

    private fun snapshotLog(tail: MutableList<String>): List<String> = synchronized(tail) { tail.toList() }

    private fun waitForHealth(url: String, expected: Int, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (expected != generation) {
                return false
            }
            if (isHealthy(url)) {
                return true
            }
            Thread.sleep(300)
        }
        return false
    }

    private fun choosePort(configured: Int): Int {
        if (isPortFree(configured)) {
            return configured
        }
        for (candidate in configured + 1..configured + 20) {
            if (ServerCommand.isValidPort(candidate) && isPortFree(candidate)) {
                return candidate
            }
        }
        throw IllegalStateException("No free port near $configured. Set another server port in Settings | Tools | OpenChamber.")
    }

    private fun isPortFree(port: Int): Boolean =
        try {
            ServerSocket().use { socket ->
                socket.bind(InetSocketAddress(ServerCommand.HOST, port))
                true
            }
        } catch (_: IOException) {
            false
        }

    private fun isHealthy(baseUrl: String): Boolean {
        return try {
            val request = HttpRequest.newBuilder(URI.create("$baseUrl/health"))
                .timeout(Duration.ofSeconds(1))
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            val body = response.body()
            val healthy = response.statusCode() == 200 &&
                Regex("\"status\"\\s*:\\s*\"ok\"").containsMatchIn(body) &&
                Regex("\"openchamberVersion\"\\s*:").containsMatchIn(body)
            if (!healthy) {
                logger.debug("OpenChamber health probe $baseUrl returned ${response.statusCode()} ${body.take(120)}")
            }
            healthy
        } catch (e: Exception) {
            // A connection refused is expected until the server binds its port.
            logger.debug("OpenChamber health probe $baseUrl failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun update(next: Snapshot) {
        logger.info("OpenChamber state: ${next.status} ${next.url.orEmpty()}")
        snapshot = next
        val application = ApplicationManager.getApplication()
        if (application.isDisposed) {
            return
        }
        application.invokeLater {
            listeners.forEach { listener ->
                try {
                    listener(next)
                } catch (t: Throwable) {
                    logger.warn("OpenChamber listener failed", t)
                }
            }
        }
    }

    private fun stopProcess(target: Process?) {
        if (target == null) {
            return
        }
        target.descendants().forEach { it.destroy() }
        target.destroy()
        try {
            if (!target.waitFor(3, TimeUnit.SECONDS)) {
                target.descendants().forEach { it.destroyForcibly() }
                target.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun dispose() {
        val target: Process?
        synchronized(lock) {
            generation++
            target = process
            process = null
        }
        healthMonitor?.cancel(false)
        healthMonitor = null
        snapshot = Snapshot(Status.STOPPED)
        if (target == null) {
            return
        }
        target.descendants().forEach { it.destroy() }
        target.destroy()
        try {
            AppExecutorUtil.getAppExecutorService().execute { stopProcess(target) }
        } catch (_: Exception) {
            target.destroyForcibly()
        }
    }
}
