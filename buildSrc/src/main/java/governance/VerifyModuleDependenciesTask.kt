package governance

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

abstract class VerifyModuleDependenciesTask : DefaultTask() {

    init {
        group = "verification"
        description = "Verify module dependency governance rules (v2)."
    }

    @TaskAction
    fun run() {
        ModuleDependencyGovernance.verify(project.rootProject)
    }
}

