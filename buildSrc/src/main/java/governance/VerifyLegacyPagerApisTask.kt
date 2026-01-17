package governance

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

abstract class VerifyLegacyPagerApisTask : DefaultTask() {

    init {
        group = "verification"
        description =
            "Verify no new usage of legacy pager APIs (ViewPager / FragmentPagerAdapter) is introduced."
    }

    @TaskAction
    fun run() {
        LegacyPagerApiGovernance.verify(project.rootProject)
    }
}

