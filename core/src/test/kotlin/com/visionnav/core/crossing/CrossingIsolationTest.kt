package com.visionnav.core.crossing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Brief 18: navigation heading is never overwritten by crossing logic.
 * The crossing package has no API that could write heading, depends on no heading source, and the
 * map/camera fusion is pure.
 */
class CrossingIsolationTest {

    private val crossingClasses = listOf(
        CrossingAssistStateMachine::class.java,
        FlowFusion::class.java,
        CameraOrientationEstimator::class.java,
        TrafficObservationReporter::class.java,
        RoadContextInterpreter::class.java,
        MismatchPersistence::class.java,
    )

    @Test
    fun brief18_noHeadingWritingApiInCrossingPackage() {
        for (cls in crossingClasses) {
            val names = cls.methods.map { it.name.lowercase() } + cls.declaredFields.map { it.name.lowercase() }
            assertThat(names.filter { "heading" in it }).isEmpty()
            // Nothing that accepts a heading/orientation *source* (which could be written to).
            val paramTypes = cls.declaredConstructors.flatMap { it.parameterTypes.toList() } + cls.methods.flatMap { it.parameterTypes.toList() }
            assertThat(paramTypes.map { it.simpleName }).containsNoneOf("OrientationSource", "LocationSource", "ActivitySource")
        }
    }

    @Test
    fun brief18_fusionIsPure_noMutableState() {
        for (cls in listOf(FlowFusion::class.java, RoadContextInterpreter::class.java)) {
            for (f in cls.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }) {
                assertThat(Modifier.isFinal(f.modifiers)).isTrue()
            }
        }
    }

    @Test
    fun brief18_sourcesDoNotReferenceHeadingWriters() {
        val dir = File("src/main/kotlin/com/visionnav/core/crossing")
        assertThat(dir.isDirectory).isTrue()
        val forbidden = Regex("""\b(OrientationSource|HeadingTracker|LocationSource|walkingHeading)\b|set\w*Heading|heading\s*=""")
        val sources = dir.listFiles { f -> f.extension == "kt" }!!.toList()
        assertThat(sources).isNotEmpty()
        for (f in sources) {
            val hits = forbidden.findAll(f.readText()).map { it.value }.toList()
            assertThat(hits).isEmpty()
        }
    }
}
