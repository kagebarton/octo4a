package com.octo4a.camera

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// Threading: the factory, the track and every PeerConnection are only used on peerEvents, where
// peers are looked up by id, so a disposed peer is never touched again. libwebrtc callbacks only
// post to peerEvents: a peer can't be disposed inside its own callback, and anything a callback
// throws aborts the process.
class WebRTCManager(private val context: Context) {
    companion object {
        private const val TAG = "WebRTCManager"
        // Browsers only expose a track in ontrack's streams if it was added with a stream id
        private const val STREAM_ID = "octo4a"
        // ICE reports DISCONNECTED on brief network drops and often recovers by itself
        private const val DISCONNECT_GRACE_SECONDS = 15L
        // Setup only gathers host candidates, which takes well under a second
        private const val SETUP_TIMEOUT_SECONDS = 10L
        // ICE can wait forever on a client that never answers our offer, or whose candidates
        // can't be reached
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        // Each peer runs its own encoder
        private const val MAX_PEERS = 3
    }

    private class Peer(val pc: PeerConnection) {
        // Set once ICE connects, and kept during the disconnect grace period
        var connected = false
        var disconnectTimer: ScheduledFuture<*>? = null
    }

    private val peerEvents = Executors.newSingleThreadScheduledExecutor { Thread(it, "WebRTCPeerEvents") }
    private var factory: PeerConnectionFactory? = null
    // pushFrame runs on the camera thread, so release swaps the source out under frameLock
    private val frameLock = Any()
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    // Oldest first, for eviction
    private val peers = LinkedHashMap<String, Peer>()
    @Volatile private var streaming = false
    // Whether a peer is connected, so frames are wanted
    val isStreaming: Boolean get() = streaming

    // Called on peerEvents when the first peer connects and after the last one closes
    @Volatile var onStreamActiveStatusChanged: ((Boolean) -> Unit)? = null

    // Runs block on peerEvents after delaySeconds, or returns null once released. What block
    // throws is logged, as the executor would swallow it.
    private fun schedule(delaySeconds: Long, block: () -> Unit): ScheduledFuture<*>? = try {
        peerEvents.schedule(Runnable {
            try {
                block()
            } catch (e: Throwable) {
                Log.e(TAG, "WebRTC peer event failed", e)
            }
        }, delaySeconds, TimeUnit.SECONDS)
    } catch (e: RejectedExecutionException) {
        null
    }

    private fun post(block: () -> Unit) = schedule(0, block)

    private fun updateStreaming() {
        val active = peers.values.any { it.connected }
        if (active != streaming) {
            streaming = active
            onStreamActiveStatusChanged?.invoke(active)
        }
    }

    private fun closePeer(id: String, reason: String) {
        val peer = peers.remove(id) ?: return
        Log.i(TAG, "Closing WebRTC peer $id: $reason")
        peer.disconnectTimer?.cancel(false)
        // Closes it too
        peer.pc.dispose()
        updateStreaming()
    }

    // Makes room for a new peer by closing the oldest one that isn't streaming, else the oldest
    private fun evictIfFull() {
        if (peers.size < MAX_PEERS) return
        val idle = peers.entries.firstOrNull { !it.value.connected || it.value.disconnectTimer != null }
        closePeer((idle ?: peers.entries.first()).key, "too many peers")
    }

    fun init() {
        post {
            // WebRTC registers its network monitor's receiver on this context. A service context
            // drops it when the service is destroyed, and release's unregister then aborts.
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )

            // Frames arrive as NV21 arrays, not GL textures, so neither factory needs an EGL
            // context. Nothing is decoded, but codec negotiation still lists the decoders.
            val noEglContext: EglBase.Context? = null
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(
                    noEglContext,  /* enableIntelVp8Encoder */ true,  /* enableH264HighProfile */ true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(noEglContext))
                .createPeerConnectionFactory()

            val source = factory?.createVideoSource(false)
            localVideoTrack = factory?.createVideoTrack("100", source)
            synchronized(frameLock) { videoSource = source }
        }
    }

    // Closes every peer and frees libwebrtc's objects. The manager can't be used afterwards.
    fun release() {
        // The service is going away, and CameraX unbinds the camera with its lifecycle
        onStreamActiveStatusChanged = null
        post {
            for (peer in peers.values) {
                peer.disconnectTimer?.cancel(false)
                peer.pc.dispose()
            }
            peers.clear()
            streaming = false
            val source = synchronized(frameLock) { videoSource.also { videoSource = null } }
            localVideoTrack?.dispose()
            source?.dispose()
            factory?.dispose()
            localVideoTrack = null
            factory = null
        }
        // Tasks already queued or delayed still run, and find no peers
        peerEvents.shutdown()
    }

    // Sends a frame to the connected peers. release runs once libwebrtc is done with nv21, which
    // can be after pushFrame returns.
    fun pushFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int, release: () -> Unit = {}) {
        val buffer = NV21Buffer(nv21, width, height, Runnable { release() })
        try {
            if (streaming) {
                synchronized(frameLock) {
                    videoSource?.capturerObserver?.onFrameCaptured(VideoFrame(buffer, rotation, System.nanoTime()))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            buffer.release()
        }
    }

    // Posts a peer's ICE events to peerEvents. onGatheringComplete runs once its local
    // description has all ICE candidates, so it can be returned in one response.
    private fun peerObserver(id: String, onGatheringComplete: () -> Unit) = object : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            post { onIceConnectionState(id, state) }
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) post(onGatheringComplete)
        }
        override fun onIceCandidate(p0: IceCandidate?) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
    }

    // A DISCONNECTED peer gets time to recover before it's closed: OctoPrint doesn't reconnect,
    // so closing on a brief drop would freeze its stream until a page reload.
    private fun onIceConnectionState(id: String, state: PeerConnection.IceConnectionState?) {
        val peer = peers[id] ?: return
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                peer.disconnectTimer?.cancel(false)
                peer.disconnectTimer = null
                peer.connected = true
                updateStreaming()
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                if (peer.disconnectTimer == null) {
                    peer.disconnectTimer = schedule(DISCONNECT_GRACE_SECONDS) {
                        closePeer(id, "disconnected for ${DISCONNECT_GRACE_SECONDS}s")
                    }
                }
            }
            PeerConnection.IceConnectionState.FAILED,
            PeerConnection.IceConnectionState.CLOSED -> closePeer(id, "ICE $state")
            else -> {}
        }
    }

    // SdpObserver for one create or set call on peer id. Its callbacks run on peerEvents, and
    // onSuccess only while the peer is open. onSuccess gets the created description, or null after a set.
    private inner class SdpCallback(
        private val id: String,
        private val onFailure: (String?) -> Unit,
        private val onSuccess: (SessionDescription?) -> Unit
    ) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = succeed(sdp)
        override fun onSetSuccess() = succeed(null)
        override fun onCreateFailure(error: String?) = fail(error)
        override fun onSetFailure(error: String?) = fail(error)

        private fun succeed(sdp: SessionDescription?) {
            post {
                if (!peers.containsKey(id)) {
                    onFailure("peer closed")
                } else {
                    try {
                        onSuccess(sdp)
                    } catch (e: Throwable) {
                        onFailure(e.toString())
                    }
                }
            }
        }

        private fun fail(error: String?) {
            post { onFailure(error) }
        }
    }

    // Runs block on peerEvents and suspends until it calls finish. Gives up with failed if block
    // throws, after SETUP_TIMEOUT_SECONDS, or once released; later finish calls are ignored.
    private suspend fun <T> awaitPeerEvents(failed: T, block: (finish: (T) -> Unit) -> Unit): T =
        suspendCoroutine { cont ->
            val done = AtomicBoolean(false)
            val finish: (T) -> Unit = { result -> if (done.compareAndSet(false, true)) cont.resume(result) }
            val timeout = schedule(SETUP_TIMEOUT_SECONDS) { finish(failed) }
            val started = post {
                try {
                    block(finish)
                } catch (e: Throwable) {
                    Log.w(TAG, "WebRTC setup failed", e)
                    finish(failed)
                }
            }
            if (timeout == null || started == null) finish(failed)
        }

    // Creates a peer, lets negotiate start its SDP exchange, and returns (id, local SDP) once ICE
    // gathering is done, or ("", "") on failure. negotiate's callbacks report errors to fail.
    private suspend fun newPeer(
        config: PeerConnection.RTCConfiguration,
        negotiate: (id: String, pc: PeerConnection, fail: (String?) -> Unit) -> Unit
    ): Pair<String, String> {
        val id = UUID.randomUUID().toString()
        val sdp = awaitPeerEvents<String?>(null) { finish ->
            val fail: (String?) -> Unit = { error ->
                Log.w(TAG, "WebRTC setup failed: $error")
                finish(null)
            }
            val pc = factory?.createPeerConnection(config, peerObserver(id) {
                finish(peers[id]?.pc?.localDescription?.description)
            })
            if (pc == null) {
                fail("could not create peer connection")
            } else {
                evictIfFull()
                peers[id] = Peer(pc)
                schedule(CONNECT_TIMEOUT_SECONDS) {
                    if (peers[id]?.connected == false) closePeer(id, "not connected after ${CONNECT_TIMEOUT_SECONDS}s")
                }
                negotiate(id, pc, fail)
            }
        }
        if (sdp.isNullOrEmpty()) {
            post { closePeer(id, "setup failed") }
            return Pair("", "")
        }
        return Pair(id, sdp)
    }

    // Creates an offer (server is the offerer, matching camera-streamer API).
    // Returns a pair of (id, offerSdp). Waits for ICE gathering to complete.
    suspend fun createOffer(): Pair<String, String> =
        newPeer(PeerConnection.RTCConfiguration(emptyList())) { id, pc, fail ->
            pc.addTrack(localVideoTrack)
            pc.createOffer(SdpCallback(id, fail) { offer ->
                pc.setLocalDescription(SdpCallback(id, fail) {
                    // ICE gathering starts; newPeer returns once it's complete
                }, offer)
            }, MediaConstraints())
        }

    // Moves H.264 to the front of an SDP's video codec list. The phone sends with the first
    // codec both sides support, and browsers list VP8 first. This WebRTC build only offers
    // H.264 when a hardware encoder exists, so preferring it keeps encoding off the CPU.
    private fun preferH264(sdp: String): String {
        val lines = sdp.split("\r\n")
        val h264 = lines.mapNotNull { Regex("^a=rtpmap:(\\d+) H264/90000").find(it)?.groupValues?.get(1) }.toSet()
        if (h264.isEmpty()) return sdp
        return lines.joinToString("\r\n") { line ->
            if (!line.startsWith("m=video ")) {
                line
            } else {
                // m=video <port> <proto> <payload types...>
                val fields = line.split(" ")
                val payloadTypes = fields.drop(3)
                (fields.take(3) + payloadTypes.filter { it in h264 } + payloadTypes.filterNot { it in h264 })
                    .joinToString(" ")
            }
        }
    }

    // Answers a client's offer (client is the offerer, as OctoPrint's webrtc:// webcam does).
    // Returns a pair of (id, answerSdp). Waits for ICE gathering to complete.
    suspend fun answerOffer(offerSdp: String): Pair<String, String> {
        // Browsers offer Unified Plan SDP; this library defaults to Plan B
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        return newPeer(config) { id, pc, fail ->
            pc.setRemoteDescription(SdpCallback(id, fail) {
                // Reuses the recvonly video transceiver from the offer; its audio one stays inactive
                pc.addTrack(localVideoTrack, listOf(STREAM_ID))
                pc.createAnswer(SdpCallback(id, fail) { answer ->
                    if (answer == null) {
                        fail("empty answer")
                    } else {
                        pc.setLocalDescription(SdpCallback(id, fail) {
                            // ICE gathering starts; newPeer returns once it's complete
                        }, answer)
                    }
                }, MediaConstraints())
            }, SessionDescription(SessionDescription.Type.OFFER, preferH264(offerSdp)))
        }
    }

    // Sets the client's answer SDP as remote description for the given peer connection id.
    suspend fun processAnswer(id: String, answerSdp: String): Boolean = awaitPeerEvents(false) { finish ->
        val pc = peers[id]?.pc
        if (pc == null) {
            finish(false)
        } else {
            pc.setRemoteDescription(SdpCallback(id, { finish(false) }) { finish(true) },
                SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        }
    }

    // Adds a remote ICE candidate from the client to the given peer connection.
    fun addIceCandidate(id: String, candidate: IceCandidate) {
        post { peers[id]?.pc?.addIceCandidate(candidate) }
    }
}
