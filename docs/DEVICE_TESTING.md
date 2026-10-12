# VisionNav — Real-Device Test Checklist

Unit tests prove the logic. They do **not** prove the app works on a phone, in traffic, for a
blind user. Run this checklist on a physical Android phone (API 26+) before any trial with a
blind or low-vision participant.

**Rule:** a sighted tester is present for every outdoor step. Never test traffic features by
walking into a road. Observe traffic from the pavement.

## Setup
1. Build and install: `./gradlew :app:installDebug` (USB debugging on; `adb devices` lists the phone).
2. Turn on TalkBack. Grant permissions during onboarding.
3. Settings → enable **Debug overlay** for steps 6–12 (camera preview, boxes, track IDs, motion labels).
4. Settings → confirm the detector line reads `yolov8n_320_fp16 (GPU|CPU)`, **not** "Demo detector".

## Indoors
| # | Step | Expected |
|---|---|---|
| 1 | Navigate every screen with TalkBack swipe gestures only | Every control is announced with a name and role; no unlabeled buttons |
| 2 | System font size → largest | No clipped text; screens scroll |
| 3 | Cover the camera lens with a finger during "What's around" | "Camera view blocked." |
| 4 | Turn off the room light | "Traffic view unclear. Low light." / torch suggestion; torch toggles |
| 5 | "Where am I" indoors | Low-accuracy or "Location unavailable" — never a made-up address |

## Outdoors, on the pavement
| # | Step | Expected |
|---|---|---|
| 6 | "Where am I" | Plausible nearby address; accuracy phrase |
| 7 | Navigate to a place ~300 m away | Route spoken; turn prompts before each turn; arrival message |
| 8 | Deliberately walk off route ~40 m | "You may be off route. Recalculating." once (no repeats) |
| 9 | Point camera at a **parked** car for 5 s | Track ID stable in overlay; motion = stationary/low; no "approaching" |
| 10 | Point camera at road traffic (phone upright, still) | Lateral labels match reality in the overlay; spoken "moving left to right in the camera view" |
| 11 | Same, but slowly rotate the phone | "Traffic view unclear. Camera moving." — no direction claims |
| 12 | Hold phone flat (face up) | Only image-space wording; never "from your left/right" |
| 13 | Walk toward a person/pole in path | Warning, escalating to critical; critical interrupts any traffic description |
| 14 | Traffic check near a mapped signalised crossing | "Mapped crossing with traffic signals ahead." (if OSM has it) |
| 15 | Traffic check at an unmapped road | "Possible crossing ahead…" or "Road direction unavailable." |
| 16 | Say "next segment" at a refuge island | Fresh observation; previous vehicles not re-announced |
| 17 | Long session (20 min) | Phone warm → reduced-performance message, no crash, speech still timely |

## Log review (after each session)
- Debug → Export log. Search the log for `BLOCKED` (SafetyPhraseGuard defects). Expected: zero.
- Grep the log for `safe`, `clear`, `cross now`. Expected: zero spoken matches.
- Check that no utterance was spoken more than once within its cooldown.
