package governance

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

internal object ModuleDependencyGovernance {

    private const val RULES_DOC = "TODOs/module_dependency_governance.md"

    private val allKnownModules: Set<String> =
        setOf(
            ":app",
            ":anime_component",
            ":local_component",
            ":user_component",
            ":storage_component",
            ":player_component",
            ":data_component",
            ":core_contract_component",
            ":core_log_component",
            ":core_system_component",
            ":core_network_component",
            ":core_database_component",
            ":core_storage_component",
            ":core_ui_component",
            ":bilibili_component",
            // Gradle "container" project for nested :repository:* modules.
            ":repository",
            ":repository:danmaku",
            ":repository:immersion_bar",
            ":repository:panel_switch",
            ":repository:seven_zip",
            ":repository:thunder",
            ":repository:video_cache"
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
            ":app" to
                setOf(
                    ":anime_component",
                    ":local_component",
                    ":user_component",
                    ":storage_component",
                    ":player_component",
                    ":core_system_component",
                    ":core_log_component",
                    ":core_ui_component",
                    ":core_contract_component",
                    ":data_component"
                ),

            // feature
            ":anime_component" to
                setOf(
                    ":core_ui_component",
                    ":core_system_component",
                    ":core_log_component",
                    ":core_network_component",
                    ":core_database_component",
                    ":core_storage_component",
                    ":core_contract_component",
                    ":data_component"
                ),
            ":local_component" to
                setOf(
                    ":bilibili_component",
                    ":core_ui_component",
                    ":core_system_component",
                    ":core_log_component",
                    ":core_network_component",
                    ":core_database_component",
                    ":core_storage_component",
                    ":core_contract_component",
                    ":data_component"
                ),
            ":user_component" to
                setOf(
                    ":bilibili_component",
                    ":core_ui_component",
                    ":core_system_component",
                    ":core_log_component",
                    ":core_network_component",
                    ":core_database_component",
                    ":core_storage_component",
                    ":core_contract_component",
                    ":data_component"
                ),
            ":storage_component" to
                setOf(
                    ":bilibili_component",
                    ":core_ui_component",
                    ":core_system_component",
                    ":core_log_component",
                    ":core_network_component",
                    ":core_database_component",
                    ":core_storage_component",
                    ":core_contract_component",
                    ":data_component"
                ),
            ":player_component" to
                setOf(
                    ":core_ui_component",
                    ":core_system_component",
                    ":core_log_component",
                    ":core_network_component",
                    ":core_database_component",
                    ":core_storage_component",
                    ":core_contract_component",
                    ":data_component",
                    ":repository:danmaku",
                    ":repository:panel_switch",
                    ":repository:video_cache"
                ),

            // data / contract / runtime
            ":data_component" to emptySet(),
            ":core_contract_component" to setOf(":data_component"),
            ":core_log_component" to setOf(":data_component"),
            ":core_system_component" to
                setOf(
                    ":core_contract_component",
                    ":core_log_component",
                    ":data_component"
                ),

            // infra
            ":core_network_component" to
                setOf(
                    ":core_system_component",
                    ":core_log_component",
                    ":data_component"
                ),
            ":core_database_component" to
                setOf(
                    ":core_system_component",
                    ":data_component"
                ),
            ":core_storage_component" to
                setOf(
                    ":bilibili_component",
                    ":core_contract_component",
                    ":core_database_component",
                    ":core_log_component",
                    ":core_network_component",
                    ":core_system_component",
                    ":data_component",
                    ":repository:seven_zip",
                    ":repository:thunder"
                ),
            ":bilibili_component" to
                setOf(
                    ":core_contract_component",
                    ":core_database_component",
                    ":core_log_component",
                    ":core_network_component",
                    ":core_system_component",
                    ":data_component"
                ),

            // ui (must not depend on infra impl layer; repo allowlist is restricted)
            ":core_ui_component" to
                setOf(
                    ":core_contract_component",
                    ":core_log_component",
                    ":core_system_component",
                    ":data_component",
                    ":repository:immersion_bar"
                ),

            // repository: no internal module deps
            ":repository" to emptySet(),
            ":repository:danmaku" to emptySet(),
            ":repository:immersion_bar" to emptySet(),
            ":repository:panel_switch" to emptySet(),
            ":repository:seven_zip" to emptySet(),
            ":repository:thunder" to emptySet(),
            ":repository:video_cache" to emptySet()
        )

    /**
     * Extra allowlist for test-only configurations (e.g., testImplementation).
     *
     * Source: v2 rules in [RULES_DOC] §4.3.2.
     */
    private val allowedTestOnlyProjectDependencies: Map<String, Set<String>> =
        mapOf(
            // core_network_component: tests may depend on core_contract_component.
            ":core_network_component" to setOf(":core_contract_component")
        )

    /**
     * Bilibili dependency whitelist (DR-0002).
     * Any new direct dependency to :bilibili_component must be explicitly approved here + in docs.
     */
    private val bilibiliDependentWhitelist: Set<String> =
        setOf(
            ":local_component",
            ":user_component",
            ":storage_component",
            ":core_storage_component"
        )

    /**
     * Allowed *project* dependencies declared via api(...) configurations (DR-0003).
     * We only validate project(":...") here, not external artifacts.
     */
    private val allowedApiProjectDependencies: Map<String, Set<String>> =
        mapOf(
            ":core_contract_component" to setOf(":data_component"),
            ":core_ui_component" to
                setOf(
                    ":repository:immersion_bar",
                    ":data_component"
                ),
            ":core_network_component" to setOf(":data_component"),
            ":core_database_component" to setOf(":data_component"),
            ":core_log_component" to setOf(":data_component")
        )

    fun verify(rootProject: Project) {
        val allGradleModules = rootProject.subprojects.map { it.path }.toSet()
        val unknownModules = allGradleModules - allKnownModules
        if (unknownModules.isNotEmpty()) {
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

        val violations = mutableListOf<Violation>()
        rootProject.subprojects
            .sortedBy { it.path }
            .forEach { project ->
                val fromPath = project.path
                val allowedMain = allowedMainProjectDependencies.getValue(fromPath)
                val allowedTestExtra = allowedTestOnlyProjectDependencies[fromPath].orEmpty()
                val allowedApi = allowedApiProjectDependencies[fromPath].orEmpty()

                for (configuration in project.configurations.sortedBy { it.name }) {
                    val configName = configuration.name
                    if (!isDeclaredDependencyConfiguration(configName)) continue

                    val declaredProjectDeps =
                        configuration.dependencies
                            .withType(ProjectDependency::class.java)
                            .sortedBy { it.dependencyProject.path }
                    if (declaredProjectDeps.isEmpty()) continue

                    val isTestConfig = isTestConfiguration(configName)
                    val allowedForConfig =
                        if (isTestConfig) {
                            allowedMain + allowedTestExtra
                        } else {
                            allowedMain
                        }

                    val isApiConfig = isApiConfiguration(configName)
                    declaredProjectDeps.forEach { dependency ->
                        val toPath = dependency.dependencyProject.path

                        if (toPath !in allowedForConfig) {
                            val reason =
                                when {
                                    toPath == ":bilibili_component" && fromPath !in bilibiliDependentWhitelist ->
                                        "违反 DR-0002：仅允许 ${bilibiliDependentWhitelist.sorted().joinToString()} 直接依赖 :bilibili_component"
                                    else ->
                                        "不在 v2 允许矩阵内"
                                }
                            violations +=
                                Violation(
                                    from = fromPath,
                                    configuration = configName,
                                    to = toPath,
                                    reason = reason
                                )
                        }

                        if (isApiConfig && toPath !in allowedApi) {
                            violations +=
                                Violation(
                                    from = fromPath,
                                    configuration = configName,
                                    to = toPath,
                                    reason = "违反 DR-0003：禁止通过 api(project(...)) 泄漏该模块"
                                )
                        }
                    }
                }
            }

        if (violations.isNotEmpty()) {
            throw GradleException(formatViolations(violations))
        }
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
