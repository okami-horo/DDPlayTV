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
import com.xyoye.player.kernel.impl.media3.Media3Diagnostics
import java.io.IOException
import kotlin.math.max

@UnstableApi
class Anime4kPerformanceShaderProgram(
    context: Context,
) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents= */ false,
        /* texturePoolCapacity= */ 2,
    ) {
    private val appContext = context.applicationContext

    private data class ShaderPass(
        val description: String,
        val hook: String?,
        val binds: List<String>,
        val save: String?,
        val code: String,
    ) {
        fun requiredTextures(): List<String> {
            val required = LinkedHashSet<String>()
            hook?.takeIf { it.isNotBlank() }?.let { required.add(it) }
            binds.forEach { bind ->
                if (bind.isNotBlank()) {
                    required.add(bind)
                }
            }
            return required.toList()
        }
    }

    private data class PassProgram(
        val pass: ShaderPass,
        val glProgram: GlProgram,
    )

    private data class BoundTexture(
        val texId: Int,
        val width: Int,
        val height: Int,
    )

    private var configuredInputSize = Size.ZERO
    private var configuredOutputSize = Size.ZERO
    private var fallbackToCopy = false
    private var loggedFallbackToCopy = false

    private var restoredTexture = GlTextureInfo.UNSET
    private var conv2dTfTexture = GlTextureInfo.UNSET
    private var conv2d1Texture = GlTextureInfo.UNSET
    private var conv2d2Texture = GlTextureInfo.UNSET
    private var conv2dLastTexture = GlTextureInfo.UNSET

    private val restorePrograms: List<PassProgram>
    private val upscalePrograms: List<PassProgram>
    private val copyProgram: GlProgram
    private val allPrograms: List<GlProgram>

    init {
        val restoreSource = readAssetText(RESTORE_SHADER_PATH)
        val upscaleSource = readAssetText(UPSCALE_SHADER_PATH)

        val restorePasses = parseMpvShaderPasses(restoreSource)
        val upscalePasses = parseMpvShaderPasses(upscaleSource)
        Media3Diagnostics.logAnime4kShaderParsed(
            restorePassCount = restorePasses.size,
            upscalePassCount = upscalePasses.size,
        )

        restorePrograms = restorePasses.map { pass ->
            PassProgram(pass, GlProgram(VERTEX_SHADER, buildFragmentShader(pass)))
        }
        upscalePrograms = upscalePasses.map { pass ->
            PassProgram(pass, GlProgram(VERTEX_SHADER, buildFragmentShader(pass)))
        }
        copyProgram = GlProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER)
        allPrograms = buildList {
            restorePrograms.forEach { add(it.glProgram) }
            upscalePrograms.forEach { add(it.glProgram) }
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
        if (inputSize == configuredInputSize) {
            return configuredOutputSize
        }

        configuredInputSize = inputSize
        configuredOutputSize = Size(safeInputWidth * 2, safeInputHeight * 2)
        fallbackToCopy = false
        loggedFallbackToCopy = false

        releaseIntermediateTextures()
        try {
            restoredTexture = createHalfFloatTexture(safeInputWidth, safeInputHeight)
            conv2dTfTexture = createHalfFloatTexture(safeInputWidth, safeInputHeight)
            conv2d1Texture = createHalfFloatTexture(safeInputWidth, safeInputHeight)
            conv2d2Texture = createHalfFloatTexture(safeInputWidth, safeInputHeight)
            conv2dLastTexture = createHalfFloatTexture(safeInputWidth, safeInputHeight)

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
            )
        } catch (e: VideoFrameProcessingException) {
            fallbackToCopy = true
            configuredOutputSize = inputSize
            releaseIntermediateTextures()
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
            releaseIntermediateTextures()
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

        val inputTexture =
            BoundTexture(
                texId = inputTexId,
                width = inputSize.width,
                height = inputSize.height,
            )

        renderPasses(
            programs = restorePrograms,
            mainInput = inputTexture,
            finalTarget =
                Target.Texture(
                    texture = restoredTexture,
                ),
        )

        val restoredOutput =
            BoundTexture(
                texId = restoredTexture.texId,
                width = restoredTexture.width,
                height = restoredTexture.height,
            )

        renderPasses(
            programs = upscalePrograms,
            mainInput = restoredOutput,
            finalTarget =
                Target.Framebuffer(
                    fboId = outputFboId,
                    width = outputSize.width,
                    height = outputSize.height,
                ),
        )
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
            releaseIntermediateTextures()
        }.onFailure { errors.add(it as? Exception ?: RuntimeException(it)) }

        allPrograms.forEach { program ->
            runCatching { program.delete() }.onFailure { errors.add(it as? Exception ?: RuntimeException(it)) }
        }

        runCatching { super.release() }.onFailure { errors.add(it as? Exception ?: RuntimeException(it)) }

        errors.firstOrNull()?.let {
            throw VideoFrameProcessingException(it)
        }
    }

    private sealed class Target {
        data class Texture(
            val texture: GlTextureInfo,
        ) : Target()

        data class Framebuffer(
            val fboId: Int,
            val width: Int,
            val height: Int,
        ) : Target()
    }

    @Throws(VideoFrameProcessingException::class)
    private fun renderPasses(
        programs: List<PassProgram>,
        mainInput: BoundTexture,
        finalTarget: Target,
    ) {
        val available = HashMap<String, BoundTexture>()
        available["MAIN"] = mainInput
        available["HOOKED"] = mainInput

        for ((pass, program) in programs) {
            val saveTarget =
                when (pass.save) {
                    null -> finalTarget
                    "MAIN" -> finalTarget
                    "conv2d_tf" -> Target.Texture(conv2dTfTexture)
                    "conv2d_1_tf" -> Target.Texture(conv2d1Texture)
                    "conv2d_2_tf" -> Target.Texture(conv2d2Texture)
                    "conv2d_last_tf" -> Target.Texture(conv2dLastTexture)
                    else -> finalTarget
                }

            try {
                when (saveTarget) {
                    is Target.Texture -> {
                        GlUtil.focusFramebufferUsingCurrentContext(
                            saveTarget.texture.fboId,
                            saveTarget.texture.width,
                            saveTarget.texture.height,
                        )
                    }

                    is Target.Framebuffer -> {
                        GlUtil.focusFramebufferUsingCurrentContext(
                            saveTarget.fboId,
                            saveTarget.width,
                            saveTarget.height,
                        )
                    }
                }
                GlUtil.clearFocusedBuffers()

                program.use()
                val required = pass.requiredTextures()
                required.forEachIndexed { index, name ->
                    val bound = checkNotNull(available[name]) { "Anime4K missing bound texture: $name" }
                    runCatching { program.setSamplerTexIdUniform(name, bound.texId, index) }
                    program.setFloatsUniformIfPresent(
                        "${name}_size",
                        floatArrayOf(bound.width.toFloat(), bound.height.toFloat()),
                    )
                    program.setFloatsUniformIfPresent(
                        "${name}_pt",
                        floatArrayOf(1f / bound.width.toFloat(), 1f / bound.height.toFloat()),
                    )
                }
                program.bindAttributesAndUniforms()

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
                GlUtil.checkGlError()

                when (pass.save) {
                    "conv2d_tf" -> available["conv2d_tf"] = BoundTexture(conv2dTfTexture.texId, conv2dTfTexture.width, conv2dTfTexture.height)
                    "conv2d_1_tf" -> available["conv2d_1_tf"] = BoundTexture(conv2d1Texture.texId, conv2d1Texture.width, conv2d1Texture.height)
                    "conv2d_2_tf" -> available["conv2d_2_tf"] = BoundTexture(conv2d2Texture.texId, conv2d2Texture.width, conv2d2Texture.height)
                    "conv2d_last_tf" -> available["conv2d_last_tf"] = BoundTexture(conv2dLastTexture.texId, conv2dLastTexture.width, conv2dLastTexture.height)
                }
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
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

    private fun releaseIntermediateTextures() {
        listOf(
            restoredTexture,
            conv2dTfTexture,
            conv2d1Texture,
            conv2d2Texture,
            conv2dLastTexture,
        ).forEach { texture ->
            runCatching { texture.release() }
        }
        restoredTexture = GlTextureInfo.UNSET
        conv2dTfTexture = GlTextureInfo.UNSET
        conv2d1Texture = GlTextureInfo.UNSET
        conv2d2Texture = GlTextureInfo.UNSET
        conv2dLastTexture = GlTextureInfo.UNSET
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
                    "HOOK" -> builder.hook = value
                    "BIND" -> builder.binds.add(value)
                    "SAVE" -> builder.save = value
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
        var hook: String? = null
        val binds = mutableListOf<String>()
        var save: String? = null
        val code = StringBuilder()

        fun build(): ShaderPass =
            ShaderPass(
                description = description,
                hook = hook,
                binds = binds.toList(),
                save = save,
                code = code.toString(),
            )
    }

    private fun buildFragmentShader(pass: ShaderPass): String {
        val textures = pass.requiredTextures()
        val header =
            buildString {
                appendLine("#version 100")
                appendLine("precision highp float;")
                appendLine("precision highp int;")
                appendLine("varying vec2 vTexSamplingCoord;")
                textures.forEach { name ->
                    appendLine("uniform sampler2D $name;")
                    appendLine("uniform vec2 ${name}_size;")
                    appendLine("uniform vec2 ${name}_pt;")
                    appendLine("#define ${name}_pos vTexSamplingCoord")
                    appendLine("vec4 ${name}_tex(vec2 pos) { return texture2D($name, pos); }")
                    appendLine(
                        "vec4 ${name}_texOff(vec2 off) { return texture2D($name, ${name}_pos + off * ${name}_pt); }",
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

    private companion object {
        private const val RESTORE_SHADER_PATH = "shaders/Anime4K_Restore_CNN_S.glsl"
        private const val UPSCALE_SHADER_PATH = "shaders/Anime4K_Upscale_CNN_x2_S.glsl"

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
