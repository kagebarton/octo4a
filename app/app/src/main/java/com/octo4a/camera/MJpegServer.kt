package com.octo4a.camera

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import java.io.ByteArrayInputStream

interface MJpegFrameProvider {
    data class FrameInfo(val image: ByteArray? = null, val id: Int)

    suspend fun takeSnapshot(): ByteArray
    fun getNewFrame(prevFrame: FrameInfo?): FrameInfo
    fun registerListener(): Boolean
    fun unregisterListener()
}

// Simple http server hosting mjpeg stream along with
class MJpegServer(
    port: Int,
    private val frameProvider: MJpegFrameProvider,
    private val webRTC: WebRTCManager
): NanoHTTPD(port) {
    companion object {
        // Browser SDP offers are a few KB
        private const val MAX_WEBRTC_BODY_BYTES = 64 * 1024L
    }

    override fun serve(session: IHTTPSession?): Response {
        when (session?.uri) {
            "/snapshot" -> {
                    var res: Response? = null
                    kotlin.runCatching {
                        runBlocking {
                            val data = frameProvider?.takeSnapshot()
                            val inputStream = ByteArrayInputStream(data)

                            res = newFixedLengthResponse(
                                Response.Status.OK,
                                "image/jpeg",
                                inputStream,
                                data.size.toLong()
                            )
                        }
                    }.onFailure {
                    }

                return res ?: newFixedLengthResponse(
                    "<h1>Failed to fetch image</h1>"
                )
            }
            "/mjpeg" -> {
                return MjpegResponse(frameProvider)
            }
            "/webrtc" -> return serveWebRTC(session).apply { addHeader("Access-Control-Allow-Origin", "*") }
            else -> return newFixedLengthResponse(
                "<html><body>"
                        + "<h1>GET /snapshot</h1><p>GET a current JPEG image.</p>"
                        + "<h1>GET /mjpeg</h1><p>GET MJPEG frames.</p>"
                        + "<h1>POST /webrtc</h1><p>POST WebRTC SDP Offer.</p>"
                        + "</body></html>"
            )
        }
    }

    // Signaling as in camera-streamer's API. Either the client offers ("offer", as OctoPrint does)
    // and we answer, or it sends "request", we offer, and it answers ("answer"). Our reply's id
    // names the peer in later messages.
    private fun serveWebRTC(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) {
            // CORS preflight
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
                addHeader("Access-Control-Allow-Headers", "*")
                addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            }
        }
        val badRequest = newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Bad Request")
        if (session.method != Method.POST) return badRequest
        // parseBody spools the whole body to a temp file and then reads it into
        // memory, so a huge Content-Length fills the disk and then the heap.
        val bodySize = session.headers["content-length"]?.toLongOrNull() ?: 0L
        if (bodySize > MAX_WEBRTC_BODY_BYTES) {
            return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE, "text/plain", "Payload Too Large")
        }
        fun json(obj: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())
        fun failed(message: String) = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", message)

        return try {
            val body = HashMap<String, String>().also { session.parseBody(it) }["postData"] ?: ""
            val request = JSONObject(body)
            val id = request.optString("id")
            val sdp = request.optString("sdp")
            when (request.optString("type")) {
                "request" -> {
                    val (newId, offer) = runBlocking { webRTC.createOffer() }
                    if (newId.isEmpty()) failed("Failed to create WebRTC offer")
                    else json(JSONObject().put("type", "offer").put("sdp", offer).put("id", newId)
                        .put("iceServers", JSONArray()))
                }
                "offer" -> {
                    val (newId, answer) = runBlocking { webRTC.answerOffer(sdp) }
                    if (newId.isEmpty()) failed("Failed to answer WebRTC offer")
                    else json(JSONObject().put("type", "answer").put("sdp", answer).put("id", newId))
                }
                "answer" ->
                    if (runBlocking { webRTC.processAnswer(id, sdp) }) json(JSONObject())
                    else failed("Failed to process WebRTC answer")
                "remote_candidate" -> {
                    val candidates = request.optJSONArray("candidates") ?: JSONArray()
                    for (i in 0 until candidates.length()) {
                        val candidate = candidates.optJSONObject(i) ?: continue
                        webRTC.addIceCandidate(id, IceCandidate(candidate.optString("sdpMid"),
                            candidate.optInt("sdpMLineIndex"), candidate.optString("candidate")))
                    }
                    json(JSONObject())
                }
                else -> badRequest
            }
        } catch (e: Throwable) {
            // Malformed JSON throws, and deeply nested JSON overflows org.json's recursive parser.
            // NanoHTTPD only catches Exception, so an uncaught Error would kill the app.
            e.printStackTrace()
            badRequest
        }
    }

    fun startServer() {
        start()
    }

    fun stopServer() {
        stop()
    }
}