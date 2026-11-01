package com.fsodano.openchamber.webstorm

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

class OpenChamberStartupActivity : ProjectActivity {
    private val logger = Logger.getInstance(OpenChamberStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        val openOnStartup = OpenChamberSettings.getInstance().state.openOnStartup
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("OpenChamber")
        logger.info("OpenChamber startup: openOnStartup=$openOnStartup, toolWindow=${toolWindow != null}")
        if (openOnStartup) {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    ToolWindowManager.getInstance(project).getToolWindow("OpenChamber")?.show()
                }
            }
        }
    }
}
