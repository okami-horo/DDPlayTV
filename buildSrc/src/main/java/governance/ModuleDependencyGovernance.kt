package governance

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

internal object ModuleDependencyGovernance {

    private const val RULES_DOC = "document/architecture/module_dependency_governance.md"
    private const val MODULE_APP = ":app"
    private const val MODULE_ANIME = ":anime_component"
    private const val MODULE_LOCAL = ":local_component"
    private const val MODULE_USER = ":user_component"
    private const val MODULE_STORAGE = ":storage_component"
    private const val MODULE_PLAYER = ":player_component"
    private const val MODULE_DATA = ":data_component"
    private const val MODULE_CONTRACT = ":core_contract_component"
    private const val MODULE_LOG = ":core_log_component"
    private const val MODULE_SYSTEM = ":core_system_component"
    private const val MODULE_NETWORK = ":core_network_component"
    private const val MODULE_DATABASE = ":core_database_component"
    private const val MODULE_STORAGE_CORE = ":core_storage_component"
    private const val MODULE_UI = ":core_ui_component"
    private const val MODULE_REPOSITORY = ":repository"
    private const val MODULE_REPO_DANMAKU = ":repository:danmaku"
    private const val MODULE_REPO_IMMERSION = ":repository:immersion_bar"
    private const val MODULE_REPO_PANEL = ":repository:panel_switch"
    private const val MODULE_REPO_SEVEN_ZIP = ":repository:seven_zip"
    private const val MODULE_REPO_THUNDER = ":repository:thunder"
    private const val MODULE_REPO_VIDEO_CACHE = ":repository:video_cache"
    private const val MODULE_REPO_MPV = ":repository:mpv"
    private const val MODULE_REPO_MPV_NATIVE = ":repository:mpv_native"

    private val allKnownModules: Set<String> =
        setOf(
            MODULE_APP,
            MODULE_ANIME,
            MODULE_LOCAL,
            MODULE_USER,
            MODULE_STORAGE,
            MODULE_PLAYER,
            MODULE_DATA,
            MODULE_CONTRACT,
            MODULE_LOG,
            MODULE_SYSTEM,
            MODULE_NETWORK,
            MODULE_DATABASE,
            MODULE_STORAGE_CORE,
            MODULE_UI,
            // Gradle "container" project for nested :repository:* modules.
            MODULE_REPOSITORY,
            MODULE_REPO_DANMAKU,
            MODULE_REPO_IMMERSION,
            MODULE_REPO_PANEL,
            MODULE_REPO_SEVEN_ZIP,
            MODULE_REPO_THUNDER,
            MODULE_REPO_VIDEO_CACHE,
            MODULE_REPO_MPV,
            MODULE_REPO_MPV_NATIVE
        )

    /**
     * The v2 governance allowlist for *direct* Gradle module dependencies (project(":...")).
     *
     * Source of truth:
     * - [RULES_DOC] §4.3 / §6 (DR-0002 / DR-0003)
     * - [document/architecture/module_dependencies_snapshot.md] as current baseline.
     */
    private val allowedMainProjectDependencies: Map<String, Set<String>> =
        mapOf(
            // app shell (stage 4 baseline: keep app as composition root, avoid infra impl deps)
            MODULE_APP to
                setOf(
                    MODULE_ANIME,
                    MODULE_LOCAL,
                    MODULE_USER,
                    MODULE_STORAGE,
                    MODULE_PLAYER,
                    MODULE_SYSTEM,
                    MODULE_LOG,
                    MODULE_UI,
                    MODULE_CONTRACT,
                    MODULE_DATA
                ),

            // feature
            MODULE_ANIME to
                setOf(
                    MODULE_UI,
                    MODULE_SYSTEM,
                    MODULE_LOG,
                    MODULE_NETWORK,
                    MODULE_DATABASE,
                    MODULE_STORAGE_CORE,
                    MODULE_CONTRACT,
                    MODULE_DATA
                ),
            MODULE_LOCAL to
                setOf(
                    MODULE_UI,
                    MODULE_SYSTEM,
                    MODULE_LOG,
                    MODULE_NETWORK,
                    MODULE_DATABASE,
                    MODULE_STORAGE_CORE,
                    MODULE_CONTRACT,
                    MODULE_DATA
                ),
            MODULE_USER to
                setOf(
                    MODULE_UI,
                    MODULE_SYSTEM,
                    MODULE_LOG,
                    MODULE_NETWORK,
                    MODULE_DATABASE,
                    MODULE_STORAGE_CORE,
                    MODULE_CONTRACT,
                    MODULE_DATA
                ),
            MODULE_STORAGE to
                setOf(
                    MODULE_UI,
                    MODULE_SYSTEM,
                    MODULE_LOG,
                    MODULE_NETWORK,
                    MODULE_DATABASE,
                    MODULE_STORAGE_CORE,
                    MODULE_CONTRACT,
                    MODULE_DATA
                ),
            MODULE_PLAYER to
                setOf(
                    MODULE_UI,
                    MODULE_SYSTEM,
                    MODULE_LOG,
                    MODULE_NETWORK,
                    MODULE_DATABASE,
                    MODULE_STORAGE_CORE,
                    MODULE_CONTRACT,
                    MODULE_DATA,
                    MODULE_REPO_DANMAKU,
                    MODULE_REPO_PANEL,
                    MODULE_REPO_VIDEO_CACHE,
                    MODULE_REPO_MPV
                ),

            // data / contract / runtime
            MODULE_DATA to emptySet(),
            MODULE_CONTRACT to setOf(MODULE_DATA),
            MODULE_LOG to setOf(MODULE_DATA),
            MODULE_SYSTEM to
                setOf(
                    MODULE_CONTRACT,
                    MODULE_LOG,
                    MODULE_DATA
                ),

            // infra
            MODULE_NETWORK to
                setOf(
                    MODULE_SYSTEM,
                    MODULE_LOG,
                    MODULE_DATA
                ),
            MODULE_DATABASE to
                setOf(
                    MODULE_SYSTEM,
                    MODULE_DATA
                ),
            MODULE_STORAGE_CORE to
                setOf(
                    MODULE_CONTRACT,
                    MODULE_DATABASE,
                    MODULE_LOG,
                    MODULE_NETWORK,
                    MODULE_SYSTEM,
                    MODULE_DATA,
                    MODULE_REPO_SEVEN_ZIP,
                    MODULE_REPO_THUNDER
                ),
            // ui (must not depend on infra impl layer; repo allowlist is restricted)
            MODULE_UI to
                setOf(
                    MODULE_CONTRACT,
                    MODULE_LOG,
                    MODULE_SYSTEM,
                    MODULE_DATA,
                    MODULE_REPO_IMMERSION
                ),

            // Repository wrappers may depend only on their explicitly named native artifact.
            MODULE_REPOSITORY to emptySet(),
            MODULE_REPO_DANMAKU to emptySet(),
            MODULE_REPO_IMMERSION to emptySet(),
            MODULE_REPO_PANEL to emptySet(),
            MODULE_REPO_SEVEN_ZIP to emptySet(),
            MODULE_REPO_THUNDER to emptySet(),
            MODULE_REPO_VIDEO_CACHE to emptySet(),
            MODULE_REPO_MPV to setOf(MODULE_REPO_MPV_NATIVE),
            MODULE_REPO_MPV_NATIVE to emptySet()
        )

    /**
     * Extra allowlist for test-only configurations (e.g., testImplementation).
     *
     * Source: v2 rules in [RULES_DOC] §4.3.2.
     */
    private val allowedTestOnlyProjectDependencies: Map<String, Set<String>> =
        mapOf(
            // core_network_component: tests may depend on core_contract_component.
            MODULE_NETWORK to setOf(MODULE_CONTRACT),
            // App instrumentation seeds local/history fixtures and verifies Activity source retention.
            MODULE_APP to setOf(MODULE_STORAGE_CORE, MODULE_DATABASE)
        )

    /**
     * Allowed *project* dependencies declared via api(...) configurations (DR-0003).
     * We only validate project(":...") here, not external artifacts.
     */
    private val allowedApiProjectDependencies: Map<String, Set<String>> =
        mapOf(
            MODULE_CONTRACT to setOf(MODULE_DATA),
            MODULE_UI to
                setOf(
                    MODULE_REPO_IMMERSION,
                    MODULE_DATA
                ),
            MODULE_NETWORK to setOf(MODULE_DATA),
            MODULE_DATABASE to setOf(MODULE_DATA),
            MODULE_LOG to setOf(MODULE_DATA)
        )

    fun verify(rootProject: Project) {
        validateKnownModules(rootProject)

        val violations =
            rootProject.subprojects
                .sortedBy { it.path }
                .flatMap { project -> collectProjectViolations(project) }

        if (violations.isNotEmpty()) {
            throw GradleException(formatViolations(violations))
        }
    }

    private fun validateKnownModules(rootProject: Project) {
        val allGradleModules = rootProject.subprojects.map { it.path }.toSet()
        val unknownModules = allGradleModules - allKnownModules
        if (unknownModules.isEmpty()) return

        throw GradleException(
            buildString {
                appendLine("模块依赖治理校验失败：存在未纳入规则的模块（需补充分层与依赖矩阵）")
                appendLine("规则文档：$RULES_DOC")
                appendLine("未识别模块：")
                unknownModules.sorted().forEach { appendLine("- $it") }
                appendLine()
                appendLine("处理方式：")
                appendLine("- 更新 $RULES_DOC（分层/允许矩阵/白名单）")
                appendLine("- 同步更新 buildSrc 的 ModuleDependencyGovernance 规则后再合入")
            }
        )
    }

    private fun collectProjectViolations(project: Project): List<Violation> {
        val fromPath = project.path
        val allowedMain = allowedMainProjectDependencies.getValue(fromPath)
        val allowedTestExtra = allowedTestOnlyProjectDependencies[fromPath].orEmpty()
        val allowedApi = allowedApiProjectDependencies[fromPath].orEmpty()

        return project.configurations
            .sortedBy { it.name }
            .flatMap { configuration ->
                val configName = configuration.name
                if (!isDeclaredDependencyConfiguration(configName)) {
                    return@flatMap emptyList()
                }

                val declaredProjectDeps =
                    configuration.dependencies
                        .withType(ProjectDependency::class.java)
                        .sortedBy { it.dependencyProject.path }
                if (declaredProjectDeps.isEmpty()) {
                    return@flatMap emptyList()
                }

                collectConfigurationViolations(
                    fromPath = fromPath,
                    configurationName = configName,
                    dependencies = declaredProjectDeps,
                    allowedMain = allowedMain,
                    allowedTestExtra = allowedTestExtra,
                    allowedApi = allowedApi,
                )
            }
    }

    private fun collectConfigurationViolations(
        fromPath: String,
        configurationName: String,
        dependencies: List<ProjectDependency>,
        allowedMain: Set<String>,
        allowedTestExtra: Set<String>,
        allowedApi: Set<String>,
    ): List<Violation> {
        val allowedForConfiguration =
            if (isTestConfiguration(configurationName)) {
                allowedMain + allowedTestExtra
            } else {
                allowedMain
            }
        val isApiConfig = isApiConfiguration(configurationName)
        val violations = mutableListOf<Violation>()

        dependencies.forEach { dependency ->
            val toPath = dependency.dependencyProject.path

            if (toPath !in allowedForConfiguration) {
                violations +=
                    Violation(
                        from = fromPath,
                        configuration = configurationName,
                        to = toPath,
                        reason = "不在 v2 允许矩阵内",
                    )
            }

            if (isApiConfig && toPath !in allowedApi) {
                violations +=
                    Violation(
                        from = fromPath,
                        configuration = configurationName,
                        to = toPath,
                        reason = "违反 DR-0003：禁止通过 api(project(...)) 泄漏该模块",
                    )
            }
        }

        return violations
    }

    private fun formatViolations(violations: List<Violation>): String =
        buildString {
            appendLine("模块依赖治理校验失败（v2）")
            appendLine("规则文档：$RULES_DOC")
            appendLine()
            appendLine("违例列表：")

            val grouped = violations.groupBy { it.from }.toSortedMap()
            grouped.forEach { (from, list) ->
                appendLine("- $from")
                list
                    .sortedWith(compareBy<Violation> { it.to }.thenBy { it.configuration })
                    .forEach { violation ->
                        appendLine(
                            "  - ${violation.configuration}: ${violation.to}（${violation.reason}）"
                        )
                    }
            }

            appendLine()
            appendLine("修复建议：")
            appendLine("- 如果是 feature->feature 或 core/ui->infra 等分层违例：优先把类型下沉到 :core_contract_component / :data_component")
            appendLine("- 如果确需新增依赖：先更新 $RULES_DOC 的允许矩阵/白名单（含 DR），并同步更新 buildSrc 规则")
        }

    private fun isTestConfiguration(configurationName: String): Boolean {
        return configurationName.contains("test", ignoreCase = true)
    }

    private fun isApiConfiguration(configurationName: String): Boolean {
        return configurationName.lowercase().endsWith("api")
    }

    private fun isDeclaredDependencyConfiguration(configurationName: String): Boolean {
        val name = configurationName.lowercase()

        // Classpath/configurations are usually resolved outputs; they may include plugin-injected
        // (and even self) dependencies, which are not part of our governance scope.
        if (name.endsWith("classpath")) return false
        if (name.endsWith("elements")) return false

        return when {
            name == "api" || name.endsWith("api") -> true
            name == "implementation" || name.endsWith("implementation") -> true
            name == "compileonly" || name.endsWith("compileonly") -> true
            name == "runtimeonly" || name.endsWith("runtimeonly") -> true
            name == "kapt" || name.startsWith("kapt") -> true
            name.contains("annotationprocessor") -> true
            else -> false
        }
    }

    private data class Violation(
        val from: String,
        val configuration: String,
        val to: String,
        val reason: String
    )
}
