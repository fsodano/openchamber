package com.fsodano.openchamber.webstorm

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JProgressBar

class OpenChamberPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val server: OpenChamberServer = project.service()
    private val settings = OpenChamberSettings.getInstance()

    private val cards = CardLayout()
    private val cardPanel = JPanel(cards)
    private val browserHost = JPanel(BorderLayout())

    private val title = JBLabel("OpenChamber").apply {
        font = font.deriveFont(font.size2D + 2f)
    }
    private val detail = JBLabel(" ").apply { foreground = JBColor.GRAY }
    private val progress = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
    }
    private val logArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        rows = 8
        font = JBUI.Fonts.label()
    }
    private val logScroll = JBScrollPane(logArea).apply { isVisible = false }

    private val startButton = JButton("Start")
    private val browserButton = JButton("Open in Browser").apply { toolTipText = "Open OpenChamber in the system browser" }
    private val settingsButton = JButton("Settings")

    private val reloadButton = JButton("Reload").apply { toolTipText = "Reload the OpenChamber page" }
    private val openButton = JButton("Browser").apply { toolTipText = "Open OpenChamber in the system browser" }
    private val restartButton = JButton("Restart").apply { toolTipText = "Restart the OpenChamber server" }
    private val connectButton = JButton("Connect MCP").apply { toolTipText = "Register WebStorm's MCP server in OpenCode" }

    private var browser: JBCefBrowser? = null
    private var loadedUrl: String? = null
    private var snapshot = server.current
    private var autoConnectedUrl: String? = null
    private var autoConnectFailed = false

    private val listener: (OpenChamberServer.Snapshot) -> Unit = { next ->
        ApplicationManager.getApplication().invokeLater { render(next) }
    }

    init {
        Logger.getInstance(OpenChamberPanel::class.java).info("OpenChamber panel created")
        border = JBUI.Borders.empty()
        add(buildToolbar(), BorderLayout.NORTH)
        cardPanel.add(buildStatusCard(), CARD_STATUS)
        cardPanel.add(browserHost, CARD_BROWSER)
        add(cardPanel, BorderLayout.CENTER)

        startButton.addActionListener {
            if (snapshot.status == OpenChamberServer.Status.FAILED) {
                server.restart()
            } else {
                server.ensureStarted()
            }
        }
        browserButton.addActionListener {
            snapshot.url?.let(BrowserUtil::browse)
        }
        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project)
        }
        reloadButton.addActionListener {
            loadedUrl?.let { browser?.loadURL(it) }
        }
        openButton.addActionListener {
            snapshot.url?.let(BrowserUtil::browse)
        }
        restartButton.addActionListener {
            autoConnectedUrl = null
            autoConnectFailed = false
            server.restart()
        }
        connectButton.addActionListener {
            val url = server.baseUrl
            if (url == null) {
                McpConnector.notifyNoServer(project)
            } else {
                McpConnector.connectAsync(project, url, project.basePath, quietSuccess = false)
            }
        }

        server.addListener(listener)
        render(server.current)
        if (settings.state.autoStart) {
            server.ensureStarted()
        }
    }

    private fun buildToolbar(): JPanel {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        toolbar.add(reloadButton)
        toolbar.add(openButton)
        toolbar.add(restartButton)
        toolbar.add(connectButton)
        return toolbar
    }

    private fun buildStatusCard(): JPanel {
        val text = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(title)
            add(Box.createVerticalStrut(6))
            add(detail)
            add(Box.createVerticalStrut(12))
            add(progress)
            add(Box.createVerticalStrut(12))
            add(logScroll)
            add(Box.createVerticalGlue())
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(startButton)
            add(browserButton)
            add(settingsButton)
        }

        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(16)
            add(text, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    private fun render(next: OpenChamberServer.Snapshot) {
        snapshot = next
        reloadButton.isEnabled = next.status == OpenChamberServer.Status.RUNNING
        openButton.isEnabled = next.url != null
        restartButton.isEnabled = next.status != OpenChamberServer.Status.STARTING
        connectButton.isEnabled = next.status == OpenChamberServer.Status.RUNNING

        when (next.status) {
            OpenChamberServer.Status.STOPPED -> {
                title.text = "OpenChamber is stopped"
                detail.text = "Start the server to use OpenChamber."
                progress.isVisible = false
                logScroll.isVisible = false
                startButton.text = "Start"
                startButton.isVisible = true
                cards.show(cardPanel, CARD_STATUS)
            }

            OpenChamberServer.Status.STARTING -> {
                title.text = "Starting OpenChamber"
                detail.text = "Waiting for the server to answer."
                progress.isVisible = true
                logScroll.isVisible = false
                startButton.isVisible = false
                cards.show(cardPanel, CARD_STATUS)
            }

            OpenChamberServer.Status.RUNNING -> {
                title.text = "OpenChamber"
                detail.text = next.url.orEmpty()
                progress.isVisible = false
                logScroll.isVisible = false
                openBrowser(next.url.orEmpty())
                maybeConnectMcp(next.url)
            }

            OpenChamberServer.Status.FAILED -> {
                val error = next.error.orEmpty()
                title.text = "OpenChamber could not start"
                detail.text = error.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: "Unknown error"
                logArea.text = error
                logArea.caretPosition = 0
                progress.isVisible = false
                logScroll.isVisible = error.isNotBlank()
                startButton.text = "Retry"
                startButton.isVisible = true
                cards.show(cardPanel, CARD_STATUS)
            }
        }
    }

    private fun openBrowser(url: String) {
        if (url.isBlank()) {
            return
        }
        if (!JBCefApp.isSupported()) {
            Logger.getInstance(OpenChamberPanel::class.java).warn("JCEF is not supported in this IDE")
            title.text = "JCEF is not available"
            detail.text = "Open OpenChamber in your browser instead."
            progress.isVisible = false
            cards.show(cardPanel, CARD_STATUS)
            return
        }

        Logger.getInstance(OpenChamberPanel::class.java).info("Loading OpenChamber in JCEF: $url")
        val target = browser ?: JBCefBrowser().also { created ->
            browser = created
            Disposer.register(this, created)
            browserHost.add(created.component, BorderLayout.CENTER)
        }
        if (loadedUrl != url) {
            target.loadURL(url)
            loadedUrl = url
        }
        cards.show(cardPanel, CARD_BROWSER)
    }

    private fun maybeConnectMcp(url: String?) {
        if (url.isNullOrBlank() || !settings.state.autoConnectMcp || !ServerCommand.isLoopbackUrl(url)) {
            return
        }
        if (autoConnectedUrl == url) {
            return
        }
        autoConnectedUrl = url
        if (autoConnectFailed) {
            return
        }
        autoConnectFailed = true
        McpConnector.connectAsync(project, url, project.basePath, quietSuccess = true)
    }

    override fun dispose() {
        server.removeListener(listener)
    }

    private companion object {
        const val CARD_STATUS = "status"
        const val CARD_BROWSER = "browser"
    }
}
