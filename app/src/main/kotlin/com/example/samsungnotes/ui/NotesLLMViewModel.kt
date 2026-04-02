package com.example.samsungnotes.ui

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.samsungnotes.accessibility.NotesAccessibilityService
import com.example.samsungnotes.llm.LLMInferenceService
import com.example.samsungnotes.model.ModelStatus
import com.example.samsungnotes.model.NoteCapture
import com.example.samsungnotes.model.UIState
import com.example.samsungnotes.ocr.OcrService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

class NotesLLMViewModel(context: Context) : ViewModel() {

    private val llmService = LLMInferenceService(context)
    private val ocrService = OcrService()

    private val _uiState = MutableStateFlow(UIState())
    val uiState: StateFlow<UIState> = _uiState.asStateFlow()

    init {
        collectNoteCaptures()
        initializeModel()
    }

    private fun initializeModel() {
        viewModelScope.launch {
            when {
                llmService.isModelDownloaded() -> loadModel()
                else -> _uiState.update { it.copy(modelStatus = ModelStatus.NOT_DOWNLOADED) }
            }
        }
    }

    fun copyModelFromDownloads() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            _uiState.update { it.copy(needsStoragePermission = true) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(modelStatus = ModelStatus.COPYING, isLoading = true) }
            val success = llmService.copyModelFromDownloads { progress ->
                _uiState.update { it.copy(processingProgress = progress) }
            }
            if (success) {
                loadModel()
            } else {
                _uiState.update {
                    it.copy(
                        modelStatus = ModelStatus.ERROR,
                        isLoading = false,
                        error = "Model file not found in Downloads. See instructions."
                    )
                }
            }
        }
    }

    private suspend fun loadModel() {
        _uiState.update { it.copy(modelStatus = ModelStatus.LOADING, isLoading = true) }
        try {
            val success = llmService.loadModel()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isModelLoaded = success,
                    modelStatus = if (success) ModelStatus.READY else ModelStatus.ERROR,
                    error = if (success) null else "Failed to load model"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error loading model")
            _uiState.update {
                it.copy(isLoading = false, modelStatus = ModelStatus.ERROR, error = e.message)
            }
        }
    }

    private fun collectNoteCaptures() {
        viewModelScope.launch {
            NotesAccessibilityService.noteFlow.collect { capture ->
                when (capture) {
                    is NoteCapture.TextCapture -> {
                        Timber.d("Text captured from accessibility tree")
                        _uiState.update { it.copy(noteContent = capture.text, ocrStatus = "") }
                    }
                    is NoteCapture.ImageCapture -> {
                        Timber.d("Handwritten note detected, running OCR...")
                        _uiState.update { it.copy(ocrStatus = "Reading handwriting...") }
                        try {
                            val text = ocrService.extractText(capture.bitmap)
                            _uiState.update {
                                it.copy(
                                    noteContent = text,
                                    ocrStatus = if (text.isBlank()) "No text found in image" else "Handwriting extracted"
                                )
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "OCR failed")
                            _uiState.update { it.copy(ocrStatus = "", error = "OCR failed: ${e.message}") }
                        }
                    }
                }
            }
        }
    }

    fun updateNoteContent(content: String) {
        _uiState.update { it.copy(noteContent = content) }
    }

    fun sendToLLM() {
        val currentState = _uiState.value
        if (currentState.noteContent.isBlank()) {
            _uiState.update { it.copy(error = "Please enter note content") }
            return
        }
        if (!llmService.isModelLoaded()) {
            _uiState.update { it.copy(error = "Model not loaded. Please wait...") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, llmResponse = "") }
            try {
                val response = llmService.inference(currentState.noteContent)
                _uiState.update { it.copy(llmResponse = response) }
            } catch (e: Exception) {
                Timber.e(e, "Error during inference")
                _uiState.update { it.copy(error = "Error: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun clearContent() {
        _uiState.update { it.copy(noteContent = "", llmResponse = "", ocrStatus = "") }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        llmService.unloadModel()
        ocrService.close()
    }
}
