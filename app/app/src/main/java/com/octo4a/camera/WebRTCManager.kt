package com.octo4a.camera

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WebRTCManager(private val context: Context) {
    companion object {
        private const val TAG = "WebRTCManager"
        // Browsers only expose a track in ontrack's streams if it was added with a stream id
        private const val STREAM_ID = "octo4a"
    }

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    private val peerConnectionsById = ConcurrentHashMap<String, PeerConnection>()

    var onStreamActiveStatusChanged: ((Boolean) -> Unit)? = null
    private val activePeerIds = ConcurrentHashMap.newKeySet<String>()

    private fun updatePeerStatus(id: String, connected: Boolean) {
        val wasEmpty = activePeerIds.isEmpty()
        if (connected) {
            activePeerIds.add(id)
        } else {
            activePeerIds.remove(id)
            peerConnectionsById.remove(id)?.close()
        }
        val isEmpty = activePeerIds.isEmpty()
        if (wasEmpty && !isEmpty) {
            onStreamActiveStatusChanged?.invoke(true)
        } else if (!wasEmpty && isEmpty) {
            onStreamActiveStatusChanged?.invoke(false)
        }
    }

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()
        val options = PeerConnectionFactory.Options()

        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
            eglBase?.eglBaseContext,  /* enableIntelVp8Encoder */ true,  /* enableH264HighProfile */ true
        )
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()

        videoSource = factory?.createVideoSource(false)
        localVideoTrack = factory?.createVideoTrack("100", videoSource)
    }

    fun pushFrame(nv21: ByteArray, width: Int, height: Int, rotation: Int) {
        if (factory == null || activePeerIds.isEmpty()) return
        try {
            val buffer = NV21Buffer(nv21, width, height, null)
            val frame = VideoFrame(buffer, rotation, System.nanoTime())
            videoSource?.capturerObserver?.onFrameCaptured(frame)
            frame.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Tracks a peer's connection state and calls onGatheringComplete once its local
    // description has all ICE candidates, so it can be returned in one response.
    private fun peerObserver(id: String, onGatheringComplete: () -> Unit) = object : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            if (state == PeerConnection.IceConnectionState.CONNECTED) {
                updatePeerStatus(id, true)
            } else if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.CLOSED) {
                updatePeerStatus(id, false)
            }
        }
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) onGatheringComplete()
        }
        override fun onIceCandidate(p0: IceCandidate?) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
    }

    // SdpObserver for one create or set call. onSuccess gets the created description, or null after a set.
    private class SimpleSdpObserver(
        private val onFailure: (String?) -> Unit,
        private val onSuccess: (SessionDescription?) -> Unit
    ) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = onSuccess(sdp)
        override fun onSetSuccess() = onSuccess(null)
        override fun onCreateFailure(error: String?) = onFailure(error)
        override fun onSetFailure(error: String?) = onFailure(error)
    }

    // Creates an offer (server is the offerer, matching camera-streamer API).
    // Returns a pair of (id, offerSdp). Waits for ICE gathering to complete.
    suspend fun createOffer(): Pair<String, String> = suspendCoroutine { cont ->
        if (factory == null) {
            cont.resume(Pair("", ""))
            return@suspendCoroutine
        }

        val id = UUID.randomUUID().toString()
        val resumed = AtomicBoolean(false)

        val peerConnection = factory?.createPeerConnection(
            PeerConnection.RTCConfiguration(emptyList()),
            peerObserver(id) {
                if (resumed.compareAndSet(false, true)) {
                    val sdp = peerConnectionsById[id]?.localDescription?.description ?: ""
                    cont.resume(if (sdp.isNotEmpty()) Pair(id, sdp) else Pair("", ""))
                }
            }
        )

        if (peerConnection == null) {
            cont.resume(Pair("", ""))
            return@suspendCoroutine
        }

        peerConnection.addTrack(localVideoTrack)
        peerConnectionsById[id] = peerConnection

        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(offer: SessionDescription?) {
                peerConnection.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        // ICE gathering starts; wait for onIceGatheringChange(COMPLETE)
                    }
                    override fun onCreateFailure(p0: String?) {
                        if (resumed.compareAndSet(false, true)) cont.resume(Pair("", ""))
                    }
                    override fun onSetFailure(p0: String?) {
                        if (resumed.compareAndSet(false, true)) cont.resume(Pair("", ""))
                    }
                }, offer)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {
                if (resumed.compareAndSet(false, true)) cont.resume(Pair("", ""))
            }
            override fun onSetFailure(p0: String?) {
                if (resumed.compareAndSet(false, true)) cont.resume(Pair("", ""))
            }
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
    suspend fun answerOffer(offerSdp: String): Pair<String, String> = suspendCoroutine { cont ->
        if (factory == null) {
            cont.resume(Pair("", ""))
            return@suspendCoroutine
        }

        val id = UUID.randomUUID().toString()
        val resumed = AtomicBoolean(false)
        val fail: (String?) -> Unit = { error ->
            if (resumed.compareAndSet(false, true)) {
                Log.w(TAG, "Failed to answer WebRTC offer: $error")
                peerConnectionsById.remove(id)?.close()
                cont.resume(Pair("", ""))
            }
        }

        // Browsers offer Unified Plan SDP; this library defaults to Plan B
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val peerConnection = factory?.createPeerConnection(config, peerObserver(id) {
            if (resumed.compareAndSet(false, true)) {
                val sdp = peerConnectionsById[id]?.localDescription?.description ?: ""
                cont.resume(if (sdp.isNotEmpty()) Pair(id, sdp) else Pair("", ""))
            }
        })

        if (peerConnection == null) {
            fail("could not create peer connection")
            return@suspendCoroutine
        }
        peerConnectionsById[id] = peerConnection

        peerConnection.setRemoteDescription(SimpleSdpObserver(fail) {
            // Reuses the recvonly video transceiver from the offer; its audio one stays inactive
            peerConnection.addTrack(localVideoTrack, listOf(STREAM_ID))
            peerConnection.createAnswer(SimpleSdpObserver(fail) { answer ->
                if (answer == null) {
                    fail("empty answer")
                } else {
                    peerConnection.setLocalDescription(SimpleSdpObserver(fail) {
                        // ICE gathering starts; wait for onIceGatheringChange(COMPLETE)
                    }, answer)
                }
            }, MediaConstraints())
        }, SessionDescription(SessionDescription.Type.OFFER, preferH264(offerSdp)))
    }

    // Sets the client's answer SDP as remote description for the given peer connection id.
    suspend fun processAnswer(id: String, answerSdp: String): Boolean = suspendCoroutine { cont ->
        val peerConnection = peerConnectionsById[id]
        if (peerConnection == null) {
            cont.resume(false)
            return@suspendCoroutine
        }

        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { cont.resume(true) }
            override fun onCreateFailure(p0: String?) { cont.resume(false) }
            override fun onSetFailure(p0: String?) { cont.resume(false) }
        }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    // Adds a remote ICE candidate from the client to the given peer connection.
    fun addIceCandidate(id: String, candidate: IceCandidate) {
        peerConnectionsById[id]?.addIceCandidate(candidate)
    }
}
