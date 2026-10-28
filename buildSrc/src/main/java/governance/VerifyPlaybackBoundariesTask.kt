package governance

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/** Syntax checks for the known playback write APIs; dependency direction is checked separately. */
abstract class VerifyPlaybackBoundariesTask : DefaultTask() {
    init {
        group = "verification"
        description = "Verify playback entries, source ownership and removal of legacy writers."
    }

    @TaskAction
    fun verify() {
        val disposable = Disposer.newDisposable()
        try {
            val environment = KotlinCoreEnvironment.createForProduction(
                disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES
            )
            val factory = KtPsiFactory(environment.project, false)
            val violations = linkedSetOf<String>()
            project.rootProject.subprojects.forEach { module ->
                module.fileTree("src") {
                    include("**/*.kt", "**/*.java", "**/*.xml")
                    exclude("test/**", "androidTest/**", "**/generated/**")
                }.forEach { file ->
                    val path = file.relativeTo(project.rootDir).invariantSeparatorsPath
                    val source = file.readText()
                    fun check(name: String, offset: Int, call: Boolean = false) {
                        val forbidden = name in removedSymbols ||
                            (name == "StorageVideoSourceFactory" && path !in sourceConstructionFiles) ||
                            (call && name == "StorageVideoSource" && path !in sourceConstructionFiles) ||
                            (name == "releasePlaybackResources" && path !in sourceReleaseFiles) ||
                            (name == "setVideoSource" && path !in backendSourceFiles) ||
                            (name == "setQueueMetadata" && path !in queueProjectionFiles) ||
                            (call && name == "ActivePlaybackSession" && path != sessionProvider) ||
                            (call && name == "PreparedPlayback" && path != sessionOwner)
                        if (forbidden) {
                            val line = source.take(offset).count { it == '\n' } + 1
                            violations.add("$path:$line: $name")
                        }
                    }
                    if (file.extension == "kt") {
                        factory.createFile(file.name, source).accept(object : KtTreeVisitorVoid() {
                            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                                check(expression.getReferencedName(), expression.textOffset)
                                super.visitSimpleNameExpression(expression)
                            }

                            override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                                declaration.name?.let { check(it, declaration.textOffset) }
                                super.visitNamedDeclaration(declaration)
                            }

                            override fun visitCallExpression(expression: KtCallExpression) {
                                (expression.calleeExpression as? KtNameReferenceExpression)?.let {
                                    check(it.getReferencedName(), it.textOffset, call = true)
                                }
                                super.visitCallExpression(expression)
                            }
                        })
                    } else {
                        // Java/XML supplementary scan: not type resolution; comments may also match.
                        Regex("[A-Za-z_][A-Za-z_0-9]*").findAll(source).forEach {
                            check(it.value, it.range.first, call = file.extension == "java")
                        }
                    }
                }
            }
            if (violations.isNotEmpty()) {
                throw GradleException("Playback ownership boundary violations:\n" + violations.joinToString("\n"))
            }
            logger.lifecycle("Playback ownership boundaries verified (Kotlin syntax; Java/XML token scan).")
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private companion object {
        const val STORAGE = "core_storage_component/src/main/java/com/xyoye/common_component/source/"
        const val PLAYER = "player_component/src/main/java/com/xyoye/player_component/"
        const val sessionOwner = PLAYER + "playback/session/ActivePlaybackSession.kt"
        const val sessionProvider = PLAYER + "service/PlaybackRequestServiceImpl.kt"
        val sourceConstructionFiles = setOf(
            STORAGE + "factory/StorageVideoSourceFactory.kt",
            STORAGE + "handoff/PlaybackSourceResolver.kt"
        )
        val sourceReleaseFiles = sourceConstructionFiles + setOf(
            STORAGE + "media/StorageVideoSource.kt",
            PLAYER + "playback/session/PreparedPlayback.kt",
            sessionOwner
        )
        val backendSourceFiles = setOf(
            PLAYER + "ui/activities/player/PlayerActivity.kt",
            "player_component/src/main/java/com/xyoye/player/DanDanVideoPlayer.kt"
        )
        val queueProjectionFiles = setOf(
            STORAGE + "media/StorageVideoSource.kt",
            PLAYER + "playback/session/PreparedPlayback.kt"
        )
        val removedSymbols = setOf(
            "VideoSourceManager", "Media3LaunchParams", "attachMedia3LaunchParams", "consumeMedia3LaunchParams",
            "PlayerInterceptorActivity", "PlayerInterceptorViewModel", "PlayerCenter", "Media3SessionStore",
            "Media3SessionController", "Media3CapabilityService", "prepareMedia3Session", "indexSource",
            "withPreparedPlaybackSource", "FallbackSequentialPlaybackAdapter", "activity_player_interceptor"
        )
    }
}
