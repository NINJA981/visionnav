package com.visionnav.app.ui.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.visionnav.core.announce.SafetyPhraseGuard
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.w3c.dom.Element

/**
 * Product rule §0.1: no user-facing text may claim a road is safe/clear or tell the user to
 * cross. Checks every string resource and everything the fake controller can say or show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserFacingTextPhraseGuardTest {

    private val guard = SafetyPhraseGuard()

    private fun stringsFile(): File {
        // Gradle runs unit tests with the module directory as working directory.
        val candidates = listOf(
            File("src/main/res/values/strings.xml"),
            File("app/src/main/res/values/strings.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("strings.xml not found from ${File(".").absolutePath}")
    }

    /** All <string>, <plurals><item> and <string-array><item> values, unescaped, placeholders filled. */
    private fun allResourceStrings(): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile())
        val out = linkedMapOf<String, String>()
        fun clean(raw: String) = raw
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\n", " ")
            .replace(Regex("%(\\d+\\$)?[sd]"), "Placeholder")
            .trim()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as Element
            out[e.getAttribute("name")] = clean(e.textContent)
        }
        for (tag in listOf("plurals", "string-array")) {
            val groups = doc.getElementsByTagName(tag)
            for (i in 0 until groups.length) {
                val g = groups.item(i) as Element
                val items = g.getElementsByTagName("item")
                for (j in 0 until items.length) {
                    out["${g.getAttribute("name")}[$j]"] = clean(items.item(j).textContent)
                }
            }
        }
        return out
    }

    @Test
    fun guard_sanityCheck_blocksForbiddenPhrases() {
        assertThat(guard.isAllowed("It is safe to cross now.")).isFalse()
        assertThat(guard.isAllowed("The road is clear.")).isFalse()
        assertThat(guard.isAllowed("Vehicle approaching.")).isTrue()
    }

    @Test
    fun everyStringResource_passesSafetyPhraseGuard() {
        val strings = allResourceStrings()
        assertThat(strings.size).isGreaterThan(150)
        val blocked = strings.filterValues { !guard.isAllowed(it) }
        assertWithMessage("Strings blocked by SafetyPhraseGuard: $blocked").that(blocked).isEmpty()
    }

    @Test
    fun requiredSafetyStatements_arePresent() {
        val strings = allResourceStrings()
        assertThat(strings.keys).containsAtLeast(
            "safety_never_decides", "safety_ahead_only", "safety_may_miss", "crossing_disclaimer",
            "crossing_coverage", "status_detector_mock", "limit_not_detected", "privacy_camera", "credit_yolo",
        )
        assertThat(strings["crossing_coverage"]).isEqualTo("Camera sees ahead only.")
    }

    @Test
    fun fakeSampleTexts_passSafetyPhraseGuard() {
        val blocked = FakeSampleData.fixedTexts.filterNot { guard.isAllowed(it) }
        assertWithMessage("Fake sample texts blocked: $blocked").that(blocked).isEmpty()
    }

    /** Drives the fake through every flow and checks every piece of text it ever exposes. */
    @Test
    fun everyTextTheFakeEmits_passesSafetyPhraseGuard() = runTest {
        val c = FakeAppController(backgroundScope, tickMs = 100)
        val seen = linkedSetOf<String>()
        fun capture() {
            val s = c.state.value
            seen += listOfNotNull(
                s.lastSpoken, s.voiceTranscript, s.safetyAlert?.text, s.gps.text, s.heading.text, s.camera.text,
                s.camera.detectorModelName, s.performanceText, s.crossing.stateText, s.crossing.contextText,
                s.search.error, s.search.previewError, s.navigation?.currentInstruction, s.navigation?.nextInstruction,
                s.navigation?.distanceToNextText, s.navigation?.remainingText, s.navigation?.upcomingCrossingText,
                s.search.preview?.firstInstruction, s.search.preview?.lengthText, s.search.preview?.durationText,
                s.search.preview?.crossingsText,
                (s.sos as? SosUi.AwaitingConfirmation)?.message, (s.sos as? SosUi.Sent)?.message,
            )
            seen += s.crossing.observations
            seen += s.errors.map { it.text }
        }
        suspend fun run(action: UiAction, ticks: Int) {
            c.dispatch(action)
            capture()
            repeat(ticks) {
                advanceTimeBy(100)
                capture()
            }
        }
        run(UiAction.StartVoiceCommand, 4)
        run(UiAction.WhatsAround, 0)
        run(UiAction.RepeatLast, 0)
        run(UiAction.Search("temple"), 3)
        run(UiAction.Search("offline"), 3)
        run(UiAction.Search("nothing-matches-this"), 3)
        run(UiAction.SelectPlace(FakeSampleData.places.first()), 3)
        run(UiAction.StartNavigation, 10)
        run(UiAction.StartTrafficObservation, 12)
        run(UiAction.ConfirmAtCrossing, 1)
        run(UiAction.NextSegment, 6)
        run(UiAction.ToggleTorch, 0)
        run(UiAction.StopTrafficObservation, 45)
        run(UiAction.StopNavigation, 0)
        run(UiAction.StartExploring, 0)
        run(UiAction.StopExploring, 0)
        run(UiAction.ToggleTorch, 0)
        run(UiAction.SavePlace("Corner shop"), 0)
        run(UiAction.TriggerSos, 0)
        run(UiAction.CancelSos, 0)
        run(UiAction.TriggerSos, 0)
        run(UiAction.ConfirmSos, 3)

        assertThat(seen.size).isGreaterThan(30)
        val blocked = seen.filterNot { guard.isAllowed(it) }
        assertWithMessage("Fake controller texts blocked: $blocked").that(blocked).isEmpty()
    }
}
