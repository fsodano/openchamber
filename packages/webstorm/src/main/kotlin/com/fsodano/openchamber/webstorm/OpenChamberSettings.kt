package com.fsodano.openchamber.webstorm

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(name = "OpenChamberSettings", storages = [Storage("openchamber.xml")])
class OpenChamberSettings : PersistentStateComponent<OpenChamberSettings.State> {

    class State {
        var serverUrl: String = ""
        var port: Int = 3001
        var command: String = ""
        var autoStart: Boolean = true
        var mcpPort: Int = 64342
        var mcpTransport: String = "sse"
        var autoConnectMcp: Boolean = true
        var openOnStartup: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        fun getInstance(): OpenChamberSettings =
            ApplicationManager.getApplication().getService(OpenChamberSettings::class.java)
    }
}
