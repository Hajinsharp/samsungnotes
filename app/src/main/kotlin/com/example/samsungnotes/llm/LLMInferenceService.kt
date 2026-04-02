package com.example.samsungnotes.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class LLMInferenceService(private val context: Context) {

    private var llmInference: LlmInference? = null
    private val modelFile = File(context.filesDir, MODEL_FILENAME)

    fun isModelLoaded(): Boolean = llmInference != null

    fun isModelDownloaded(): Boolean = modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE

    suspend fun copyModelFromDownloads(onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val source = File("/sdcard/Download/$MODEL_FILENAME")
            if (!source.exists()) {
                Timber.e("Model file not found in Downloads: $source")
                return@withContext false
            }
            try {
                val total = source.length()
                source.inputStream().use { input ->
                    modelFile.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024 * 1024)
                        var copied = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(((copied * 100) / total).toInt())
                        }
                    }
                }
                Timber.d("Model copied to internal storage")
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to copy model")
                modelFile.delete()
                false
            }
        }

    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (!isModelDownloaded()) {
            Timber.e("Model file not found at ${modelFile.absolutePath}")
            return@withContext false
        }
        return@withContext try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .setTopK(40)
                .setTemperature(0.7f)
                .setRandomSeed(42)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            Timber.d("Phi-3 Mini loaded successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load model")
            false
        }
    }

    suspend fun inference(noteContent: String): String = withContext(Dispatchers.Default) {
        val engine = llmInference
            ?: throw IllegalStateException("Model not loaded. Call loadModel() first.")

        val prompt = """<|user|>
You are a helpful assistant. Analyze the following note and provide:
1. A brief summary
2. Key points
3. Any action items or questions raised

Note:
$noteContent
<|end|>
<|assistant|>"""

        return@withContext try {
            Timber.d("Running Phi-3 inference...")
            engine.generateResponse(prompt)
        } catch (e: Exception) {
            Timber.e(e, "Inference failed")
            throw e
        }
    }

    fun unloadModel() {
        llmInference?.close()
        llmInference = null
        Timber.d("Model unloaded")
    }

    companion object {
        const val MODEL_FILENAME = "phi-3-mini-4k-instruct-int4.bin"
        private const val MIN_MODEL_SIZE = 1_000_000_000L // 1GB sanity check
    }
}
