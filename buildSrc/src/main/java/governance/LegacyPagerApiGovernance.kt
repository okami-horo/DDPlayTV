package governance

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

internal object LegacyPagerApiGovernance {

    private const val BASELINE_FILE = "document/architecture/legacy_pager_api_baseline.tsv"

    private val fragmentPagerAdapterRegex = Regex("""\bFragmentPagerAdapter\b""")
    private val viewPagerRegex = Regex("""\bViewPager\b""")

    fun verify(rootProject: Project) {
        val baselineFile = rootProject.file(BASELINE_FILE)
        if (!baselineFile.exists()) {
            throw GradleException(
                buildString {
                    appendLine("旧版 Pager API 门禁失败：缺少基线文件")
                    appendLine("基线文件：$BASELINE_FILE")
                    appendLine()
                    appendLine("处理方式：")
                    appendLine("- 请恢复/补齐基线文件后再执行校验")
                }
            )
        }

        val baseline = parseBaseline(baselineFile)
        val current = scanLegacyPagerApiUsage(rootProject)

        val violations =
            current
                .mapNotNull { (path, counts) ->
                    val allowed = baseline[path] ?: Counts(fragmentPagerAdapter = 0, viewPager = 0)
                    if (counts.exceeds(allowed)) {
                        Violation(
                            path = path,
                            current = counts,
                            allowed = allowed
                        )
                    } else {
                        null
                    }
                }
                .sortedBy { it.path }

        if (violations.isEmpty()) return

        throw GradleException(formatViolations(violations))
    }

    private fun parseBaseline(file: File): Map<String, Counts> {
        val baseline = mutableMapOf<String, Counts>()

        file.useLines { lines ->
            lines.forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || line.startsWith("#")) return@forEachIndexed

                val parts = line.split(Regex("\\s+"), limit = 3)
                if (parts.size != 3) {
                    throw GradleException(
                        "旧版 Pager API 基线解析失败：第 ${index + 1} 行格式错误（需要 3 列）：$rawLine"
                    )
                }

                val path = parts[0]
                val fragmentPagerAdapter =
                    parts[1].toIntOrNull()
                        ?: throw GradleException(
                            "旧版 Pager API 基线解析失败：第 ${index + 1} 行 FragmentPagerAdapter 计数非法：$rawLine"
                        )
                val viewPager =
                    parts[2].toIntOrNull()
                        ?: throw GradleException(
                            "旧版 Pager API 基线解析失败：第 ${index + 1} 行 ViewPager 计数非法：$rawLine"
                        )

                if (fragmentPagerAdapter < 0 || viewPager < 0) {
                    throw GradleException(
                        "旧版 Pager API 基线解析失败：第 ${index + 1} 行计数不可为负：$rawLine"
                    )
                }

                baseline[path] = Counts(fragmentPagerAdapter = fragmentPagerAdapter, viewPager = viewPager)
            }
        }

        return baseline
    }

    private fun scanLegacyPagerApiUsage(rootProject: Project): Map<String, Counts> {
        val rootDir = rootProject.rootDir.toPath()
        val results = mutableMapOf<String, Counts>()

        rootProject.subprojects
            .sortedBy { it.path }
            .forEach { project ->
                val sources =
                    project.fileTree(project.projectDir) {
                        include("src/**/*.kt")
                        include("src/**/*.java")
                        include("src/**/*.xml")
                        exclude("**/build/**")
                        exclude("**/generated/**")
                    }

                sources.files
                    .sortedBy { it.path }
                    .forEach fileLoop@{ file ->
                        val text = file.readText()
                        val counts =
                            Counts(
                                fragmentPagerAdapter = fragmentPagerAdapterRegex.findAll(text).count(),
                                viewPager = viewPagerRegex.findAll(text).count()
                            )
                        if (counts.isEmpty()) return@fileLoop

                        val relativePath =
                            rootDir.relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                        results[relativePath] = counts
                    }
            }

        return results
    }

    private fun formatViolations(violations: List<Violation>): String =
        buildString {
            appendLine("旧版 Pager API 门禁失败：检测到新增旧版 ViewPager/FragmentPagerAdapter 用法")
            appendLine("基线文件：$BASELINE_FILE")
            appendLine()
            appendLine("违例列表（当前值 > 基线上限）：")
            violations.forEach { violation ->
                appendLine("- ${violation.path}")
                if (violation.current.fragmentPagerAdapter > violation.allowed.fragmentPagerAdapter) {
                    appendLine(
                        "  - FragmentPagerAdapter: ${violation.current.fragmentPagerAdapter} > ${violation.allowed.fragmentPagerAdapter}"
                    )
                }
                if (violation.current.viewPager > violation.allowed.viewPager) {
                    appendLine("  - ViewPager: ${violation.current.viewPager} > ${violation.allowed.viewPager}")
                }
            }
            appendLine()
            appendLine("处理方式：")
            appendLine("- 禁止新增旧 API：请迁移到 ViewPager2/FragmentStateAdapter 后再提交")
            appendLine("- 如果是误判（例如注释/字符串中包含关键字），请调整文本避免命中门禁规则")
        }

    private data class Counts(
        val fragmentPagerAdapter: Int,
        val viewPager: Int
    ) {
        fun isEmpty(): Boolean = fragmentPagerAdapter == 0 && viewPager == 0

        fun exceeds(allowed: Counts): Boolean =
            fragmentPagerAdapter > allowed.fragmentPagerAdapter || viewPager > allowed.viewPager
    }

    private data class Violation(
        val path: String,
        val current: Counts,
        val allowed: Counts
    )
}
