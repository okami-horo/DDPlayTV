package com.xyoye.player.kernel.impl.media3.effect

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.C
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import com.xyoye.player.kernel.anime4k.Anime4kShaderAssets
import com.xyoye.player.kernel.impl.media3.Media3Diagnostics
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@UnstableApi
open class Anime4kMpvShaderProgram(
    context: Context,
    private val outputSizeProvider: () -> Size?,
    private val shaderFiles: List<String>,
) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents= */ false,
        /* texturePoolCapacity= */ 2,
    ) {
    private val appContext = context.applicationContext

    private data class PingPongTextureSet(
        var first: GlTextureInfo,
        var second: GlTextureInfo,
    )

    private data class ShaderPass(
        val description: String,
        val hookStage: String?,
        val binds: List<String>,
        val save: String?,
        val widthExpr: String?,
        val heightExpr: String?,
        val whenExpr: String?,
        val components: Int?,
        val code: String,
    ) {
        fun requiredSamplers(): List<String> {
            val required = LinkedHashSet<String>()
            required.add("MAIN")
            binds.forEach { bind ->
                if (bind.isNotBlank()) {
                    required.add(bind)
                }
            }
            return required.toList()
        }

        fun updatesMain(): Boolean = save == null || save == "MAIN"
    }

    private data class PassProgram(
        val pass: ShaderPass,
        val glProgram: GlProgram,
        val requiredSamplers: List<String>,
    )

    private data class BoundTexture(
        val texId: Int,
        val width: Int,
        val height: Int,
    )

    private data class ConfiguredPass(
        val program: PassProgram,
        val active: Boolean,
        val outputSize: Size,
    )

    private data class EvalContext(
        val mainSize: Size,
        val nativeSize: Size,
        val outputSize: Size,
        val savedSizes: Map<String, Size>,
    ) {
        fun resolve(token: String): Float? {
            val dim = token.substringAfterLast('.', missingDelimiterValue = "")
            val name = token.substringBeforeLast('.', missingDelimiterValue = token)
            if (dim != "w" && dim != "width" && dim != "h" && dim != "height") return null

            val size =
                when (name) {
                    "MAIN", "HOOKED" -> mainSize
                    "NATIVE" -> nativeSize
                    "OUTPUT" -> outputSize
                    else -> savedSizes[name]
                } ?: return null

            return if (dim == "w" || dim == "width") size.width.toFloat() else size.height.toFloat()
        }
    }

    private var configuredInputSize = Size.ZERO
    private var configuredOutputSize = Size.ZERO
    private var configuredOutputControlSize = Size.ZERO
    private var fallbackToCopy = false
    private var loggedFallbackToCopy = false
    private var loggedFirstFrame = false
    private var configuredPasses: List<ConfiguredPass> = emptyList()

    private val namedTextures = HashMap<String, GlTextureInfo>()
    private val pingPongTextures = HashMap<String, PingPongTextureSet>()
    private val pingPongSaveTargets = HashSet<String>()
    private var mainSwapA = GlTextureInfo.UNSET
    private var mainSwapB = GlTextureInfo.UNSET

    private val pipelinePrograms: List<PassProgram>
    private val copyProgram: GlProgram
    private val allPrograms: List<GlProgram>

    init {
        val passes = ArrayList<ShaderPass>()
        shaderFiles.forEach { filename ->
            val source = readAssetText("${Anime4kShaderAssets.ASSET_DIR}/$filename")
            passes.addAll(parseMpvShaderPasses(source))
        }
        Media3Diagnostics.logAnime4kShaderPipelineParsed(
            shaderFiles = shaderFiles,
            totalPasses = passes.size,
        )
        passes.forEach { pass ->
            val save = pass.save?.takeIf { it.isNotBlank() && it != "MAIN" } ?: return@forEach
            if (pass.binds.contains(save)) {
                pingPongSaveTargets.add(save)
            }
        }

        pipelinePrograms =
            passes.map { pass ->
                val requiredSamplers = pass.requiredSamplers()
                PassProgram(
                    pass = pass,
                    glProgram = GlProgram(VERTEX_SHADER, buildFragmentShader(pass, requiredSamplers)),
                    requiredSamplers = requiredSamplers,
                )
            }
        copyProgram = GlProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER)
        allPrograms = buildList {
            pipelinePrograms.forEach { add(it.glProgram) }
            add(copyProgram)
        }
    }

    @Throws(VideoFrameProcessingException::class)
    override fun configure(
        inputWidth: Int,
        inputHeight: Int
    ): Size {
        val safeInputWidth = max(inputWidth, 1)
        val safeInputHeight = max(inputHeight, 1)
        val inputSize = Size(safeInputWidth, safeInputHeight)
        val outputControlSize = outputSizeProvider.invoke() ?: inputSize
        if (inputSize == configuredInputSize && outputControlSize == configuredOutputControlSize) {
            return configuredOutputSize
        }

        configuredInputSize = inputSize
        configuredOutputControlSize = outputControlSize
        fallbackToCopy = false
        loggedFallbackToCopy = false
        loggedFirstFrame = false
        configuredPasses = emptyList()

        releaseAllTextures()
        try {
            configuredPasses = buildConfiguredPasses(inputSize, outputControlSize)
            configuredOutputSize =
                configuredPasses
                    .lastOrNull { it.active && it.program.pass.updatesMain() }
                    ?.outputSize
                    ?: inputSize

            Media3Diagnostics.logAnime4kShaderPipelinePlanned(
                inputSize = inputSize,
                outputControlSize = outputControlSize,
                totalPasses = configuredPasses.size,
                activePasses = configuredPasses.count { it.active },
                activeMainPasses = configuredPasses.count { it.active && it.program.pass.updatesMain() },
                outputSize = configuredOutputSize,
                skippedPasses =
                    configuredPasses.mapIndexedNotNull { index, configured ->
                        if (configured.active) null else "#${index + 1}:${configured.program.pass.description}"
                    },
            )

            // Allocate (or resize) named textures in the same order as runtime will use.
            configuredPasses.forEach { configured ->
                if (!configured.active) return@forEach
                val pass = configured.program.pass
                val save = pass.save?.takeIf { it.isNotBlank() } ?: return@forEach
                if (save == "MAIN") return@forEach
                if (pingPongSaveTargets.contains(save)) {
                    ensurePingPongTextureSet(save, configured.outputSize)
                } else {
                    ensureNamedTexture(save, configured.outputSize)
                }
            }

            val identity = GlUtil.create4x4IdentityMatrix()
            val bounds = GlUtil.getNormalizedCoordinateBounds()
            allPrograms.forEach { program ->
                program.use()
                program.setBufferAttribute(
                    "aFramePosition",
                    bounds,
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
                program.setFloatsUniform("uTransformationMatrix", identity)
                program.setFloatsUniform("uTexTransformationMatrix", identity)
            }
            Media3Diagnostics.logAnime4kShaderConfigured(
                inputSize = inputSize,
                outputSize = configuredOutputSize,
                fallbackToCopy = false,
                reason = "outputControl=${outputControlSize.width}x${outputControlSize.height}",
            )
        } catch (e: VideoFrameProcessingException) {
            fallbackToCopy = true
            configuredOutputSize = inputSize
            releaseAllTextures()
            Media3Diagnostics.logAnime4kShaderConfigured(
                inputSize = inputSize,
                outputSize = configuredOutputSize,
                fallbackToCopy = true,
                reason = "configure_failed_video_frame_processing_exception",
                glInfo = readGlInfo(),
                throwable = e,
            )
        } catch (e: GlUtil.GlException) {
            fallbackToCopy = true
            configuredOutputSize = inputSize
            releaseAllTextures()
            Media3Diagnostics.logAnime4kShaderConfigured(
                inputSize = inputSize,
                outputSize = configuredOutputSize,
                fallbackToCopy = true,
                reason = "configure_failed_gl_exception",
                glInfo = readGlInfo(),
                throwable = e,
            )
        }
        return configuredOutputSize
    }

    @Throws(VideoFrameProcessingException::class)
    override fun drawFrame(
        inputTexId: Int,
        presentationTimeUs: Long
    ) {
        if (fallbackToCopy) {
            if (!loggedFallbackToCopy) {
                loggedFallbackToCopy = true
                Media3Diagnostics.logAnime4kShaderConfigured(
                    inputSize = configuredInputSize,
                    outputSize = configuredOutputSize,
                    fallbackToCopy = true,
                    reason = "drawFrame_fallback_copy",
                    glInfo = readGlInfo(),
                )
            }
            renderCopy(inputTexId)
            return
        }

        val outputFboId = readCurrentFramebufferId()
        val outputSize = configuredOutputSize
        val inputSize = configuredInputSize

        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            Media3Diagnostics.logAnime4kShaderFirstFrame(
                inputSize = inputSize,
                outputSize = outputSize,
                activePasses = configuredPasses.count { it.active },
                outputFboId = outputFboId,
            )
        }

        val nativeTexture =
            BoundTexture(
                texId = inputTexId,
                width = inputSize.width,
                height = inputSize.height,
            )

        var currentTexture = nativeTexture
        val saved = HashMap<String, BoundTexture>()
        var swapToggle = 0

        configuredPasses.forEach { configured ->
            if (!configured.active) {
                return@forEach
            }

            val passProgram = configured.program
            val pass = passProgram.pass
            val targetSize = configured.outputSize

            val (targetTextureInfo, updatesMain) =
                if (pass.updatesMain()) {
                    val target =
                        if (swapToggle % 2 == 0) {
                            ensureSwapTextureA(targetSize)
                        } else {
                            ensureSwapTextureB(targetSize)
                        }
                    swapToggle++
                    target to true
                } else {
                    val name = pass.save?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (pingPongSaveTargets.contains(name)) {
                        val set = ensurePingPongTextureSet(name, targetSize)
                        val readTexId = saved[name]?.texId
                        val needsPingPong = passProgram.requiredSamplers.contains(name)
                        val target =
                            if (needsPingPong && readTexId == set.first.texId) {
                                set.second
                            } else if (needsPingPong && readTexId == set.second.texId) {
                                set.first
                            } else {
                                set.first
                            }
                        target to false
                    } else {
                        ensureNamedTexture(name, targetSize) to false
                    }
                }

            try {
                GlUtil.focusFramebufferUsingCurrentContext(
                    targetTextureInfo.fboId,
                    targetTextureInfo.width,
                    targetTextureInfo.height,
                )
                GlUtil.clearFocusedBuffers()

                val samplerResolver: (String) -> BoundTexture? = { samplerName ->
                    when (samplerName) {
                        "MAIN", "HOOKED" -> currentTexture
                        "NATIVE" -> nativeTexture
                        else -> saved[samplerName]
                    }
                }

                val program = passProgram.glProgram
                program.use()
                passProgram.requiredSamplers.forEachIndexed { index, samplerName ->
                    val bound =
                        samplerResolver(samplerName)
                            ?: throw VideoFrameProcessingException(
                                IllegalStateException("Anime4K missing bound texture: $samplerName"),
                            )
                    runCatching { program.setSamplerTexIdUniform(samplerName, bound.texId, index) }
                    program.setFloatsUniformIfPresent(
                        "${samplerName}_size",
                        floatArrayOf(bound.width.toFloat(), bound.height.toFloat()),
                    )
                    program.setFloatsUniformIfPresent(
                        "${samplerName}_pt",
                        floatArrayOf(1f / bound.width.toFloat(), 1f / bound.height.toFloat()),
                    )
                }
                program.bindAttributesAndUniforms()

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
                GlUtil.checkGlError()

                val produced =
                    BoundTexture(
                        texId = targetTextureInfo.texId,
                        width = targetTextureInfo.width,
                        height = targetTextureInfo.height,
                    )

                if (updatesMain) {
                    currentTexture = produced
                } else {
                    pass.save?.takeIf { it.isNotBlank() }?.let { saveName ->
                        saved[saveName] = produced
                    }
                }
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        try {
            GlUtil.focusFramebufferUsingCurrentContext(outputFboId, outputSize.width, outputSize.height)
            GlUtil.clearFocusedBuffers()
            if (currentTexture.width == 0 || currentTexture.height == 0) {
                renderCopy(inputTexId)
                return
            }
            copyProgram.use()
            copyProgram.setSamplerTexIdUniform("MAIN", currentTexture.texId, /* texUnitIndex= */ 0)
            copyProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()
        } catch (_: Exception) {
            // Ignore and let ExoPlayer handle video frame processing errors upstream.
        }
    }

    private fun renderCopy(inputTexId: Int) {
        try {
            copyProgram.use()
            copyProgram.setSamplerTexIdUniform("MAIN", inputTexId, /* texUnitIndex= */ 0)
            copyProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
            GlUtil.checkGlError()
        } catch (_: Exception) {
            // Ignore and let ExoPlayer handle video frame processing errors upstream.
        }
    }

    @Throws(VideoFrameProcessingException::class)
    override fun release() {
        val errors = ArrayList<Exception>()
        runCatching {
            releaseAllTextures()
        }.onFailure { errors.add(it as? Exception ?: RuntimeException(it)) }

        allPrograms.forEach { program ->
            runCatching { program.delete() }.onFailure { errors.add(it as? Exception ?: RuntimeException(it)) }
        }

        runCatching { super.release() }.onFailure { errors.add(it as? Exception ?: RuntimeException(it)) }

        errors.firstOrNull()?.let {
            throw VideoFrameProcessingException(it)
        }
    }

    private fun readCurrentFramebufferId(): Int {
        val framebuffer = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, framebuffer, 0)
        GlUtil.checkGlError()
        return framebuffer[0]
    }

    @Throws(VideoFrameProcessingException::class)
    private fun createHalfFloatTexture(
        width: Int,
        height: Int,
    ): GlTextureInfo {
        try {
            val texId = GlUtil.createTexture(width, height, /* useHighPrecisionColorComponents= */ true)
            val fboId = GlUtil.createFboForTexture(texId)
            return GlTextureInfo(texId, fboId, C.INDEX_UNSET, width, height)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private fun releaseAllTextures() {
        namedTextures.values.forEach { texture ->
            runCatching { texture.release() }
        }
        namedTextures.clear()

        pingPongTextures.values.forEach { set ->
            runCatching { set.first.release() }
            runCatching { set.second.release() }
        }
        pingPongTextures.clear()

        runCatching { mainSwapA.release() }
        runCatching { mainSwapB.release() }
        mainSwapA = GlTextureInfo.UNSET
        mainSwapB = GlTextureInfo.UNSET
    }

    private fun readAssetText(path: String): String =
        try {
            appContext.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw VideoFrameProcessingException(e)
        }

    private fun readGlInfo(): String? =
        runCatching {
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            val version = GLES20.glGetString(GLES20.GL_VERSION)
            "vendor=${vendor ?: "<null>"} renderer=${renderer ?: "<null>"} version=${version ?: "<null>"}"
        }.getOrNull()

    private fun parseMpvShaderPasses(source: String): List<ShaderPass> {
        var current: ShaderPassBuilder? = null
        val passes = mutableListOf<ShaderPass>()

        source.lineSequence().forEach { line ->
            if (line.startsWith("//!DESC")) {
                current?.build()?.let { passes.add(it) }
                current =
                    ShaderPassBuilder(
                        description = line.removePrefix("//!DESC").trim(),
                    )
                return@forEach
            }

            val builder = current ?: return@forEach
            if (line.startsWith("//!")) {
                val payload = line.removePrefix("//!").trim()
                val key = payload.substringBefore(" ").trim()
                val value = payload.substringAfter(" ", missingDelimiterValue = "").trim()
                when (key) {
                    "HOOK" -> builder.hookStage = value
                    "BIND" -> builder.binds.add(value)
                    "SAVE" -> builder.save = value
                    "WIDTH" -> builder.widthExpr = value
                    "HEIGHT" -> builder.heightExpr = value
                    "WHEN" -> builder.whenExpr = value
                    "COMPONENTS" -> builder.components = value.toIntOrNull()
                    else -> Unit
                }
            } else {
                builder.code.append(line).append('\n')
            }
        }

        current?.build()?.let { passes.add(it) }
        return passes
    }

    private class ShaderPassBuilder(
        private val description: String,
    ) {
        var hookStage: String? = null
        val binds = mutableListOf<String>()
        var save: String? = null
        var widthExpr: String? = null
        var heightExpr: String? = null
        var whenExpr: String? = null
        var components: Int? = null
        val code = StringBuilder()

        fun build(): ShaderPass =
            ShaderPass(
                description = description,
                hookStage = hookStage,
                binds = binds.toList(),
                save = save,
                widthExpr = widthExpr,
                heightExpr = heightExpr,
                whenExpr = whenExpr,
                components = components,
                code = code.toString(),
            )
    }

    private fun buildFragmentShader(
        pass: ShaderPass,
        samplers: List<String>
    ): String {
        val header =
            buildString {
                appendLine("#version 100")
                appendLine("precision highp float;")
                appendLine("precision highp int;")
                appendLine("varying vec2 vTexSamplingCoord;")
                samplers.forEach { name ->
                    appendLine("uniform sampler2D $name;")
                    appendLine("uniform vec2 ${name}_size;")
                    appendLine("uniform vec2 ${name}_pt;")
                    appendLine("#define ${name}_raw $name")
                    appendLine("#define ${name}_pos vTexSamplingCoord")
                    appendLine("#define ${name}_off vec2(0.0)")
                    appendLine("#define ${name}_mul 1.0")
                    appendLine("vec4 ${name}_tex(vec2 pos) { return ${name}_mul * texture2D(${name}_raw, pos); }")
                    appendLine(
                        "vec4 ${name}_texOff(vec2 off) { return ${name}_tex(${name}_pos + ${name}_pt * off); }",
                    )
                }
            }
        return buildString {
            append(header)
            appendLine()
            appendLine(pass.code.trimEnd())
            appendLine()
            appendLine("void main() {")
            appendLine("  gl_FragColor = hook();")
            appendLine("}")
        }
    }

    private fun buildConfiguredPasses(
        inputSize: Size,
        outputControlSize: Size,
    ): List<ConfiguredPass> {
        val configured = ArrayList<ConfiguredPass>(pipelinePrograms.size)
        val savedSizes = HashMap<String, Size>()
        var currentSize = inputSize

        pipelinePrograms.forEach { program ->
            val pass = program.pass
            val ctx =
                EvalContext(
                    mainSize = currentSize,
                    nativeSize = inputSize,
                    outputSize = outputControlSize,
                    savedSizes = savedSizes,
                )

            val active =
                pass.whenExpr?.takeIf { it.isNotBlank() }?.let { expr ->
                    val value = evalRpn(expr, ctx)
                    if (value == null) {
                        Media3Diagnostics.logAnime4kShaderDirectiveEvalFailed(
                            passDescription = pass.description,
                            directive = "WHEN",
                            expression = expr,
                            mainSize = ctx.mainSize,
                            nativeSize = ctx.nativeSize,
                            outputSize = ctx.outputSize,
                        )
                    }
                    value?.let { it != 0f } ?: false
                } ?: true

            val outputSize =
                if (!active) {
                    currentSize
                } else {
                    val outW =
                        pass.widthExpr?.takeIf { it.isNotBlank() }?.let { expr ->
                            val value = evalRpn(expr, ctx)
                            if (value == null) {
                                Media3Diagnostics.logAnime4kShaderDirectiveEvalFailed(
                                    passDescription = pass.description,
                                    directive = "WIDTH",
                                    expression = expr,
                                    mainSize = ctx.mainSize,
                                    nativeSize = ctx.nativeSize,
                                    outputSize = ctx.outputSize,
                                )
                            }
                            value?.roundToInt()?.coerceAtLeast(1)
                        } ?: currentSize.width
                    val outH =
                        pass.heightExpr?.takeIf { it.isNotBlank() }?.let { expr ->
                            val value = evalRpn(expr, ctx)
                            if (value == null) {
                                Media3Diagnostics.logAnime4kShaderDirectiveEvalFailed(
                                    passDescription = pass.description,
                                    directive = "HEIGHT",
                                    expression = expr,
                                    mainSize = ctx.mainSize,
                                    nativeSize = ctx.nativeSize,
                                    outputSize = ctx.outputSize,
                                )
                            }
                            value?.roundToInt()?.coerceAtLeast(1)
                        } ?: currentSize.height
                    Size(outW, outH)
                }

            if (active) {
                val saveName = pass.save?.takeIf { it.isNotBlank() }
                if (saveName != null && saveName != "MAIN") {
                    savedSizes[saveName] = outputSize
                } else if (pass.updatesMain()) {
                    currentSize = outputSize
                }
            }

            configured.add(
                ConfiguredPass(
                    program = program,
                    active = active,
                    outputSize = outputSize,
                ),
            )
        }
        return configured
    }

    private fun ensureNamedTexture(
        name: String,
        size: Size,
    ): GlTextureInfo {
        val existing = namedTextures[name]
        if (existing != null && existing.width == size.width && existing.height == size.height) {
            return existing
        }
        existing?.let { runCatching { it.release() } }
        val created = createHalfFloatTexture(size.width, size.height)
        namedTextures[name] = created
        return created
    }

    private fun ensurePingPongTextureSet(
        name: String,
        size: Size,
    ): PingPongTextureSet {
        val existing = pingPongTextures[name]
        if (
            existing != null &&
            existing.first.width == size.width &&
            existing.first.height == size.height &&
            existing.second.width == size.width &&
            existing.second.height == size.height
        ) {
            return existing
        }

        existing?.let {
            runCatching { it.first.release() }
            runCatching { it.second.release() }
        }

        val created =
            PingPongTextureSet(
                first = createHalfFloatTexture(size.width, size.height),
                second = createHalfFloatTexture(size.width, size.height),
            )
        pingPongTextures[name] = created
        return created
    }

    private fun ensureSwapTextureA(size: Size): GlTextureInfo {
        if (mainSwapA != GlTextureInfo.UNSET && mainSwapA.width == size.width && mainSwapA.height == size.height) {
            return mainSwapA
        }
        runCatching { mainSwapA.release() }
        mainSwapA = createHalfFloatTexture(size.width, size.height)
        return mainSwapA
    }

    private fun ensureSwapTextureB(size: Size): GlTextureInfo {
        if (mainSwapB != GlTextureInfo.UNSET && mainSwapB.width == size.width && mainSwapB.height == size.height) {
            return mainSwapB
        }
        runCatching { mainSwapB.release() }
        mainSwapB = createHalfFloatTexture(size.width, size.height)
        return mainSwapB
    }

    private fun evalRpn(
        expression: String,
        ctx: EvalContext,
    ): Float? {
        val stack = ArrayDeque<Float>()
        val tokens = expression.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        for (token in tokens) {
            when (token) {
                "+", "-", "*", "/", "%" -> {
                    if (stack.size < 2) return null
                    val rhs = stack.removeLast()
                    val lhs = stack.removeLast()
                    val result =
                        when (token) {
                            "+" -> lhs + rhs
                            "-" -> lhs - rhs
                            "*" -> lhs * rhs
                            "/" -> if (rhs == 0f) 0f else lhs / rhs
                            "%" -> if (rhs == 0f) 0f else lhs % rhs
                            else -> 0f
                        }
                    stack.addLast(result)
                }

                ">", "<", ">=", "<=", "==", "=" -> {
                    if (stack.size < 2) return null
                    val rhs = stack.removeLast()
                    val lhs = stack.removeLast()
                    val result =
                        when (token) {
                            ">" -> lhs > rhs
                            "<" -> lhs < rhs
                            ">=" -> lhs >= rhs
                            "<=" -> lhs <= rhs
                            "==", "=" -> {
                                val diff = abs(lhs - rhs)
                                val norm = max(abs(lhs), abs(rhs))
                                diff <= max(1e-6f, norm * 1e-6f)
                            }

                            else -> false
                        }
                    stack.addLast(if (result) 1f else 0f)
                }

                "!" -> {
                    if (stack.isEmpty()) return null
                    val value = stack.removeLast()
                    stack.addLast(if (value != 0f) 0f else 1f)
                }

                else -> {
                    val value =
                        token.toFloatOrNull()
                            ?: ctx.resolve(token)
                            ?: return null
                    stack.addLast(value)
                }
            }
        }
        return stack.lastOrNull()
    }

    private companion object {
        private val VERTEX_SHADER =
            """
            #version 100
            attribute vec4 aFramePosition;
            uniform mat4 uTransformationMatrix;
            uniform mat4 uTexTransformationMatrix;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = uTransformationMatrix * aFramePosition;
              vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5,
                                          aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
              vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
            }
            """.trimIndent()

        private val COPY_FRAGMENT_SHADER =
            """
            #version 100
            precision highp float;
            varying vec2 vTexSamplingCoord;
            uniform sampler2D MAIN;
            void main() {
              gl_FragColor = texture2D(MAIN, vTexSamplingCoord);
            }
            """.trimIndent()
    }
}

