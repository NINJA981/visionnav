package com.visionnav.perception.detect

import com.visionnav.core.vision.Detection
import com.visionnav.core.vision.DetectorCapabilities
import com.visionnav.core.vision.ObjectClass
import com.visionnav.perception.preprocess.PreprocessedFrame
import java.io.Closeable

/**
 * On-device object detector over preprocessed (upright, letterboxed) frames.
 *
 * Implementations are **not** required to be thread-safe; the camera session calls [detect] from
 * a single inference thread. [detect] may throw on runtime failure; callers must treat that as
 * "no information for this frame", never as "nothing there".
 */
interface ObjectDetector : Closeable {
    /** What this detector can actually do. Stable for the detector's lifetime. */
    val capabilities: DetectorCapabilities

    /** Detections in normalised upright image coordinates, highest confidence first. */
    fun detect(frame: PreprocessedFrame): List<Detection>

    override fun close()
}

/** Shipped label file helpers. */
object CocoLabels {
    /** Parses a label file (one label per line, YOLO class-index order). Blank lines are ignored at the end only. */
    fun parse(text: String): List<String> = text.lines().map { it.trim() }.dropLastWhile { it.isEmpty() }

    /**
     * App classes the model can emit: those whose COCO id is a valid index into [labels].
     * (Label text is not compared: the app uses some shorter names, e.g. "table" for "dining table".)
     */
    fun supportedClasses(labels: List<String>): Set<ObjectClass> =
        ObjectClass.entries.filter { it.cocoId in labels.indices }.toSet()
}
