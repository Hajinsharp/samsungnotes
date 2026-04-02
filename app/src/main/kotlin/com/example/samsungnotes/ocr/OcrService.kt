package com.example.samsungnotes.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrService {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                Timber.d("OCR extracted ${result.text.length} characters")
                cont.resume(result.text)
            }
            .addOnFailureListener { e ->
                Timber.e(e, "ML Kit OCR failed")
                cont.resumeWithException(e)
            }
    }

    fun close() {
        recognizer.close()
    }
}
