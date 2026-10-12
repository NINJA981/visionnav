# Kerb — VisionNav design system

VisionNav's interface borrows the street's own marking language. Every motif has a job on a real
street, and it does the same job here. If an element can't justify itself that way, cut it.

## Tokens (`ui/theme`)

| Token | Night (default) | Day | Use |
|---|---|---|---|
| `ground` | Asphalt `#26282B` | Paint `#F2F1EC` | Screen background |
| `onGround` / `onGroundDim` | Paint / `#C3C2BC` | Asphalt / `#3A3D42` | Text on ground |
| `bar` / `onBar` | Paint / Asphalt | Asphalt / Paint | Zebra bars: everyday actions |
| `kerb` / `onKerb` | `#3A3D42` / Paint | `#DEDCD4` / Asphalt | Kerbstones: quiet rows, panels |
| `amber` / `onAmber` | `#FFB81C` / Asphalt | same | Attention: voice pad, the one primary action, warnings, focus |
| `amberInk` | `#FFB81C` | `#8A5A00` | Amber as text/stroke on ground |
| `red` / `onRed` | `#C62A1F` / Paint | same | Stop: critical alerts and SOS only. Never as text on ground (2.6:1) |

Access via `VisionNavTheme.kerb`. Contrast is computed and documented in `Color.kt`.

## Type

Atkinson Hyperlegible Next only (Braille Institute, for low vision). Hierarchy by weight and size.
Body ≥ 20 sp. Left-aligned everywhere. Sentence case. No ALL CAPS, no letter-spaced labels,
no labels above content unless the content is ambiguous without them.

## Components

- `BigActionButton` — style decides the street role:
  `SECONDARY` zebra bar (paint, square, bleeds edge to edge) · `PRIMARY` amber (one per screen max) ·
  `QUIET` kerbstone row (bleeds, 3 dp joints) · `DANGER` red. Icons render only on DANGER.
  Put the old "subtitle" text into `onClickLabel` (TalkBack: "double tap to …") instead of on screen,
  unless it carries live information (e.g. the destination name).
- `TactilePad` (`ui/kerb`) — blister-dot voice pad. Only on Home.
- `RoadArrow(ArrowKind)` — painted carriageway arrow for maneuvers.
- `LaneProgress` — dashed centre line as route progress (TalkBack progress bar).
- `ChevronEdge` — hazard chevrons on the leading edge of alert bands.
- `Modifier.bleed()` — cancels the 16 dp gutter so paint reaches the screen edge.

## Rules

1. One bold thing per screen. Everything else is quiet.
2. Square ends for paint (bars, bands, kerbstones). Rounded corners only for things you hold (pad, dialogs, text fields).
3. No outlined cards, no shadows, no gradients, no decorative icons, no middle-dot meta strings, no arrows in button text.
4. Status is words first, colour second: problems use a different word *and* amber.
5. Copy: plain verbs, sentence case, says exactly what happens ("Go somewhere", "Check traffic", "Hold for help").
6. Never imply safety. All engine text arrives already checked by `SafetyPhraseGuard`; static strings are checked by `UserFacingTextPhraseGuardTest`.
7. Motion only in response to a person's action (press flashes amber). Respect reduced motion.
