# VisionNav — Flagship Build Plan

VisionNav is an on-device Android navigation assistant for blind and low-vision pedestrians.
It combines pedestrian routing, live camera perception, and a priority-aware voice/haptic
interface. The goal is to describe **what the phone can actually observe**, honestly and
concisely, without ever claiming that a road is safe to cross.

This plan supersedes the "V1 brief": the brief assumed Phases 1–6C already existed. They
don't. This repository is a from-scratch build, so every phase is built here, with the
Assisted Road-Crossing feature designed in from the start instead of bolted on.

---

## 0. Non-negotiable product rules

1. **No safety guarantees.** The app never produces "safe to cross", "cross now", "road is clear",
   "no vehicles are coming", "traffic has stopped". This is enforced in code by a
   `SafetyPhraseGuard` that every utterance passes through, and by unit tests.
2. **Absence of detection ≠ absence of hazard.** "No vehicle detected" is never spoken as
   reassurance. The engine reports coverage limits instead ("Traffic view limited to the front").
3. **Coordinate-frame honesty.** Image-space ("in the camera view") vs. world-space
   ("from your left") wording is chosen by the frame the estimate was computed in.
4. **Unknown is a first-class state.** Every estimator returns an explicit `Unknown(reason)`.
5. **On-device only.** Camera frames never leave the phone. Network is used only for maps.
6. **Critical audio always wins.** Safety warnings preempt everything; routine speech is
   short and rate-limited so the user can hear real traffic.
7. **Augments, never replaces**, the cane, guide dog, hearing, and O&M training. This is stated in
   onboarding and in the About screen.

---

## 1. Environment (verified on this machine)

| Item | Status | Decision |
|---|---|---|
| Android SDK | `X:\andriod`, platforms 34 & 36.1, build-tools 34–36 | compileSdk 36, targetSdk 35, minSdk 26 |
| JDK | Only JDK 26 installed (too new for AGP) | Gradle daemon toolchain = JDK 21, auto-provisioned; system JDK untouched |
| Gradle | 8.13 cached | Wrapper 8.13, AGP 8.13.2 |
| Python | 3.12 + ultralytics 8.4, `yolov8n.pt` available | Export YOLOv8n → TFLite (float16) at 320 px |
| Device | Physical Android phone available (not attached yet) | Real-device checklist in §12 |
| Language | English at launch | All strings in `strings.xml`, ready for localisation |
| Maps | Public OSM services | Valhalla (routing), Overpass (road tags), Photon (geocoding) behind interfaces |

---

## 2. Architecture

Multi-module, so that all safety-relevant logic is **pure Kotlin/JVM** and unit-testable
without Android, cameras, or vehicles.

```
:app          Android app: Compose UI, Hilt wiring, NavigationService (foreground), ViewModels
:core         Pure Kotlin. Domain models, geo math, tracking, motion, safety, crossing, announcements
:mapdata      Pure Kotlin + OkHttp. Valhalla / Overpass / Photon clients and parsers, caching
:perception   Android lib. CameraX session, LiteRT YOLO detector, frame-quality analysis, crosswalk CV
:platform     Android lib. Fused location, heading sensor fusion, TTS, audio focus, haptics, speech input
```

Dependency direction: `app → (perception, platform, mapdata) → core`. `core` depends on nothing
Android. Everything stateful exposes `StateFlow`/`Flow`, and every clock is injected (`TimeSource`)
for deterministic tests.

### Runtime data flow

```
CameraX (1 session, KEEP_ONLY_LATEST, throttled)
   └─▶ FrameQualityAnalyzer (luma, blur, glare, obstruction)  ─┐
   └─▶ YoloDetector (LiteRT, on-device) ─▶ DetectionFilter ─▶ ObjectTracker (stable IDs)
                                                                  └─▶ MotionEstimator (per track)
                                                                         ├─▶ SafetyRiskEngine ─▶ SafetyEvents
                                                                         └─▶ TrafficObserver (crossing mode)
Fused GPS ─▶ LocationProvider ─┐
Rotation-vector ─▶ HeadingTracker (device heading)  ─┐
                               ├─▶ RouteProgressTracker ─▶ NavigationEvents
                               ├─▶ CrossingContextProvider (Overpass + route maneuvers)
                               └─▶ CameraOrientationEstimator (camera bearing + reliability)
All events ─▶ AnnouncementPolicy (dedupe, cooldown, verbosity) ─▶ SafetyPhraseGuard ─▶ AudioQueueManager ─▶ TTS + earcons + haptics
```

---

## 3. Phases (all built in this repo)

### Phase 1 — Foundation
- Gradle version catalog, 5 modules, Hilt, Compose (Material 3), kotlinx.serialization, coroutines.
- Accessibility-first single-activity UI: TalkBack semantics on every control, large touch
  targets (≥ 72 dp), high-contrast theme, no information conveyed by colour alone.
- `NavigationService` foreground service (`location|camera` types). Camera runs **only** while a
  session that needs it is active, never in the background otherwise.
- Onboarding: spoken safety statement, permission rationale screens.

### Phase 2 — Voice & audio engine
- `AudioQueueManager`: priorities `CRITICAL > WARNING > NAVIGATION > OBSERVATION > INFO`.
  Higher priority preempts (stops) lower in-flight speech; same-or-lower waits; stale queued items
  expire (TTL per item) so outdated observations are never spoken late.
- `VoiceSpeaker` over Android `TextToSpeech` with audio focus (transient-may-duck).
- Earcons (short tones) and haptic patterns per event class, so critical alerts are recognisable
  without speech. Haptics also work with TTS muted.
- `SafetyPhraseGuard`: final gate; blocks/rewrites forbidden phrases, logged as a defect.
- Voice commands (Android `SpeechRecognizer`, on-device where supported): "navigate to …",
  "where am I", "what's around", "traffic", "repeat", "stop", "help".

### Phase 3 — Location, heading & geocoding
- `LocationProvider`: single fused-location subscription, accuracy-aware, exposes `Unavailable`.
- `HeadingTracker`: rotation-vector sensor → device azimuth, magnetic declination correction,
  accuracy status, calibration prompt. Separate `walkingHeading` from GPS course over ground
  (only when speed > 0.6 m/s and accuracy is good).
- `Geocoder`: Photon search biased to current location + radius ranking; reverse geocode for
  "where am I". Saved places (Home, Work, custom) in DataStore.

### Phase 4 — Pedestrian routing
- Valhalla `pedestrian` costing, decoded polyline6, maneuvers with street names.
- `RouteProgressTracker`: snap to route, distance-to-next-maneuver, off-route detection with
  hysteresis (distance + duration), rerouting with backoff, arrival detection with accuracy gate.
- Instructions in clock-face and left/right forms ("Turn right onto Anna Salai in 40 metres").
  Distances rounded honestly to GPS accuracy.

### Phase 5 — Vision detection & safety engine
- YOLOv8n COCO → LiteRT float16, 320×320, GPU delegate with CPU/XNNPACK fallback.
  **Real classes used:** person, bicycle, car, motorcycle, bus, truck, traffic light, stop sign,
  fire hydrant, bench, chair, dog, potted plant, … (full COCO-80 label file shipped with model).
- `DetectionFilter` (per-class confidence, min box area), NMS in Kotlin.
- `ObjectTracker`: IoU + centre-distance association (ByteTrack-lite: high/low confidence passes),
  stable IDs, track age, misses, history ring buffer; identity-switch guard.
- `MotionEstimator`: robust slope (Theil–Sen) of centre-x, box-height growth, consistency checks.
- `SafetyRiskEngine`: walking corridor (centre band) occupancy, relative proximity bands from box
  bottom-edge and height ratio (never metres), approaching-object escalation → `SafetyEvent`s.

### Phase 5B — Visibility assistance
- `FrameQualityAnalyzer`: mean luma, dark-pixel ratio, Laplacian-variance blur, glare ratio,
  obstruction (near-uniform frame). Low light → torch suggestion / auto-torch (setting); torch
  state owned by the single camera session.

### Phase 6A — Environment understanding
- "What's around": scene summary grouped by direction (clock face) and relative proximity, from
  current stable tracks only.

### Phase 6B — Obstacles & walking path
- Path corridor occupancy, "obstacle ahead, slightly left", step-around hint toward the freer side.

### Phase 6C — Crossings, entrances, stairs
- **Crosswalk (visual):** classical CV zebra-stripe detector on the lower image (periodic
  high-contrast bands, orientation, persistence over frames). Labelled *possible crosswalk*.
- **Mapped crossings / entrances / steps:** from OSM (`highway=crossing`, `crossing=*`,
  `entrance=*`, `highway=steps`) via Overpass near the route.
- **Stairs/doors visually:** not in COCO. Reported as *unsupported by current model*; a
  `CustomDetector` slot exists for a future trained model. No fake detection.

### Phase 7 — Assisted Road-Crossing Traffic Awareness (the brief, upgraded)
See §4.

### Phase 8 — Flagship extras
- **SOS:** "help" voice command / triple-press gesture → spoken confirmation → SMS to saved
  contacts with a map link; also offers a call. Never auto-sends without confirmation.
- **Verbosity profiles:** Minimal / Standard / Detailed; speech rate; haptics-only mode.
- **Battery & thermal governor:** adaptive inference rate from `PowerManager` thermal status
  and battery level; announces degraded mode.
- **Debug overlay & session log:** boxes, track IDs, motion labels, frame-quality metrics;
  text-only local log (no images) for field testing; export via share sheet.
- **Simulation/replay harness:** feed recorded GPS traces and synthetic track sequences through
  the real pipeline in tests and in a debug screen.

---

## 4. Assisted Road-Crossing (Phase 7) design

### 4.1 Road & crossing context (`core.crossing`)
```kotlin
data class RoadCrossingContext(
  val location: GeoPoint?,              // null when GPS unavailable — never fabricated
  val locationAccuracyM: Float?,
  val roads: List<RoadSegmentContext>,  // nearby candidate roads
  val crossings: List<CrossingCandidate>,
  val timestamp: Instant,
)
data class RoadSegmentContext(
  val osmWayId: Long?, val name: String?, val geometry: List<GeoPoint>,
  val directionality: RoadDirectionality,    // ONE_WAY / TWO_WAY / UNKNOWN
  val expectedFlows: List<ExpectedFlow>,     // bearings, 1 for one-way, 2 for two-way, 0 for unknown
  val lanes: Int?, val maxSpeedKph: Int?,
  val source: DataSource, val reliability: Reliability,
)
sealed interface CrossingCandidate { // never interchangeable
  MappedCrossing     // OSM highway=crossing node (+ crossing=traffic_signals/zebra/uncontrolled, island=yes)
  VisualCrosswalk    // zebra-stripe CV detector, persistence-gated
  PossibleCrossing   // route maneuver crosses a road way, no mapped crossing
  // "user at curb" is NOT a candidate; it is UserPositionAssessment.UNCONFIRMED unless user confirms
}
```
- `oneway=yes|1|true` → ONE_WAY along way order; `-1|reverse` → ONE_WAY reversed;
  `oneway=no` or `highway` in two-way defaults **only when tag explicitly `no`** → TWO_WAY;
  missing tag → `UNKNOWN` (exception: `junction=roundabout` implies one-way per OSM convention, marked `INFERRED`).
- Expected flow bearing = local segment bearing at the point nearest the crossing.
- `refuge`: OSM `crossing:island=yes` / `traffic_calming=island` → `RefugeInfo.MAPPED`;
  otherwise `UNKNOWN`. GPS alone never confirms the refuge is reached.

### 4.2 Image-space motion (`core.motion`)
- Per track: history of (t, cx, cy, w, h, conf), normalised to [0,1] image coordinates in the
  **upright display frame** (sensor rotation applied once in perception, with a unit test).
- Classify when: ≥ `minObservations` (6), span ≥ `minDurationMs` (600 ms), robust slope
  |dx/dt| ≥ `minSpeed` (0.08 width/s), net displacement ≥ `minDisplacement` (0.06 width),
  ≥ 75 % of step-wise moves agree in sign, no identity-switch flag.
- Result: `MOVING_RIGHT_IN_IMAGE | MOVING_LEFT_IN_IMAGE | STATIONARY_OR_LOW_MOTION | UNKNOWN(reason)`.
- **Camera ego-motion compensation:** gyro angular rate during the window converted to image
  shift (`Δx ≈ ω·Δt / HFOV`). If gyro rotation exceeds threshold → `UNKNOWN(CAMERA_MOVING)`.
  Optionally subtract median motion of static-class tracks.

### 4.3 Approach estimation
- Inputs: height-growth rate (log-scale slope), bottom-edge descent, lateral slope, consistency.
- `APPROACHING` only when **both** log-height growth and bottom-edge descent are consistently
  positive over the window, the camera is steady, and lateral motion is not dominant.
- `CROSSING` when lateral motion dominates and scale is stable. `MOVING_AWAY` on consistent shrink.
- Box growth alone → stays `UNKNOWN` (test 12). No metres, no time-to-collision.

### 4.4 World-direction (left/right) — gated
- `CameraOrientationEstimator`: camera bearing = device azimuth for a rear camera in portrait
  held upright (pitch within ±35° of vertical, roll small). Reliability = `RELIABLE` only when:
  heading sensor accuracy ≥ MEDIUM, phone near-upright, gyro steady, and magnetic field
  magnitude within normal Earth range (no interference).
- Image-left/right → world-left/right relative to the **camera's facing**, spoken only when
  reliable ("vehicle passing from left to right in front of you"). Otherwise image-space wording.
- Navigation/walking heading is a separate value and is **never** written from camera data.

### 4.5 Map/camera fusion (`core.crossing.FlowFusion`)
- Lateral image motion + camera bearing + HFOV → approximate world travel bearing (±45° bins).
- Compared to `ExpectedFlow` only when camera orientation `RELIABLE`, road `ONE_WAY` with
  `reliability ≥ MAPPED`, and the road segment lies within the camera FOV.
- `EXPECTED_FLOW_MATCH | EXPECTED_FLOW_MISMATCH | INSUFFICIENT_INFORMATION`. Mismatch never drops
  the observation; it continues to the safety engine. Wording "vehicle moving against the
  expected direction" only on reliable mismatch persisted across ≥ 2 tracks or ≥ 1.5 s.

### 4.6 Observation mode state machine (`core.crossing.CrossingAssistStateMachine`)
```
INACTIVE ─(context available near route)▶ APPROACH_CONTEXT_AVAILABLE ─(user says "traffic"/taps, OR
  stationary detected: speed<0.3 m/s ∧ accelerometer step-free ≥2 s)▶ USER_STOPPED_FOR_OBSERVATION
  ▶ OBSERVING_TRAFFIC ⇄ OBSERVATION_UNCERTAIN ▶ OBSERVATION_COMPLETE (timeout / user resumes walking)
  ▶ SEGMENT_TRANSITION (user says "next"/confirms refuge) ▶ OBSERVING_TRAFFIC (fresh, history cleared)
  any ─(user "stop" / moved away > 30 m / route ended)▶ EXITED ▶ INACTIVE
```
- Starting observation **reuses** the single camera session (asks `CameraSessionController` to
  raise analysis rate); it never opens a second one (test 17).
- Stationary detection uses step detector + GPS speed; if unavailable → stays in
  `APPROACH_CONTEXT_AVAILABLE` and requires explicit user action.
- Segment transition: clear tracker histories for the observation, refresh location/heading,
  restart from zero (test 19). `RefugeStatus.UNKNOWN` never auto-completes a segment (test 20).

### 4.7 Announcements
- `TrafficAnnouncementPolicy`: one utterance per track per state change, global cooldown
  (default 4 s for OBSERVATION), summary mode when > 3 moving vehicles
  ("Several vehicles moving in the camera view"), escalation (e.g., `CROSSING`→`APPROACHING`)
  bypasses cooldown. Uses `OBSERVATION` priority, so `CRITICAL` always preempts.
- Example vocabulary: "Possible crossing ahead. Stop and assess your surroundings.",
  "Mapped crossing with signals ahead.", "One-way road, traffic expected from your left."
  (only with reliable orientation), "Vehicle moving left to right in the camera view.",
  "Vehicle approaching.", "Vehicle moving away.", "Vehicle detected, direction unclear.",
  "Traffic view unclear. Low light.", "Road direction unavailable.", "Camera unavailable."
- Periodic coverage reminder in observation mode: "Camera sees ahead only."

---

## 5. Uncertainty & failure states
`UncertaintyReason`: GPS_UNAVAILABLE, GPS_LOW_ACCURACY, HEADING_UNAVAILABLE, HEADING_UNRELIABLE,
ROAD_DIRECTION_UNAVAILABLE, CAMERA_UNAVAILABLE, CAMERA_OBSTRUCTED, LOW_LIGHT, GLARE, BLUR,
DETECTOR_UNAVAILABLE, TRACK_TOO_SHORT, CAMERA_MOVING, MOTION_INCONSISTENT, IDENTITY_SWITCH,
CROSSING_AMBIGUOUS, MAP_DATA_UNAVAILABLE. Each maps to a short phrase and is spoken at most once
per cooldown window.

## 6. Performance & privacy
- One CameraX `ImageAnalysis` (YUV, `STRATEGY_KEEP_ONLY_LATEST`, 640×480 target), inference
  rate 5 fps walking / 10 fps observing / 2 fps thermal-throttled; preview only when debug
  overlay visible.
- LiteRT GPU delegate → XNNPACK CPU fallback; model warm-up at session start.
- Overpass queries cached by tile (~250 m) for 24 h; Valhalla/Photon calls debounced.
- No frame upload; no analytics SDK; session log text-only and user-exportable.

## 7. Testing (≥ the brief's 25 cases, plus flagship coverage)
- `:core` JVM tests: road classification (1–5), motion classification (6–12), fusion (13–16),
  state machine (17–20), announcements/priority/phrase guard (21–25), route progress, risk engine,
  tracker association, Theil–Sen, geo math.
- `:mapdata` tests with recorded JSON fixtures (Valhalla, Overpass, Photon) + MockWebServer.
- `:perception` JVM tests for NMS, YOLO output decoding, rotation mapping, crosswalk detector
  on synthetic luma buffers, frame-quality metrics.
- `:app` tests: AudioQueueManager preemption with fake speaker; ViewModel state.
- Gate: `./gradlew testDebugUnitTest assembleDebug lint` must pass.

## 8. Build order
1. Toolchain + skeleton modules + CI-style Gradle check (build passes empty).
2. `:core` domain + geo + tracking + motion + safety + crossing + announcements, with tests.
3. `:mapdata` clients + parsers + tests.
4. Model export (YOLOv8n → TFLite) + `:perception` detector, quality, crosswalk.
5. `:platform` location, heading, TTS, haptics, speech.
6. `:app` service, orchestration, Compose UI, settings, onboarding, SOS, debug overlay.
7. Full test suite, debug APK, diff review, report.

## 9. Explicitly out of scope / unsupported
- Stairs, doors, curb edges, pedestrian-signal (walk/don't-walk) recognition: COCO YOLOv8n does
  not detect these. Exposed as unsupported, not faked.
- Metric distance and time-to-collision: not computed.
- Wide-angle/ultra-wide lens: only used if CameraX reports it; default main rear camera.
- Autonomous crossing decisions: never.

## 10. Risks
| Risk | Mitigation |
|---|---|
| Public OSM endpoints rate-limit | Interfaces + caching + backoff; self-host later |
| Magnetometer interference near vehicles/metal | Reliability gating → image-space wording |
| GPU delegate crashes on some devices | Delegate probe + CPU fallback |
| Thermal throttling on long sessions | Adaptive inference rate governor |

## 11. Definition of done
All unit tests pass, debug APK builds, forbidden-phrase tests pass, real-device checklist
executed by a sighted tester before any blind user trial.

## 12. Real-device checklist (summary — expanded in `docs/DEVICE_TESTING.md`)
1. Install, grant permissions, TalkBack on: every control announced.
2. "Where am I" outdoors: plausible address; indoors: "location unavailable/low accuracy".
3. Route to a nearby place: maneuvers spoken, off-route triggers reroute.
4. Point camera at a parked car / passing car (sighted tester present): track IDs stable in
   debug overlay; motion labels match.
5. Rotate phone during observation → "Traffic view unclear, camera moving".
6. Cover lens → "Camera view obstructed". Night → low-light message / torch.
7. Critical alert (person walking into camera centre close) interrupts a traffic description.
8. Verify no utterance ever claims safety (session log grep).
