package com.visionnav.core.announce

/**
 * Final gate for every utterance. VisionNav must never claim that a road is safe or clear,
 * or tell the user to cross. Any text matching a forbidden pattern is blocked.
 *
 * This is a backstop, not the primary control: phrase generators must never produce such text,
 * and tests assert they don't. A block here is a defect and is reported via [onBlocked].
 */
class SafetyPhraseGuard(private val onBlocked: (String) -> Unit = {}) {

    sealed interface Verdict {
        data class Allowed(val text: String) : Verdict
        data class Blocked(val text: String, val pattern: String) : Verdict
    }

    fun check(text: String): Verdict {
        val normalized = text.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()
        for (p in FORBIDDEN) {
            if (p.containsMatchIn(normalized)) {
                onBlocked(text)
                return Verdict.Blocked(text, p.pattern)
            }
        }
        return Verdict.Allowed(text)
    }

    fun isAllowed(text: String): Boolean = check(text) is Verdict.Allowed

    companion object {
        val FORBIDDEN: List<Regex> = listOf(
            Regex("\\bsafe to (cross|go|walk|proceed)\\b"),
            Regex("\\b(it is|it's|its|road is|street is|now) safe\\b"),
            Regex("\\b(road|street|way|crossing|traffic|path) (is )?(clear|empty|free)\\b"),
            Regex("\\bcross now\\b"),
            Regex("\\b(you can|you may|go ahead and|okay to|ok to|start to|begin to|time to) cross\\b"),
            Regex("\\bno (vehicles|cars|traffic) (are |is )?(coming|approaching)\\b"),
            Regex("\\btraffic (has )?stopped\\b"),
            Regex("\\ball clear\\b"),
            Regex("^(go|walk|cross)$"),
        )
    }
}
