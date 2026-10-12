# VisionNav

On-device Android navigation assistant for blind and low-vision pedestrians: walking routes,
live camera hazard detection, and **Assisted Road-Crossing Traffic Awareness**, delivered through a
priority-aware voice, earcon and haptic interface designed TalkBack-first.

> VisionNav describes what the phone can observe. It never says a road is safe or clear, and it
> never tells you when to cross. It does not replace a cane, guide dog, hearing, or orientation
> and mobility training.

## Build

Requirements: Android SDK (`local.properties` → `sdk.dir`), internet for the first build.
No JDK setup needed: Gradle runs its daemon on JDK 21 automatically (`gradle/gradle-daemon-jvm.properties`),
whatever `JAVA_HOME` points to.

```bash
./gradlew test                 # all unit tests (JVM, no device)
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug    # install on a USB-connected phone
./gradlew lintDebug            # Android lint
```

Model export (only needed to regenerate the shipped model): see [tools/model/README.md](tools/model/README.md).

## Modules

| Module | Kind | Contents |
|---|---|---|
| `:core` | Kotlin/JVM | Domain contracts, geo math, tracker, motion, safety risk engine, scene/path, crossing context + state machine + fusion + reporter, audio priority queue, route progress, phrasing, voice-command parser |
| `:mapdata` | Kotlin/JVM | Valhalla (routing), Overpass (road tags, crossings), Photon (geocoding); caching, rate limits, backoff |
| `:perception` | Android lib | Single CameraX session, LiteRT YOLOv8n (GPU → CPU fallback), frame quality, crosswalk CV |
| `:platform` | Android lib | Fused location, camera-orientation sensors, step activity, TTS, earcons, haptics, speech input, SOS, power governor |
| `:app` | Android app | Compose UI, `VisionNavEngine` orchestrator, foreground service, settings, debug overlay |

Design and safety rules: [docs/PLAN.md](docs/PLAN.md). Field test procedure: [docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md).

## Status

**Implemented and unit-tested** (526 tests, JVM, no device):
image-space motion (left/right in the camera view), approach estimation requiring both scale growth
and bottom-edge descent, ego-motion gating, tracking with identity-switch guard, safety severity with
hysteresis, road one-way/two-way/unknown from OSM tags, mapped vs visual vs possible crossings,
orientation-gated world wording, map/camera flow comparison, crossing state machine and multi-stage
segments, audio priority/preemption/dedupe/deferral, forbidden-phrase guard, route progress/off-route/
arrival, Valhalla/Overpass/Photon parsing, YOLO output decoding and NMS, frame quality, crosswalk
heuristic, end-to-end pipeline integration tests.

**Implemented, not yet verified on a physical phone:**
CameraX binding from the foreground service, LiteRT GPU delegate and real inference latency, YUV
conversion on real frames, camera FOV from Camera2, rotation-vector heading accuracy, step detection,
TTS/earcon/haptic feel, on-device speech recognition, SMS delivery, thermal throttling, real street
detection quality at 320 px. Run [docs/DEVICE_TESTING.md](docs/DEVICE_TESTING.md).

**Interface only:** custom detector slot for classes YOLOv8n lacks; self-hosted map endpoints (config only).

**Not supported by the current model/hardware (reported, never faked):** stairs, doors, curbs,
pedestrian walk/don't-walk signals, metric distance, time-to-collision, full road coverage beyond the
rear camera's field of view, reliable refuge-island detection from GPS.

## Data & privacy

Camera frames are processed on the device and never stored or uploaded. Network is used only for map
data (OpenStreetMap services). The session log is text-only, in memory, and exported only by the user.

Map data © OpenStreetMap contributors. Routing: Valhalla. Geocoding: Photon (Komoot).
Detection model: Ultralytics YOLOv8n (AGPL-3.0).
