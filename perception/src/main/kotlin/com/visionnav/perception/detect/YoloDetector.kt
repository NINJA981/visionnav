package com.visionnav.perception.detect

import android.content.Context
import android.util.Log
import com.visionnav.core.vision.Detection
import com.visionnav.core.vision.DetectorCapabilities
import com.visionnav.perception.preprocess.PreprocessedFrame
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * YOLOv8n (COCO-80) running on LiteRT, fully on-device.
 *
 * Construction ([create]) memory-maps the model from assets, tries the GPU delegate when
 * [CompatibilityList] reports the device as supported, and otherwise (or if GPU init / warm-up
 * fails) uses the CPU with XNNPACK and [CPU_THREADS] threads. One warm-up inference runs before the
 * detector is returned so the first real frame is not penalised. The accelerator actually in use is
 * reported in [capabilities].
 *
 * Expected model (see `tools/model/README.md`): input `[1, 320, 320, 3]` float32 RGB in [0, 1]
 * (letterboxed, upright), output `[1, 84, 2100]` float32 with boxes in input pixels (cx, cy, w, h) and 80 sigmoid scores.
 *
 * Not thread-safe: call [detect] from one thread.
 */
class YoloDetector private constructor(
    private val interpreter: Interpreter,
    private val gpuDelegate: GpuDelegate?,
    private val decoder: YoloOutputDecoder,
    private val outputShape: IntArray,
    override val capabilities: DetectorCapabilities,
) : ObjectDetector {

    private val outputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(outputShape.fold(1) { a, b -> a * b } * 4).order(ByteOrder.nativeOrder())
    private val outputFloats: FloatBuffer = outputBuffer.asFloatBuffer()
    private val output = FloatArray(outputShape.fold(1) { a, b -> a * b })

    @Volatile
    private var closed = false

    override fun detect(frame: PreprocessedFrame): List<Detection> {
        check(!closed) { "detector closed" }
        require(frame.inputSize == capabilities.inputSize) {
            "frame input ${frame.inputSize} != model input ${capabilities.inputSize}"
        }
        frame.input.rewind()
        outputBuffer.rewind()
        interpreter.run(frame.input, outputBuffer)
        outputFloats.rewind()
        outputFloats.get(output)
        return decoder.decode(output, outputShape, frame.letterbox)
    }

    override fun close() {
        if (closed) return
        closed = true
        interpreter.close()
        gpuDelegate?.close()
    }

    companion object {
        private const val TAG = "YoloDetector"
        const val MODEL_ASSET = "models/yolov8n_320_fp16.tflite"
        const val LABELS_ASSET = "models/coco80.txt"
        const val MODEL_NAME = "yolov8n-coco80-320-fp16"
        const val CPU_THREADS = 4

        /** True when the model asset is packaged (cheap check, no loading). */
        fun isModelPackaged(context: Context): Boolean =
            runCatching { context.assets.openFd(MODEL_ASSET).close(); true }.getOrDefault(false)

        /**
         * Loads the model. Throws on failure (missing asset, unsupported ops, OOM…); callers decide
         * the fallback. Must not be called on the main thread (model load + warm-up take 100s of ms).
         */
        fun create(
            context: Context,
            decoder: YoloOutputDecoder = YoloOutputDecoder(boxUnits = BoxUnits.PIXELS),
            preferGpu: Boolean = true,
        ): YoloDetector {
            val model = mapAsset(context, MODEL_ASSET)
            val labels = context.assets.open(LABELS_ASSET).bufferedReader().use { CocoLabels.parse(it.readText()) }

            if (preferGpu) {
                val gpu = tryGpu(model)
                if (gpu != null) return build(gpu.first, gpu.second, "gpu", decoder, labels)
            }
            val options = Interpreter.Options().apply {
                setNumThreads(CPU_THREADS)
                setUseXNNPACK(true)
            }
            val interpreter = Interpreter(model, options)
            return try {
                warmUp(interpreter)
                build(interpreter, null, "cpu-xnnpack-x$CPU_THREADS", decoder, labels)
            } catch (t: Throwable) {
                interpreter.close()
                throw t
            }
        }

        private fun tryGpu(model: MappedByteBuffer): Pair<Interpreter, GpuDelegate>? {
            var delegate: GpuDelegate? = null
            var interpreter: Interpreter? = null
            return try {
                val compat = CompatibilityList()
                try {
                    if (!compat.isDelegateSupportedOnThisDevice) {
                        Log.i(TAG, "GPU delegate not supported on this device; using CPU")
                        return null
                    }
                    delegate = GpuDelegate(compat.bestOptionsForThisDevice)
                } finally {
                    compat.close()
                }
                interpreter = Interpreter(model, Interpreter.Options().addDelegate(delegate))
                warmUp(interpreter)
                interpreter to delegate
            } catch (t: Throwable) {
                Log.w(TAG, "GPU delegate failed (${t.message}); falling back to CPU", t)
                interpreter?.close()
                delegate?.close()
                null
            }
        }

        private fun build(
            interpreter: Interpreter,
            delegate: GpuDelegate?,
            accelerator: String,
            decoder: YoloOutputDecoder,
            labels: List<String>,
        ): YoloDetector {
            val inShape = interpreter.getInputTensor(0).shape()
            val outShape = interpreter.getOutputTensor(0).shape()
            require(inShape.size == 4 && inShape[1] == inShape[2] && inShape[3] == 3) {
                "unexpected input shape ${inShape.contentToString()}"
            }
            val layout = decoder.layoutOf(outShape)
            require(layout.numClasses == labels.size) {
                "model has ${layout.numClasses} classes but label file has ${labels.size}"
            }
            val caps = DetectorCapabilities(
                modelName = MODEL_NAME,
                supportedClasses = CocoLabels.supportedClasses(labels),
                isMock = false,
                inputSize = inShape[1],
                accelerator = accelerator,
            )
            Log.i(TAG, "loaded $MODEL_NAME in=${inShape.contentToString()} out=${outShape.contentToString()} on $accelerator")
            return YoloDetector(interpreter, delegate, decoder, outShape, caps)
        }

        private fun warmUp(interpreter: Interpreter) {
            val inShape = interpreter.getInputTensor(0).shape()
            val outShape = interpreter.getOutputTensor(0).shape()
            val input = ByteBuffer.allocateDirect(inShape.fold(1) { a, b -> a * b } * 4).order(ByteOrder.nativeOrder())
            val out = ByteBuffer.allocateDirect(outShape.fold(1) { a, b -> a * b } * 4).order(ByteOrder.nativeOrder())
            interpreter.run(input, out)
        }

        private fun mapAsset(context: Context, path: String): MappedByteBuffer =
            context.assets.openFd(path).use { fd ->
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
    }
}
