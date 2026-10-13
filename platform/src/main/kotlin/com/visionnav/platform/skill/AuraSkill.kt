package com.visionnav.platform.skill

import android.content.Intent
import com.visionnav.core.announce.AnnouncementPriority

/**
 * A voice capability contributed by a feature module (phone, camera tools, app control, ...).
 *
 * Skills are registered with Hilt (`@Binds @IntoSet AuraSkill`). When the core command parser
 * does not recognise an utterance, the engine offers it to each skill in [order] until one
 * returns an action. Skills never speak or listen directly: all audio goes through [SkillHost],
 * so the single priority queue and the SafetyPhraseGuard apply to every feature.
 */
interface AuraSkill {
    /** Stable id for logs, e.g. "phone.call". */
    val id: String

    /** Lower runs first. Use ranges per module (phone 100s, tools 200s, assist 300s, link 400s). */
    val order: Int get() = 500

    /** Example phrases for "what can I say". Short, plain English. */
    val examples: List<String>

    /**
     * Returns an action if this skill handles [utterance], else null. Must be fast and side-effect
     * free (it may be called for every recognised phrase). [utterance] is lower-case, trimmed,
     * punctuation removed; [alternatives] are the other recognition hypotheses, same normalisation.
     */
    fun match(utterance: String, alternatives: List<String> = emptyList()): SkillAction?
}

fun interface SkillAction {
    suspend fun run(host: SkillHost)
}

/** What a skill may ask the app to do. Implemented by the engine. */
interface SkillHost {
    /** Speak through the priority queue. Text must be plain and must never imply safety. */
    fun say(text: String, priority: AnnouncementPriority = AnnouncementPriority.NAVIGATION, key: String = "skill")

    /** Start an activity (dial screen, settings page, another app). */
    fun launch(intent: Intent)

    /** Bring a registered feature screen to the front (see the app's FeatureScreen registry). */
    fun openScreen(route: String)

    /** Ask a follow-up question and return what the user said, or null if nothing was heard. */
    suspend fun ask(question: String): String?

    /** Ask a yes/no question. Anything other than a clear yes counts as no. */
    suspend fun confirm(question: String): Boolean
}
