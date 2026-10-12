package com.visionnav.perception.detect

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.vision.Detection
import com.visionnav.core.vision.NormBox
import com.visionnav.core.vision.ObjectClass
import com.visionnav.perception.preprocess.LetterboxParams
import com.visionnav.perception.preprocess.PreprocessedFrame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class MockAndLabelsTest {

    private val frame = PreprocessedFrame(
        input = ByteBuffer.allocateDirect(4),
        inputSize = 320,
        letterbox = LetterboxParams.compute(480, 640, 320),
        luma = ByteArray(1), lumaWidth = 1, lumaHeight = 1,
        uprightWidth = 480, uprightHeight = 640, rotationDegrees = 90, timestampNs = 0,
    )

    private val d1 = Detection(ObjectClass.CAR, 0.9f, NormBox(0f, 0f, 0.1f, 0.1f))
    private val d2 = Detection(ObjectClass.PERSON, 0.8f, NormBox(0.5f, 0.5f, 0.6f, 0.9f))

    @Test fun mockReportsIsMockAndPlaysScriptInLoop() {
        val mock = MockYoloDetector(listOf(listOf(d1), listOf(d2), emptyList()))
        assertThat(mock.capabilities.isMock).isTrue()
        assertThat(mock.capabilities.accelerator).isEqualTo("none")
        val seq = (0 until 4).map { mock.detect(frame) }
        assertThat(seq).containsExactly(listOf(d1), listOf(d2), emptyList<Detection>(), listOf(d1)).inOrder()
    }

    @Test fun mockWithoutLoopEndsEmpty() {
        val mock = MockYoloDetector(listOf(listOf(d1)), loop = false)
        assertThat(mock.detect(frame)).containsExactly(d1)
        assertThat(mock.detect(frame)).isEmpty()
    }

    @Test fun emptyMockNeverFabricates() {
        val mock = MockYoloDetector()
        repeat(10) { assertThat(mock.detect(frame)).isEmpty() }
    }

    @Test fun closedMockThrows() {
        val mock = MockYoloDetector.demo()
        mock.close()
        assertThrows(IllegalStateException::class.java) { mock.detect(frame) }
    }

    @Test fun demoSequenceIsPlausible() {
        val mock = MockYoloDetector.demo()
        repeat(20) {
            mock.detect(frame).forEach { d ->
                assertThat(d.box.left).isAtLeast(0f)
                assertThat(d.box.right).isAtMost(1f)
                assertThat(d.box.width).isGreaterThan(0f)
            }
        }
    }

    @Test fun shippedLabelFileHasCoco80InYoloOrder() {
        val file = File("src/main/assets/models/coco80.txt")
        assertThat(file.exists()).isTrue()
        val labels = CocoLabels.parse(file.readText())
        assertThat(labels).hasSize(80)
        assertThat(labels[0]).isEqualTo("person")
        assertThat(labels[2]).isEqualTo("car")
        assertThat(labels[5]).isEqualTo("bus")
        assertThat(labels[9]).isEqualTo("traffic light")
        assertThat(labels[79]).isEqualTo("toothbrush")
        // Every app class except UNKNOWN maps to the same COCO label (modulo the app's short "table").
        ObjectClass.entries.filter { it.cocoId >= 0 }.forEach { c ->
            val expected = if (c == ObjectClass.DINING_TABLE) "dining table" else c.label
            assertThat(labels[c.cocoId]).isEqualTo(expected)
        }
        assertThat(CocoLabels.supportedClasses(labels)).isEqualTo(ObjectClass.entries.filter { it.cocoId >= 0 }.toSet())
    }

    @Test fun supportedClassesLimitedToLabelFile() {
        val few = listOf("person", "bicycle", "car")
        assertThat(CocoLabels.supportedClasses(few))
            .containsExactly(ObjectClass.PERSON, ObjectClass.BICYCLE, ObjectClass.CAR)
    }

    @Test fun shippedModelIsPresent() {
        val model = File("src/main/assets/models/yolov8n_320_fp16.tflite")
        assertThat(model.exists()).isTrue()
        assertThat(model.length()).isGreaterThan(1_000_000L)
    }
}
