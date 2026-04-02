package com.example.samsungnotes.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.samsungnotes.model.NoteCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class NotesAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (!packageName.contains("samsung", ignoreCase = true) &&
            !packageName.contains("notes", ignoreCase = true)) return

        Timber.d("Samsung Notes detected, extracting content...")
        val text = extractNotesContent()

        if (text.length >= MIN_TEXT_LENGTH) {
            serviceScope.launch { _noteFlow.emit(NoteCapture.TextCapture(text)) }
        } else {
            captureScreenshot()
        }
    }

    private fun captureScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Timber.w("Screenshot capture requires Android 11+")
            return
        }
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace
                    )?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    bitmap?.let {
                        serviceScope.launch { _noteFlow.emit(NoteCapture.ImageCapture(it)) }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Timber.e("Screenshot failed with error code: $errorCode")
                }
            }
        )
    }

    override fun onInterrupt() {
        Timber.d("Accessibility service interrupted")
    }

    fun extractNotesContent(): String {
        val rootNode = rootInActiveWindow ?: return ""
        return extractTextFromNode(rootNode)
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        node.text?.let { if (it.isNotEmpty()) sb.append(it).append("\n") }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { sb.append(extractTextFromNode(it)) }
        }
        return sb.toString().trim()
    }

    companion object {
        private const val TAG = "NotesAccessibilityService"
        private const val MIN_TEXT_LENGTH = 20

        private val _noteFlow = MutableSharedFlow<NoteCapture>(extraBufferCapacity = 1)
        val noteFlow: SharedFlow<NoteCapture> = _noteFlow
    }
}
