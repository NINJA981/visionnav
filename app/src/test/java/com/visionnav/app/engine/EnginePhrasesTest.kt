package com.visionnav.app.engine

import com.google.common.truth.Truth.assertThat
import com.visionnav.core.announce.SafetyPhraseGuard
import org.junit.Test

class EnginePhrasesTest {

    private val guard = SafetyPhraseGuard()

    @Test
    fun `every fixed app phrase passes the safety guard`() {
        for (p in EnginePhrases.fixed) assertThat(guard.check(p)).isInstanceOf(SafetyPhraseGuard.Verdict.Allowed::class.java)
    }

    @Test
    fun `dynamic app phrases pass the safety guard`() {
        val samples = listOf(
            EnginePhrases.searching("central station"),
            EnginePhrases.results(1, "Chennai Central"),
            EnginePhrases.results(4, "Marina Beach"),
            EnginePhrases.savedPlace("home"),
            EnginePhrases.unknownSavedPlace("gym"),
            EnginePhrases.sosSent(1),
            EnginePhrases.sosSent(3),
            EnginePhrases.sosPending(2),
        )
        for (p in samples) assertThat(guard.isAllowed(p)).isTrue()
    }

    @Test
    fun `no app phrase claims the way is safe or clear`() {
        val all = EnginePhrases.fixed.joinToString(" ").lowercase()
        assertThat(all).doesNotContain("safe to")
        assertThat(all).doesNotContain("clear")
        assertThat(all).doesNotContain("cross now")
    }
}
