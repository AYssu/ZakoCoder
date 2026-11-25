package dev.zakocoder.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DoctorFatalMarkersTest {
    @Test fun invalidJsonExtraFieldsAndUnsafeValuesAreDropped() {
        val t = 1_700_000_000_000L
        val markers = fatalMarkersReport(listOf(
            "{not-json",
            """{"time":$t,"exceptionClass":"IllegalStateException","thread":"main","secret":"private"}""",
            """{"time":$t,"exceptionClass":"/private/path","thread":"main"}""",
            """{"time":$t,"exceptionClass":"IllegalStateException","thread":"main"}""",
            """{"time":$t,"exceptionClass":"IOException","thread":"worker 1","stage":"startup"}""",
        ))

        assertEquals(2, markers.length())
        assertEquals("IllegalStateException", markers.getJSONObject(0).getString("exceptionClass"))
        assertEquals("worker 1", markers.getJSONObject(1).getString("thread"))
        assertEquals("startup", markers.getJSONObject(1).getString("stage"))
    }

    @Test fun onlyTheLatestFiveValidMarkersAreRetainedInOrder() {
        val markers = fatalMarkersReport((0L..7L).map { time ->
            """{"time":${1_700_000_000_000L + time},"exceptionClass":"Failure$time","thread":"main"}"""
        })

        assertEquals(5, markers.length())
        assertEquals(
            listOf("Failure3", "Failure4", "Failure5", "Failure6", "Failure7"),
            (0 until markers.length()).map { markers.getJSONObject(it).getString("exceptionClass") },
        )
    }
}
