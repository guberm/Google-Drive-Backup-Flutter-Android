package dev.guber.gdrivebackup

import io.flutter.plugin.common.EventChannel

object BackupEventBus {
    @Volatile var sink: EventChannel.EventSink? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastScanEvent: Map<String, Any?>? = null
    private var lastProgressEvent: Map<String, Any?>? = null

    @Synchronized fun attach(events: EventChannel.EventSink?) {
        sink = events
        if (events != null) {
            lastScanEvent?.let { e -> mainHandler.post { try { events.success(e) } catch (_: Exception) {} } }
            lastProgressEvent?.let { e -> mainHandler.post { try { events.success(e) } catch (_: Exception) {} } }
        }
    }
    fun emit(event: Map<String, Any?>) {
        val t = event["type"] as? String
        if (t == "scan_complete") {
            lastScanEvent = event
        } else if (t == "native_progress") {
            lastProgressEvent = event
        }
        val targetSink = sink
        if (targetSink != null) {
            mainHandler.post { try { targetSink.success(event) } catch (_: Exception) {} }
        } else {
            // No sink yet; events will be buffered if scan/progress. Other types are transient.
        }
    }
}