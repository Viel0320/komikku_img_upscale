package eu.kanade.tachiyomi.util.qnn

import android.content.Context
import android.os.Build
import android.util.Log
import eu.kanade.tachiyomi.util.npu.NpuModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Compiles bundled FP16 or W8A8 DLCs once per model/runtime/device, outside the image cache. */
object QnnModelContexts {
    // KMK -->
    // 22 tracks the upstream identity format that mixes the precision variant into
    // the context hash; older FP16 caches recompile once and are then reclaimed.
    const val CACHE_VERSION = "22"
    const val PRECISION_W8A8 = 2
    // KMK <--
    private const val RUNTIME_VERSION = "2.49.0"
    private const val TAG = "QnnModelContexts"

    private val mutableStates = MutableStateFlow<Map<String, NpuModelStatus>>(emptyMap())
    val states = mutableStates.asStateFlow()

    // KMK -->
    // Runtime FP16 capability; null while undetermined so inconclusive probes
    // are retried instead of permanently disabling the FP16 option.
    private val mutableFp16Supported = MutableStateFlow<Boolean?>(null)
    val fp16Supported = mutableFp16Supported.asStateFlow()
    // KMK <--

    init {
        runCatching { System.loadLibrary("waifu2x-jni") }
    }

    // KMK -->
    /**
     * Probes the actual HTP backend with a minimal FP16 operation instead of
     * trusting the architecture allowlist, so integer-only SoCs are detected
     * before a context compile fails with an opaque validation error.
     * Blocking: call on an IO worker. Unknown runtime errors stay uncached.
     */
    @Synchronized
    fun detectFp16Support(context: Context): Boolean? {
        mutableFp16Supported.value?.let { return it }
        val result = runCatching { nativeFp16Support(context.applicationInfo.nativeLibraryDir) }.getOrDefault(-1)
        val supported = when (result) {
            1 -> true
            0 -> false
            else -> null
        }
        if (supported != null) mutableFp16Supported.value = supported
        return supported
    }

    /**
     * The W8A8 asset is prequantized on a developer machine with 8-bit activations,
     * which is only numerically safe for this model; everything else stays FP16.
     */
    fun supportsW8a8(modelName: String?): Boolean = modelName == "realesrgan-animevideov3-x2"

    fun supportedPrecisions(modelName: String?): List<Int> =
        if (supportsW8a8(modelName)) listOf(0, PRECISION_W8A8) else listOf(0)

    fun stateKey(modelName: String, precision: Int): String =
        if (precision == PRECISION_W8A8) "$modelName:w8a8" else modelName

    internal fun assetName(modelName: String, precision: Int): String {
        require(precision in supportedPrecisions(modelName)) { "INT8 W8A8 is unavailable for $modelName" }
        return if (precision == PRECISION_W8A8) "$modelName-w8a8" else modelName
    }
    // KMK <--

    internal fun contextFile(context: Context, modelName: String, precision: Int = 0): File {
        val manifest =
            JSONObject(context.assets.open("qnn-models/manifest.json").bufferedReader().use { it.readText() })
        // KMK -->
        val asset = assetName(modelName, precision)
        require(manifest.has(asset) && modelName.matches(Regex("[a-z0-9-]+"))) { "Unknown QNN model: $asset" }
        val modelHash = manifest.getString(asset)
        // KMK <--
        // KMK --> INT8 preparation does not depend on FP16 support.
        val architecture = QualcommHtp.architecture(context) ?: error("No supported HTP runtime")
        // KMK <--
        // A driver/OS update can invalidate compiled binaries even on the same HTP architecture.
        val identity = listOf(
            CACHE_VERSION,
            RUNTIME_VERSION,
            modelHash,
            architecture.toString(),
            Build.FINGERPRINT,
            Build.SUPPORTED_ABIS.first(),
            // KMK -->
            if (precision == PRECISION_W8A8) "w8a8" else "fp16",
            // KMK <--
        ).joinToString("|")
        val key = sha256(identity.toByteArray()).take(32)
        // KMK --> Integer and float contexts live in sibling directories.
        val variant = if (precision == PRECISION_W8A8) "w8a8/" else ""
        // KMK <--
        return File(context.noBackupFilesDir, "qnn-contexts/$modelName/$variant$key/context.bin")
    }

    /** Blocking: call on an IO worker. Serialization also prevents duplicate preparations from the UI and decoder. */
    @Synchronized
    fun prepare(context: Context, modelName: String, retry: Boolean = false, precision: Int = 0): File? {
        // KMK -->
        val stateKey = stateKey(modelName, precision)
        // KMK <--
        val previous = mutableStates.value[stateKey]
        if (previous is NpuModelStatus.Failed && !retry) return null
        return runCatching {
            // KMK -->
            // INT8 skips the FP16 requirement so integer-only runtimes still have
            // a working NPU path; float compilation fails here with a clear message
            // instead of an opaque QNN validation error.
            val asset = assetName(modelName, precision)
            check(precision == PRECISION_W8A8 || detectFp16Support(context) != false) {
                "FP16 is unavailable on this device; choose an INT8-capable model or Vulkan"
            }
            val binary = contextFile(context, modelName, precision)
            // KMK <--
            val directory = requireNotNull(binary.parentFile)
            check(directory.isDirectory || directory.mkdirs()) { "Unable to create NPU model directory" }
            val checksum = File(directory, "context.sha256")
            if (binary.length() > 0 && checksum.isFile && checksum.readText() == sha256(binary)) {
                setState(stateKey, NpuModelStatus.Ready(binary))
                Log.d(TAG, "Reusing persisted context: $asset")
                return@runCatching binary
            }
            setState(stateKey, NpuModelStatus.Preparing)
            // An uncommitted/modified binary must never be mistaken for a prepared context.
            check(!binary.exists() || binary.delete()) { "Unable to replace invalid NPU context" }
            checksum.delete()
            val dlc = File(directory, "model.dlc")
            val temporary = File(directory, "model.dlc.partial")
            try {
                context.assets.open("qnn-models/$asset.dlc").use { input ->
                    temporary.outputStream().use(input::copyTo)
                }
                check(temporary.renameTo(dlc)) { "Unable to extract NPU model" }
                val started = android.os.SystemClock.elapsedRealtime()
                val error =
                    nativeCompile(dlc.absolutePath, binary.absolutePath, context.applicationInfo.nativeLibraryDir)
                check(error.isEmpty()) { error }
                check(binary.length() > 0) { "NPU compilation produced an empty context" }
                val pendingChecksum = File(directory, "context.sha256.partial")
                pendingChecksum.writeText(sha256(binary))
                check(pendingChecksum.renameTo(checksum)) { "Unable to commit NPU context checksum" }
                Log.i(
                    TAG,
                    "Compiled $asset on device in ${android.os.SystemClock.elapsedRealtime() - started} ms",
                )
            } finally {
                temporary.delete()
                dlc.delete()
            }
            setState(stateKey, NpuModelStatus.Ready(binary))
            // Reclaim obsolete versions only after a replacement has been successfully prepared.
            // KMK --> Hash-named check keeps the sibling w8a8/ variant directory alive.
            directory.parentFile?.listFiles()?.filter {
                it.isDirectory && it != directory && it.name.matches(Regex("[a-f0-9]{32}"))
            }?.forEach { it.deleteRecursively() }
            // KMK <--
            binary
        }.onFailure { error ->
            Log.w(TAG, "Unable to prepare $modelName; enhancement skipped", error)
            setState(stateKey, NpuModelStatus.Failed(error.message.orEmpty()))
        }.getOrNull()
    }

    private fun setState(modelName: String, status: NpuModelStatus) {
        mutableStates.update { it + (modelName to status) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private external fun nativeCompile(dlcPath: String, contextPath: String, nativeLibraryDir: String): String

    // KMK -->
    private external fun nativeFp16Support(nativeLibraryDir: String): Int
    // KMK <--
}
