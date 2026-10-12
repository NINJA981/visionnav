"""Reproducible export of YOLOv8n (COCO-80) to a float16 LiteRT/TFLite model for VisionNav.

Usage (from the repo root, inside the isolated venv described in README.md):

    tools/model/.venv/Scripts/python tools/model/export_yolo.py [--weights PATH]

Steps:
  1. Copy the source weights into tools/model/yolov8n.pt (gitignored).
  2. Export with Ultralytics' onnx2tf pipeline: format="saved_model", imgsz=320, nms=False.
     onnx2tf writes yolov8n_float16.tflite (float16 weights, float32 input/output, raw
     [1, 84, 2100] head; NMS is done in Kotlin). This is exactly what the legacy
     format="tflite", half=True produced: Ultralytics >= 8.4.83 redirects format="tflite" to a
     new litert-torch exporter that rejects FP16 (quantize=16), so we call the onnx2tf path
     directly.
  3. Move intermediate artifacts (ONNX, SavedModel) into tools/model/out/ (gitignored).
  4. Copy the float16 .tflite into perception/src/main/assets/models/yolov8n_320_fp16.tflite
     and write the 80 class labels (YOLO order) into .../models/coco80.txt.
  5. Verify: raw interpreter input/output shapes + Ultralytics predict on the bundled bus.jpg.
  6. Print SHA-256 and size of the shipped model.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
ASSETS = REPO / "perception" / "src" / "main" / "assets" / "models"
OUT = HERE / "out"
DEFAULT_WEIGHTS = Path("X:/Projects/yolo model/yolov8n.pt")
IMGSZ = 320
MODEL_NAME = f"yolov8n_{IMGSZ}_fp16.tflite"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def export(weights_src: Path) -> Path:
    from ultralytics import YOLO

    weights = HERE / "yolov8n.pt"
    if weights_src.resolve() != weights.resolve():
        shutil.copy2(weights_src, weights)
    print(f"weights: {weights} sha256={sha256(weights)}")

    model = YOLO(str(weights))
    names = model.names  # dict {0: 'person', ...}
    assert len(names) == 80, f"expected COCO-80, got {len(names)} classes"

    OUT.mkdir(exist_ok=True)
    cwd = os.getcwd()
    os.chdir(OUT)  # onnx2tf downloads its calibration sample into the CWD; keep it out of the repo
    try:
        saved_model = Path(model.export(format="saved_model", imgsz=IMGSZ, nms=False))
    finally:
        os.chdir(cwd)
    print(f"ultralytics export returned: {saved_model}")
    exported = next(saved_model.glob("*_float16.tflite"))
    print(f"float16 tflite: {exported}")

    ASSETS.mkdir(parents=True, exist_ok=True)
    shipped = ASSETS / MODEL_NAME
    shutil.copy2(exported, shipped)
    (ASSETS / "coco80.txt").write_text(
        "\n".join(names[i] for i in range(len(names))) + "\n", encoding="utf-8", newline="\n"
    )

    # Keep intermediates out of the source tree.
    for name in ("yolov8n_saved_model", "yolov8n.onnx", "calibration_image_sample_data_20x128x128x3_float32.npy"):
        src = HERE / name
        if src.exists():
            dst = OUT / name
            if dst.exists():
                shutil.rmtree(dst) if dst.is_dir() else dst.unlink()
            shutil.move(str(src), str(dst))
    return shipped


def verify(model_path: Path) -> None:
    import numpy as np

    try:
        from ai_edge_litert.interpreter import Interpreter  # type: ignore
    except ImportError:
        import tensorflow as tf

        Interpreter = tf.lite.Interpreter

    interp = Interpreter(model_path=str(model_path))
    interp.allocate_tensors()
    inp = interp.get_input_details()[0]
    out = interp.get_output_details()[0]
    print(f"input : name={inp['name']} shape={list(inp['shape'])} dtype={inp['dtype'].__name__}")
    print(f"output: name={out['name']} shape={list(out['shape'])} dtype={out['dtype'].__name__}")
    interp.set_tensor(inp["index"], np.zeros(inp["shape"], dtype=inp["dtype"]))
    interp.invoke()
    print(f"raw zero-input output shape: {list(interp.get_tensor(out['index']).shape)}")

    import cv2
    from ultralytics import YOLO
    from ultralytics.utils import ASSETS as UL_ASSETS

    # Decode exactly like the Kotlin YoloOutputDecoder (letterbox -> raw head -> argmax -> conf ->
    # class-wise NMS -> letterbox undo). Ultralytics' own predict() is NOT used for the .tflite:
    # it assumes TFLite boxes are normalised, but this onnx2tf export emits input-pixel boxes
    # (checked below), which makes predict() collapse every box onto the image corner.
    img_path = UL_ASSETS / "bus.jpg"
    rgb = cv2.imread(str(img_path))[:, :, ::-1]
    h, w = rgb.shape[:2]
    s = min(IMGSZ / w, IMGSZ / h)
    nw, nh = round(w * s), round(h * s)
    px, py = (IMGSZ - nw) // 2, (IMGSZ - nh) // 2
    canvas = np.full((IMGSZ, IMGSZ, 3), 114, np.uint8)
    canvas[py : py + nh, px : px + nw] = cv2.resize(rgb, (nw, nh), interpolation=cv2.INTER_LINEAR)
    interp.set_tensor(inp["index"], canvas[None].astype(np.float32) / 255.0)
    interp.invoke()
    raw = interp.get_tensor(out["index"])[0]  # [84, 2100]
    print(f"box channel range: min={raw[:4].min():.2f} max={raw[:4].max():.2f} (>> 1 => input-pixel units)")
    if raw[:4].max() <= 2.0:
        sys.exit("VERIFICATION FAILED: boxes look normalised; Kotlin YoloDetector expects BoxUnits.PIXELS")

    scores = raw[4:]
    cls = scores.argmax(0)
    conf = scores.max(0)
    keep = conf >= 0.25
    cx, cy, bw, bh = raw[0, keep], raw[1, keep], raw[2, keep], raw[3, keep]
    x1 = ((cx - bw / 2 - px) / s).clip(0, w)
    y1 = ((cy - bh / 2 - py) / s).clip(0, h)
    x2 = ((cx + bw / 2 - px) / s).clip(0, w)
    y2 = ((cy + bh / 2 - py) / s).clip(0, h)
    boxes = np.stack([x1, y1, x2 - x1, y2 - y1], 1).tolist()
    idx = cv2.dnn.NMSBoxesBatched(boxes, conf[keep].tolist(), cls[keep].tolist(), 0.25, 0.45)
    names = YOLO(str(HERE / "yolov8n.pt")).names
    tfl = []
    for k in np.array(idx).flatten():
        b = boxes[k]
        tfl.append((names[int(cls[keep][k])], float(conf[keep][k]), [b[0], b[1], b[0] + b[2], b[1] + b[3]]))
    tfl.sort(key=lambda t: -t[1])
    print(f"tflite on {img_path.name} ({w}x{h}), conf>=0.25, NMS 0.45: {len(tfl)} detections")
    for n, c, b in tfl:
        print(f"  {n:<12} conf={c:.2f} xyxy={[round(v) for v in b]}")

    ref = YOLO(str(HERE / "yolov8n.pt")).predict(str(img_path), imgsz=IMGSZ, conf=0.25, verbose=False)[0]
    print(f"pytorch reference (imgsz={IMGSZ}): {len(ref.boxes)} detections")
    for c, cf, b in zip(ref.boxes.cls.tolist(), ref.boxes.conf.tolist(), ref.boxes.xyxy.tolist()):
        print(f"  {names[int(c)]:<12} conf={cf:.2f} xyxy={[round(v) for v in b]}")

    labels = {n for n, _, _ in tfl}
    if not {"bus", "person"} <= labels:
        sys.exit(f"VERIFICATION FAILED: expected bus and person, got {sorted(labels)}")
    bus_t = next(b for n, _, b in tfl if n == "bus")
    bus_r = next(b for c, b in zip(ref.boxes.cls.tolist(), ref.boxes.xyxy.tolist()) if names[int(c)] == "bus")
    ix = max(0, min(bus_t[2], bus_r[2]) - max(bus_t[0], bus_r[0])) * max(0, min(bus_t[3], bus_r[3]) - max(bus_t[1], bus_r[1]))

    def area(b):
        return (b[2] - b[0]) * (b[3] - b[1])

    iou = ix / (area(bus_t) + area(bus_r) - ix)
    print(f"bus IoU tflite vs pytorch: {iou:.3f}")
    if iou < 0.9:
        sys.exit("VERIFICATION FAILED: tflite bus box disagrees with the PyTorch reference")
    print("verification OK: bus and person detected; boxes match the PyTorch reference")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", type=Path, default=DEFAULT_WEIGHTS)
    ap.add_argument("--verify-only", action="store_true")
    args = ap.parse_args()

    shipped = ASSETS / MODEL_NAME if args.verify_only else export(args.weights)
    verify(shipped)
    print(f"shipped: {shipped.relative_to(REPO)} size={shipped.stat().st_size} sha256={sha256(shipped)}")


if __name__ == "__main__":
    main()
