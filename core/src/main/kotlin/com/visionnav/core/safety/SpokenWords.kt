package com.visionnav.core.safety

import com.visionnav.core.vision.ObjectClass

/**
 * Shared English vocabulary for safety, scene and path utterances. All wording is relative
 * ("close", "ahead") — never metres, seconds or certainty claims.
 */
object SpokenWords {

    /** Lower-case singular noun, e.g. "car", "fire hydrant". */
    fun singular(c: ObjectClass): String = c.label

    /** Lower-case plural noun, e.g. "people", "buses". */
    fun plural(c: ObjectClass): String = when (c) {
        ObjectClass.PERSON -> "people"
        else -> {
            val s = c.label
            if (s.endsWith("s") || s.endsWith("ch") || s.endsWith("sh") || s.endsWith("x")) s + "es" else s + "s"
        }
    }

    /** "A car" / "An object". */
    fun withArticle(c: ObjectClass): String {
        val n = singular(c)
        return (if (n.first().lowercaseChar() in "aeiou") "An " else "A ") + n
    }

    /** "Two people", "Many cars"; 1 yields the article form. */
    fun counted(c: ObjectClass, count: Int): String = when {
        count <= 1 -> withArticle(c)
        count < NUMBER_WORDS.size -> NUMBER_WORDS[count] + " " + plural(c)
        else -> "Many " + plural(c)
    }

    fun capitalize(s: String): String = s.replaceFirstChar { it.uppercaseChar() }

    /** Relative proximity word, or null where nothing useful can be said (MEDIUM, UNKNOWN). */
    fun proximityWord(p: ProximityBand): String? = when (p) {
        ProximityBand.VERY_NEAR -> "very close"
        ProximityBand.NEAR -> "close"
        ProximityBand.FAR -> "far"
        ProximityBand.MEDIUM, ProximityBand.UNKNOWN -> null
    }

    /**
     * Direction in the camera view. "ahead" means the centre of the camera view, which is the walking
     * direction only while the phone is held forward; that is the documented usage of Phase 5 alerts.
     */
    fun direction(sector: ViewSector, inPath: Boolean): String = when (sector) {
        ViewSector.CENTER -> "ahead"
        ViewSector.LEFT, ViewSector.FAR_LEFT -> if (inPath) "ahead, slightly left" else if (sector == ViewSector.LEFT) "slightly left" else "on the left"
        ViewSector.RIGHT, ViewSector.FAR_RIGHT -> if (inPath) "ahead, slightly right" else if (sector == ViewSector.RIGHT) "slightly right" else "on the right"
    }

    private val NUMBER_WORDS = listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine")
}
