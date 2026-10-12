package com.visionnav.app.engine

import com.visionnav.core.announce.Announcement
import com.visionnav.core.announce.AnnouncementPriority
import com.visionnav.core.announce.Earcon

/**
 * Utterances owned by the app layer (session control, errors, SOS). Domain phrasing lives in
 * :core (navigation, crossing, safety, scene). Every string here is covered by a
 * SafetyPhraseGuard test.
 */
object EnginePhrases {
    const val LOCATION_UNAVAILABLE = "Location unavailable. Try again outdoors."
    const val CAMERA_UNAVAILABLE = "Camera unavailable."
    const val CAMERA_STARTING = "Starting camera."
    const val NO_RESULTS = "No places found."
    const val SEARCH_FAILED = "Search unavailable. Check your connection."
    const val ROUTE_FAILED = "Could not find a walking route."
    const val ROUTE_OFFLINE = "Route unavailable. Check your connection."
    const val NAVIGATION_STOPPED = "Navigation stopped."
    const val GUIDANCE_PAUSED = "Guidance paused. Safety alerts stay on."
    const val GUIDANCE_RESUMED = "Guidance resumed."
    const val TORCH_ON = "Torch on."
    const val TORCH_OFF = "Torch off."
    const val TORCH_UNAVAILABLE = "No torch on this phone."
    const val LOW_LIGHT_SUGGEST = "Low light. Say light on to turn on the torch."
    const val LOW_LIGHT_AUTO = "Low light. Torch on."
    const val NOT_UNDERSTOOD = "Sorry, I did not understand. Say help me with commands to hear options."
    const val NO_SPEECH = "I did not hear anything."
    const val VOICE_UNAVAILABLE = "Voice commands unavailable on this phone."
    const val MIC_PERMISSION = "Microphone permission needed for voice commands."
    const val SOS_CONFIRM = "Send an emergency message to your contacts? Press confirm to send, or cancel."
    const val SOS_NO_CONTACTS = "No emergency contacts saved. Add them in settings."
    const val SOS_CANCELLED = "Emergency message cancelled."
    const val SOS_OPENING_SMS = "Opening your messages app with the emergency text ready to send."
    const val SOS_FAILED = "Emergency message failed to send."
    const val SAVE_PLACE_NEEDS_NAME = "Say save this place as, followed by a name."
    const val NOT_NAVIGATING = "No active route."
    const val SPEECH_RATE_CHANGED = "Speech speed changed."
    const val HELP = "You can say: take me to, followed by a place. Where am I. What's around. " +
        "Traffic. I'm at the crossing. Next segment. Repeat. Stop. Light on. Help for emergency."

    fun searching(query: String) = "Searching for $query."
    fun results(count: Int, first: String) = if (count == 1) "Found $first." else "Found $count places. First: $first."
    fun savedPlace(name: String) = "Saved this place as $name."
    fun unknownSavedPlace(name: String) = "No saved place called $name."
    fun sosSent(count: Int) = "Emergency message sent to $count contact${if (count == 1) "" else "s"}."
    fun sosPending(count: Int) = "Emergency message being sent to $count contact${if (count == 1) "" else "s"}."

    fun info(text: String, key: String) =
        Announcement(text, AnnouncementPriority.NAVIGATION, "app:$key", ttlMs = 6_000)

    fun error(text: String, key: String) =
        Announcement(text, AnnouncementPriority.NAVIGATION, "app:$key", ttlMs = 6_000, earcon = Earcon.ERROR)

    fun confirm(text: String, key: String) =
        Announcement(text, AnnouncementPriority.NAVIGATION, "app:$key", ttlMs = 6_000, earcon = Earcon.CONFIRM)

    /** Every fixed phrase, for the guard test. */
    val fixed: List<String> = listOf(
        LOCATION_UNAVAILABLE, CAMERA_UNAVAILABLE, CAMERA_STARTING, NO_RESULTS, SEARCH_FAILED, ROUTE_FAILED,
        ROUTE_OFFLINE, NAVIGATION_STOPPED, GUIDANCE_PAUSED, GUIDANCE_RESUMED, TORCH_ON, TORCH_OFF,
        TORCH_UNAVAILABLE, LOW_LIGHT_SUGGEST, LOW_LIGHT_AUTO, NOT_UNDERSTOOD, NO_SPEECH, VOICE_UNAVAILABLE,
        MIC_PERMISSION, SOS_CONFIRM, SOS_NO_CONTACTS, SOS_CANCELLED, SOS_OPENING_SMS, SOS_FAILED,
        SAVE_PLACE_NEEDS_NAME, NOT_NAVIGATING, SPEECH_RATE_CHANGED, HELP,
    )
}
