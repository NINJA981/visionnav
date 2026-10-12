# YOLOv8n → LiteRT (TFLite) export

Produces the on-device detector shipped in `:perception`:

| File | Purpose |
|---|---|
| `perception/src/main/assets/models/yolov8n_320_fp16.tflite` | YOLOv8n COCO-80, 320×320, float16 weights, float32 I/O |
| `perception/src/main/assets/models/coco80.txt` | 80 class labels, one per line, YOLO class-index order |

## Source

- Weights: official Ultralytics `yolov8n.pt` (COCO-80), from `X:/Projects/yolo model/yolov8n.pt`
  - sha256 `f59b3d833e2ff32e194b5bb8e08d211dc7c5bdf144b90d2c8412c47ccfc83b36`
- The script copies it to `tools/model/yolov8n.pt` (gitignored).

## Reproduce (Windows, Git Bash; Python 3.12)

```bash
cd tools/model
python -m venv .venv                      # isolated; .venv/ is gitignored
./.venv/Scripts/python -m pip install --upgrade pip
./.venv/Scripts/python -m pip install -r requirements.txt
./.venv/Scripts/python -m pip install --no-deps "onnx2tf==1.28.8"
cd ../..
tools/model/.venv/Scripts/python tools/model/export_yolo.py [--weights PATH]   # ~4 min on CPU
tools/model/.venv/Scripts/python tools/model/export_yolo.py --verify-only       # re-check shipped model
```

Install the export dependencies **before** exporting. If Ultralytics auto-installs TensorFlow
while it is running, the install fails on Windows (a loaded `.pyd` file is locked) and leaves the
venv broken.

### Versions used for the shipped model

| Package | Version |
|---|---|
| Python | 3.12.10 |
| ultralytics | 8.4.146 |
| torch | 2.14.1+cpu |
| tensorflow | 2.19.0 (+ tf_keras 2.19.0) |
| onnx2tf | 1.28.8 |
| onnx | 1.22.0 |
| onnxslim | 0.1.98 |
| onnxruntime | 1.31.0 |
| numpy | 2.1.3 |

### Why `format="saved_model"` and not `format="tflite", half=True`

Ultralytics ≥ 8.4.83 redirects `format="tflite"` to a new `litert` exporter (litert-torch) that
does **not** support FP16 (`quantize=16` is rejected). The onnx2tf pipeline behind
`format="saved_model"` still writes `yolov8n_float16.tflite` (float16 weights, float32 input and
output), which is the same artifact the old `format="tflite", half=True` produced. The script calls
`model.export(format="saved_model", imgsz=320, nms=False)` and ships that `*_float16.tflite`.
Intermediate files (ONNX, SavedModel, onnx2tf calibration sample, logs) go to `tools/model/out/`
(gitignored).

## Model I/O (verified with the LiteRT interpreter)

| Tensor | Name | Shape | Type | Meaning |
|---|---|---|---|---|
| input | `images` | `[1, 320, 320, 3]` | float32 | NHWC RGB in [0, 1], letterboxed (pad value 114/255) |
| output | `Identity` | `[1, 84, 2100]` | float32 | rows 0–3: `cx, cy, w, h` in **input pixels** (0..320); rows 4–83: 80 class scores (already sigmoid); no NMS |

2100 anchors = 40² + 20² + 10² (strides 8/16/32 at 320 px).

**Box units are pixels, not normalised.** On `bus.jpg` the box rows span 2.84 to 317.14. Kotlin
uses `YoloOutputDecoder(boxUnits = BoxUnits.PIXELS)`. Ultralytics' own `YOLO("…tflite").predict()`
assumes normalised TFLite boxes, so with this file it collapses every box onto the image corner.
For that reason the script checks the model with its own decoder, which works like the Kotlin
one, and compares the result with the PyTorch model.

## Verification on Ultralytics' `bus.jpg` (810×1080, conf ≥ 0.25, class-wise NMS 0.45)

| | TFLite fp16 (this model) | PyTorch reference (imgsz 320) |
|---|---|---|
| person | 0.85 `[47, 394, 231, 901]` | 0.85 `[47, 394, 232, 904]` |
| bus | 0.84 `[5, 228, 798, 753]` | 0.82 `[6, 228, 800, 753]` |
| person | 0.75 `[220, 403, 348, 854]` | 0.75 `[220, 403, 348, 854]` |
| person | 0.69 `[670, 415, 810, 872]` | 0.64 `[670, 378, 810, 872]` |

The bus box IoU between TFLite and PyTorch is 0.996. The Kotlin `YoloOutputDecoder` was also run
once on the raw TFLite tensor for this image and gave the same four detections, to within one pixel.

## Shipped artifact

| | |
|---|---|
| File | `perception/src/main/assets/models/yolov8n_320_fp16.tflite` |
| Size | 6,387,890 bytes |
| sha256 | `5943cb1aaa879bd2522dc7ed409b886f29dbf7ab566ab1473b1aca6f02421951` |
| `coco80.txt` sha256 | `bd17f1ee35d5f3c862a4894605855abbb9dda4b0621fdb0ac4c2c8c7bb7e730a` |

TFLite conversion is not bit-reproducible across TensorFlow/onnx2tf versions. To reproduce the
checksum exactly, use the pinned versions above.

## Limitations

- These are COCO classes only. The model cannot detect stairs, doors, curbs or pedestrian signals
  (see PLAN §9).
- Accuracy at 320 px is lower than at 640 px. Small or distant objects are often missed.
- The model was not tested for accuracy on street imagery from the target devices.
