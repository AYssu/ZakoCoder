package dev.zakocoder.app

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.LinkedBlockingQueue

/** Pace response events without applying their throttle to the much larger request upload. */
internal class ResponseOnlyDispatcher : Dispatcher() {
    private val responses = LinkedBlockingQueue<MockResponse>()
    private val closed = MockResponse().setResponseCode(503)

    fun enqueue(response: MockResponse) { responses.add(response) }

    override fun peek() = MockResponse()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val response = responses.take()
        // Release every waiting dispatcher when MockWebServer shuts down.
        if (response === closed) responses.add(closed)
        return response
    }

    override fun shutdown() { responses.add(closed) }
}
