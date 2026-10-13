# AURA ecosystem — using the whole phone, not just navigation

**Goal:** a blind or low-vision person can do everything a sighted person does with their phone —
calls, messages, apps, reading, recognising things, getting around, staying safe — by voice, touch
and vibration, with AURA as the one consistent companion. AURA works *with* TalkBack, never against
it, and every screen stays fully TalkBack-accessible.

## Pillars

| Module | What the user can do | Key Android pieces |
|---|---|---|
| `:phone` — **Everyday phone** | "Call Priya", hear who is calling and answer/decline by voice, "read my messages", "reply: on my way", hear WhatsApp/SMS/any notification read aloud (filtered, never spammy), "what time is it", "battery", "set an alarm for 6", reminders, optional AURA home screen | Contacts provider, Telecom (`ACTION_CALL`, caller announce via `TelephonyCallback` + contacts lookup, `TelecomManager.acceptRingingCall`), `NotificationListenerService` (read + reply via `RemoteInput`), `AlarmClock` intents, `BatteryManager`, HOME-category activity (opt-in launcher) |
| `:tools` — **Camera tools** | "Read this" (signs, letters, labels, documents, on-device OCR), "what is this product" (barcode), "what colour is this", light detector (pitch follows brightness), "describe this" (one photo to a vision AI, only on request, spoken notice) | ML Kit text recognition + barcode (on-device), single CameraSession still capture, colour naming from centre patch, Claude vision API (opt-in, key from local config) |
| `:assist` — **Control any app** | "Open WhatsApp", "read the screen", "what's on screen", "tap Send", "scroll down", "go back", "go home", "type hello", "notifications", from anywhere via the accessibility button or a shortcut | `AccessibilityService` (`isAccessibilityTool`), node-tree reading, `performGlobalAction`, label-based node search + click, `ACTION_SET_TEXT`, app launch by name via PackageManager |
| `:link` — **Safety network (AURA backend)** | Journeys mirrored to the AURA backend, safety check-ins ("Are you okay?" → "I'm safe" / "I need help"), trusted contacts see incidents in the AURA console | AURA FastAPI: `POST /api/v1/journeys`, `/start`, `/location`, `/perception/events`, `/safety/checkin`, WebSocket; offline-first queue; SMS fallback via existing `SosMessenger` |

Existing pillars (already built): walking navigation, crossing traffic awareness, obstacle and scene
awareness, SOS.

## Rules every feature follows
1. **One voice.** All speech goes through `SkillHost.say` → `AudioQueueManager` → `SafetyPhraseGuard`.
   Safety alerts always preempt feature speech.
2. **Voice skills, not parser edits.** Each module contributes `AuraSkill`s (`platform/.../skill/AuraSkill.kt`)
   via `@Binds @IntoSet`. The core parser runs first; unknown phrases go to skills.
3. **Screens register themselves.** UI lives in `app/src/main/java/com/visionnav/app/feature/<name>/`
   and registers `FeatureScreen`s (`app/.../feature/Features.kt`). Strings in `res/values/strings_<name>.xml`.
4. **AURA design system** (`AURA_DESIGN.md`): `BigActionButton` roles, `ListGroup`, AURA tokens; body ≥ 20 sp,
   targets ≥ 72 dp, TalkBack labels, 200 % font scale, no colour-only meaning.
5. **Privacy:** on-device by default. Cloud only for an explicit "describe this" (one photo) and for the
   user's own AURA backend. Notification and screen contents never leave the phone.
6. **Confirm before acting on others:** calling, sending a message, replying, and anything irreversible
   is read back and confirmed by voice ("Send 'on my way' to Priya?").
7. **Honest about limits:** if a permission, service or model is missing, say so plainly and say how to fix it.
8. **Play policy:** declare `isAccessibilityTool`; no SMS-read permissions (use notifications instead);
   permissions requested in context with spoken rationale.

## Delivery order
1. `:phone` + `:assist` (biggest change to daily phone use)
2. `:tools`
3. `:link` (AURA backend + console)
4. Tamil localisation of all phrases
