package com.fsodano.openchamber.webstorm

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

class OpenChamberSettingsConfigurable : SearchableConfigurable {

    private val settings = OpenChamberSettings.getInstance()

    private val serverUrl = JBTextField().apply { columns = 36 }
    private val port = JBIntSpinner(3001, 1, 65535, 1)
    private val command = JBTextField().apply { columns = 36 }
    private val autoStart = JBCheckBox("Start the server when the tool window opens")
    private val mcpPort = JBIntSpinner(64342, 1, 65535, 1)
    private val transport = ComboBox(arrayOf("sse", "stream"))
    private val autoConnectMcp = JBCheckBox("Connect OpenCode to WebStorm MCP automatically")
    private val openOnStartup = JBCheckBox("Open the OpenChamber tool window when a project opens")

    private var panel: JPanel? = null

    override fun getId(): String = "com.fsodano.openchamber.settings"

    override fun getDisplayName(): String = "OpenChamber"

    override fun createComponent(): JComponent {
        val built = panel ?: FormBuilder.createFormBuilder()
            .addLabeledComponent("External server URL:", serverUrl)
            .addComponent(JBLabel("Leave empty to run a local OpenChamber server."))
            .addVerticalGap(8)
            .addLabeledComponent("Server port:", port)
            .addVerticalGap(8)
            .addLabeledComponent("Server command:", command)
            .addComponent(JBLabel("Leave empty to auto-detect the trusted repository CLI, openchamber, or bunx."))
            .addVerticalGap(8)
            .addComponent(autoStart)
            .addVerticalGap(12)
            .addLabeledComponent("MCP server port:", mcpPort)
            .addLabeledComponent("MCP transport:", transport)
            .addComponent(autoConnectMcp)
            .addComponent(openOnStartup)
            .addVerticalGap(8)
            .addComponent(JBLabel("Restart the server from the tool window after changing the port, command, or URL."))
            .panel
            .apply { border = JBUI.Borders.empty(8) }
            .also { panel = it }

        reset()
        return built
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return serverUrl.text.trim() != state.serverUrl ||
            port.number != state.port ||
            command.text.trim() != state.command ||
            autoStart.isSelected != state.autoStart ||
            mcpPort.number != state.mcpPort ||
            (transport.selectedItem as? String ?: state.mcpTransport) != state.mcpTransport ||
            autoConnectMcp.isSelected != state.autoConnectMcp ||
            openOnStartup.isSelected != state.openOnStartup
    }

    override fun apply() {
        val state = settings.state
        state.serverUrl = serverUrl.text.trim()
        state.port = port.number
        state.command = command.text.trim()
        state.autoStart = autoStart.isSelected
        state.mcpPort = mcpPort.number
        state.mcpTransport = (transport.selectedItem as? String) ?: "sse"
        state.autoConnectMcp = autoConnectMcp.isSelected
        state.openOnStartup = openOnStartup.isSelected
    }

    override fun reset() {
        val state = settings.state
        serverUrl.text = state.serverUrl
        port.number = state.port.coerceIn(1, 65535)
        command.text = state.command
        autoStart.isSelected = state.autoStart
        mcpPort.number = state.mcpPort.coerceIn(1, 65535)
        transport.selectedItem = if (state.mcpTransport == "stream") "stream" else "sse"
        autoConnectMcp.isSelected = state.autoConnectMcp
        openOnStartup.isSelected = state.openOnStartup
    }

    override fun disposeUIResources() {
        panel = null
    }
}
