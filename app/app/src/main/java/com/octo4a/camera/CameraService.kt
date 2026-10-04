package com.octo4a.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.OnLifecycleEvent
import com.octo4a.repository.LoggerRepository
import com.octo4a.repository.OctoPrintHandlerRepository
import com.octo4a.utils.CancelableTimer
import com.octo4a.utils.WaitableEvent
import com.octo4a.utils.preferences.MainPreferences
import org.koin.android.ext.android.inject
import org.webrtc.IceCandidate
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


const val UNBIND_DELAY_MS: Long = 5 * 60 * 1000 // Unbind the camera after 5 minutes of no use
const val UNBIND_STREAM_DELAY_MS: Long = 10 * 1000 // Unbind streams faster to save CPU
const val SNAPSHOT_FLASH_DELAY_MS: Long = 1000 // How long to turn flash on before taking snapshot
const val JPEG_QUALITY: Int = 70
const val NV21_POOL_SIZE: Int = 2 // A frame holds at most two arrays: WebRTC's and MJPEG's

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class CameraService : LifecycleService(), MJpegFrameProvider {

  enum class InitState {
    NOT_INITIALIZED,
    INITIALIZING,
    INITIALIZED,
    UNINITIALIZING,
    FAILED
  }

  data class CompletableInitState(
      var state: InitState,
      val callback: ((CompletableInitState) -> Unit)? = null,
      val unbindDelayMs: Long = UNBIND_DELAY_MS,
      var refcnt: Int = 0,
      var waitEvent: WaitableEvent = WaitableEvent(),
      val unbindTimer: CancelableTimer = CancelableTimer(),
      var cameraControl: CameraControl? = null,
      var minFocalLength: Float? = null
  )

  private fun CompletableInitState.setState(newState: InitState) {
    state = newState
    callback?.invoke(this)
  }

  private val _cameraSettings: MainPreferences by inject()
  private val _logger: LoggerRepository by inject()

  // The latest MJPEG frame. Each new one wakes every waiting client.
  private val _frameLock = ReentrantLock()
  private val _newFrame = _frameLock.newCondition()
  private var _latestFrame = MJpegFrameProvider.FrameInfo(id = 1)

  private var _cameraProcessProvider: ProcessCameraProvider? = null
  private val _cameraBoundUseCases: MutableMap<UseCase, CompletableInitState> = HashMap()
  private var _fpsLimit: Int = -1
  private var _torchRefCnt = AtomicInteger(0)
  private val _mjpegListenerCnt = AtomicInteger(0)
  private val _webRTCHoldsCamera = AtomicBoolean(false)
  private val _octoprintHandler: OctoPrintHandlerRepository by inject()
  private val _cameraEnumerationRepository: CameraEnumerationRepository by inject()
  private val _captureExecutor by lazy { Executors.newCachedThreadPool() }
  private val nativeUtils by lazy { NativeCameraUtils() }

  private val _mjpegServer by lazy { MJpegServer(5001, this) }
  private val _webRTCManager by lazy { WebRTCManager(this) }
  private val _callbackExecutorPool by lazy { Executors.newCachedThreadPool() }
  private var _lastImageMilliseconds = System.currentTimeMillis()
  // Frame arrays are about a megabyte, and allocating two per frame kept the GC busy. WebRTC's
  // array comes back when libwebrtc releases the frame, usually before pushFrame returns.
  private val _nv21Pool = ArrayDeque<ByteArray>()
  // Kept across frames, so the JPEG encoder doesn't regrow a stream from 32 bytes every frame
  private val _jpegStream = ByteArrayOutputStream()

  fun getCameraMinFocalLength(): Float? {
    var minFocalLength: Float? = null
    _cameraBoundUseCases.entries
      .firstOrNull { it.value.cameraControl != null }
      ?.let { entry -> minFocalLength = entry.value.minFocalLength }
    _logger.log(this) { "Got min focal length : $minFocalLength" }
    return minFocalLength
  }

  private val _cameraSelector by lazy {
    CameraSelector.Builder()
        .apply {
          requireLensFacing(
              _cameraEnumerationRepository
                  .cameraWithId(_cameraSettings.selectedCamera!!)
                  ?.lensFacing ?: CameraSelector.LENS_FACING_FRONT)
        }
        .build()
  }

  private val _cameraPreview by lazy {
    val builder =
      Preview.Builder()
        .setTargetResolution(
          Size.parseSize(_cameraSettings.selectedVideoResolution ?: "1280x720"))
        .setTargetRotation(getSettingsRotation())

    val ret = builder.build()
    _cameraBoundUseCases[ret] =
      CompletableInitState(
        InitState.NOT_INITIALIZED,
        callback = ::torchControlCallback,
        unbindDelayMs = UNBIND_STREAM_DELAY_MS)
    ret
  }

  private val _imageCapture by lazy {
    val builder =
        ImageCapture.Builder()
            .setTargetResolution(Size.parseSize(_cameraSettings.selectedResolution ?: "1280x720"))
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(getSettingsRotation())
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
    val ret = builder.build()

    _cameraBoundUseCases[ret] =
        CompletableInitState(
            InitState.NOT_INITIALIZED,
            callback = ::torchControlCallback,
            unbindDelayMs = UNBIND_DELAY_MS)

    ret
  }

  @Suppress("RestrictedApi")
  private val _imageAnalysis by lazy {
    val builder =
        ImageAnalysis.Builder()
            .setTargetResolution(analysisTargetResolution())
            .setTargetRotation(getSettingsRotation())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    // CameraX 1.0.0 caps analysis at 1920x1080 by default, which turned a 1280x720 setting on
    // quarter-turned frames into a 1080x606 stream. Lift the cap to the camera's largest size;
    // CameraX still picks the smallest size that covers the target.
    _cameraEnumerationRepository.cameraWithId(_cameraSettings.selectedCamera!!)
        ?.sizes?.maxByOrNull { it.width * it.height }
        ?.let { builder.setMaxResolution(Size(it.width, it.height)) }

    val ret = builder.build()
    _cameraBoundUseCases[ret] =
        CompletableInitState(
            InitState.NOT_INITIALIZED,
            callback = ::torchControlCallback,
            unbindDelayMs = UNBIND_STREAM_DELAY_MS)
    ret.setAnalyzer(_callbackExecutorPool, ::analyzeFrame)

    ret
  }

  private fun getCameraControl(): CameraControl? {
    var cameraControl: CameraControl? = null
    _cameraBoundUseCases.entries
        .firstOrNull { it.value.cameraControl != null }
        ?.let { entry -> cameraControl = entry.value.cameraControl }
    _logger.log(this) { "Got camera control : $cameraControl" }
    return cameraControl
  }

  private fun addTorchUser(cameraControl: CameraControl? = null) {
      val refCnt = _torchRefCnt.incrementAndGet()
      if (refCnt == 1) {
        _logger.log(this) { "Torch now has users" }
      }
      if (refCnt > 0 ) {
        if (_cameraSettings.flashWhenObserved) {
          (cameraControl ?: getCameraControl())?.enableTorch(true)
        }
      }
  }

  private fun removeTorchUser(cameraControl: CameraControl? = null) {
      if (_torchRefCnt.get() > 0) {
        _torchRefCnt.decrementAndGet()
      } else {
        _logger.log(this) { "Excessive removeTorchUser calls!" }
      }
      if (_torchRefCnt.get() == 0) {
        if (_cameraSettings.flashWhenObserved) {
          (cameraControl ?: getCameraControl())?.enableTorch(false)
        }
        _logger.log(this) { "Torch has no more users" }
      }
  }

  private fun torchControlCallback(initState: CompletableInitState): Unit {
    when (initState.state) {
      InitState.INITIALIZED -> {
        addTorchUser(initState.cameraControl)
      }
      InitState.UNINITIALIZING -> {
        removeTorchUser(initState.cameraControl)
      }
      else -> {}
    }
  }

  private fun getSettingsRotation(): Int {
    val currentRotation = _cameraSettings.imageRotation?.toIntOrNull() ?: -1
    return when (currentRotation) {
      90 -> Surface.ROTATION_90
      180 -> Surface.ROTATION_180
      270 -> Surface.ROTATION_270
      else -> Surface.ROTATION_0
    }
  }

  // Upright size of the stream frames (MJPEG and WebRTC)
  private val _videoResolution by lazy {
    Size.parseSize(_cameraSettings.selectedVideoResolution ?: "1280x720")
  }

  // Whether analysis frames need a quarter turn to be upright, as on a portrait phone with a
  // landscape sensor. CameraX then reads a requested size as upright, so a landscape request
  // asks for a portrait stream, and CameraX picks shape over size: on the Redmi Note 4 that
  // turned 1024x768 into a 480x640 stream. Mirrors isRotationNeeded in CameraX 1.0.0's
  // SupportedSurfaceCombination.
  private fun framesNeedQuarterTurn(): Boolean {
    val sensorOrientation = kotlin.runCatching {
      val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
      cameraManager.getCameraCharacteristics(_cameraSettings.selectedCamera!!)
          .get(CameraCharacteristics.SENSOR_ORIENTATION)
    }.getOrNull() ?: return false
    val targetDegrees = when (getSettingsRotation()) {
      Surface.ROTATION_90 -> 90
      Surface.ROTATION_180 -> 180
      Surface.ROTATION_270 -> 270
      else -> 0
    }
    // Front and back cameras turn opposite ways, but both make a quarter turn or neither does
    return (sensorOrientation + targetDegrees) % 180 != 0
  }

  // What to ask CameraX for so frames can be cropped to the video resolution. A landscape
  // resolution on quarter-turned frames needs an upright portrait frame at least that wide,
  // and CameraX picks the smallest sensor size that covers the request.
  private fun analysisTargetResolution(): Size {
    val video = _videoResolution
    return if (video.width > video.height && framesNeedQuarterTurn()) {
      Size(video.width, video.width * video.width / video.height)
    } else {
      video
    }
  }

  // The centered area of a frame that, once turned upright, has the video resolution, or the
  // largest area of that shape the frame holds
  private fun videoCrop(width: Int, height: Int, rotation: Int): Rect {
    val quarterTurn = rotation == 90 || rotation == 270
    // The video resolution in the frame's own orientation
    val wantWidth = if (quarterTurn) _videoResolution.height else _videoResolution.width
    val wantHeight = if (quarterTurn) _videoResolution.width else _videoResolution.height
    val scale = minOf(1.0, width.toDouble() / wantWidth, height.toDouble() / wantHeight)
    // NV21 stores color per 2x2 pixel block, so sizes and offsets must be even
    val cropWidth = (wantWidth * scale).toInt() and 1.inv()
    val cropHeight = (wantHeight * scale).toInt() and 1.inv()
    val left = ((width - cropWidth) / 2) and 1.inv()
    val top = ((height - cropHeight) / 2) and 1.inv()
    return Rect(left, top, left + cropWidth, top + cropHeight)
  }

  private fun getBestAvailFps(): Int {
    val targetFps = _cameraSettings.fpsLimit?.toIntOrNull() ?: -1
    val availableFps =
        _cameraEnumerationRepository.cameraWithId(_cameraSettings.selectedCamera!!)?.frameRates
    val bestFps = availableFps?.firstOrNull { it >= targetFps } ?: -1
    return bestFps
  }

  private fun toBitmap(buffer: ByteBuffer, rotation: Float = 0f): Bitmap {
    val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
    var matrix: Matrix? = null
    if (rotation != 0f) {
      matrix = Matrix().apply { postRotate(rotation) }
    }
    val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
  }

  // Takes a frame array of the given size from the pool, or allocates one
  private fun takeNv21(size: Int): ByteArray =
      synchronized(_nv21Pool) { _nv21Pool.removeFirstOrNull() }?.takeIf { it.size == size }
          ?: ByteArray(size)

  // Returns a frame array to the pool once nothing reads it any more
  private fun giveNv21(nv21: ByteArray) {
    synchronized(_nv21Pool) {
      if (_nv21Pool.size < NV21_POOL_SIZE) _nv21Pool.addLast(nv21)
    }
  }

  private fun compressNv21(
      nv21: ByteArray,
      width: Int,
      height: Int,
      quality: Int = JPEG_QUALITY
  ): ByteArray =
      synchronized(_jpegStream) {
        _jpegStream.reset()
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, _jpegStream)
        _jpegStream.toByteArray()
      }

  private fun setNextFrame(image: ByteArray) {
    _frameLock.withLock {
      _latestFrame = MJpegFrameProvider.FrameInfo(image, _latestFrame.id + 1)
      _newFrame.signalAll()
    }
  }

  // A new client (no prevFrame) waits for the next frame too: frames are only encoded while
  // someone watches MJPEG, so the latest one can be from long ago
  override fun getNewFrame(prevFrame: MJpegFrameProvider.FrameInfo?): MJpegFrameProvider.FrameInfo =
      _frameLock.withLock {
        val lastId = prevFrame?.id ?: _latestFrame.id
        while (_latestFrame.id <= lastId) {
          _newFrame.await()
        }
        _latestFrame
      }

  inner class LocalBinder : Binder() {
    fun getService(): CameraService = this@CameraService
  }

  override suspend fun createWebRTCOffer(): Pair<String, String> {
      return _webRTCManager.createOffer()
  }

  override suspend fun answerWebRTCOffer(offerSdp: String): Pair<String, String> {
      return _webRTCManager.answerOffer(offerSdp)
  }

  override suspend fun processWebRTCAnswer(id: String, answerSdp: String): Boolean {
      return _webRTCManager.processAnswer(id, answerSdp)
  }

  override fun addWebRTCIceCandidate(id: String, sdpMid: String?, sdpMLineIndex: Int, sdpCandidate: String) {
      _webRTCManager.addIceCandidate(id, IceCandidate(sdpMid, sdpMLineIndex, sdpCandidate))
  }

  private val binder = LocalBinder()

  override suspend fun takeSnapshot(): ByteArray = suspendCoroutine {
    _logger.log(this) { "Received takeSnapshot request" }
    if (!initUseCase(_imageCapture, block = true)) {
      it.resume(ByteArray(0))
      return@suspendCoroutine
    }

    Handler(Looper.getMainLooper())
        .postDelayed(
            {
              _imageCapture.takePicture(
                  _captureExecutor,
                  object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                      _logger.log(this) { "Snapshot Capture Success" }
                      val bitmap =
                          toBitmap(
                              image.planes[0].buffer,
                              image.getImageInfo().getRotationDegrees().toFloat())
                      val compressedStream = ByteArrayOutputStream()
                      bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, compressedStream)
                      it.resume(compressedStream.toByteArray())
                      super.onCaptureSuccess(image)
                      image.close()
                      deinitUseCase(_imageCapture)
                    }

                    override fun onError(exception: ImageCaptureException) {
                      _logger.log(this) { "Failed to capture image: $exception" }
                      deinitUseCase(_imageCapture)
                      it.resume(ByteArray(0))
                      super.onError(exception)
                    }
                  })
            },
            SNAPSHOT_FLASH_DELAY_MS)
  }

  private fun sleepToLimitFps() {
    // Roughly limit fps to user's chosen value
    if (_fpsLimit > 0) {
      val timeDiff = System.currentTimeMillis() - _lastImageMilliseconds
      _lastImageMilliseconds = System.currentTimeMillis()
      val targetFrameTime = 1000 / _fpsLimit
      if (timeDiff < targetFrameTime) {
        Thread.sleep(targetFrameTime - timeDiff)
      }
    }
  }

  private fun analyzeFrame(image: ImageProxy) {
    try {
      sendFrame(image)
    } catch (e: Exception) {
      // CameraX doesn't catch what an analyzer throws, so it would kill the app
      e.printStackTrace()
    } finally {
      // The next frame only arrives once this one is closed
      image.close()
    }
    sleepToLimitFps()
  }

  // Converts a camera frame for the streams someone is watching
  private fun sendFrame(image: ImageProxy) {
    val toWebRTC = _webRTCManager.isStreaming
    // The JPEG encode is the expensive part, so skip it when only WebRTC is watching
    val toMjpeg = _mjpegListenerCnt.get() > 0
    // Frames keep coming while the camera waits to unbind
    if (!toWebRTC && !toMjpeg) return

    val rotation: Int = image.imageInfo.rotationDegrees
    val crop = videoCrop(image.width, image.height, rotation)
    val frameSize = crop.width() * crop.height() * 3 / 2
    // The native conversion copies only the crop area out of the camera buffers. WebRTC turns
    // frames upright from the stream's metadata, so it gets them as captured.
    if (toWebRTC) {
      val nv21 = takeNv21(frameSize)
      if (nativeUtils.toNv21(image, crop, 0, nv21)) {
        _webRTCManager.pushFrame(nv21, crop.width(), crop.height(), rotation) { giveNv21(nv21) }
      } else {
        giveNv21(nv21)
      }
    }

    if (toMjpeg) {
      // Turned upright during the conversion
      val upright = takeNv21(frameSize)
      if (nativeUtils.toNv21(image, crop, rotation, upright)) {
        val quarterTurn = rotation == 90 || rotation == 270
        setNextFrame(
            compressNv21(
                upright,
                if (quarterTurn) crop.height() else crop.width(),
                if (quarterTurn) crop.width() else crop.height()))
      }
      giveNv21(upright)
    }
  }

  override fun registerListener(): Boolean {
    _logger.log(this) { "Camera server register stream listener" }
    if (!initUseCase(_imageAnalysis, block = true)) {
      return false
    }
    _mjpegListenerCnt.incrementAndGet()
    return true
  }

  override fun unregisterListener() {
    _logger.log(this) { "Camera server unregister stream listener" }
    _mjpegListenerCnt.decrementAndGet()
    deinitUseCase(_imageAnalysis)
  }

  override fun onBind(intent: Intent): IBinder {
    super.onBind(intent)
    return binder
  }

  override fun onDestroy() {
    super.onDestroy()
    Thread { _mjpegServer.stopServer() }.start()
    _webRTCManager.release()
    _octoprintHandler.isCameraServerRunning = false
  }

  fun hookPreviewLifecycleObserver(lifecycleOwner: LifecycleOwner) {
    // Bind to preview destruction via the passed in lifecycleowner
    lifecycleOwner
      .lifecycle
      .addObserver(
        object : LifecycleObserver {
          @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
          fun onStop() {
            _logger.log(this) { "Preview has stopped" }
            deinitUseCase(_cameraPreview)
            lifecycleOwner.lifecycle.removeObserver(this)
          }
        })
  }

  @SuppressLint("UnsafeExperimentalUsageError", "RestrictedApi")
  fun updateCameraParameters() {
    getCameraControl()?.apply {
      val control = Camera2CameraControl.from(this)
      val ext = CaptureRequestOptions.Builder()
      if (_cameraSettings.manualAF) {
        ext.setCaptureRequestOption(
          CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        ext.setCaptureRequestOption(
          CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        ext.setCaptureRequestOption(
          CaptureRequest.LENS_FOCUS_DISTANCE, _cameraSettings.manualAFValue)
      }
      else {
        ext.setCaptureRequestOption(
          CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
      }

      if (_fpsLimit > 0) {
        ext.setCaptureRequestOption(
          CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range<Int>(_fpsLimit, _fpsLimit))
      }

      control.setCaptureRequestOptions(ext.build())
    }
  }
  fun getPreview(): Preview? {
    if (!initUseCase(_cameraPreview, block = true)) {
      return null
    }


    return _cameraPreview
  }

  override fun onCreate() {
    super.onCreate()
    initCameraProvider()
    _webRTCManager.init()
    // Bind the camera directly: registerListener() would count WebRTC as an MJPEG reader.
    // Only release a reference we got, or a failed bind would release an MJPEG client's.
    _webRTCManager.onStreamActiveStatusChanged = { active ->
      if (active) {
        _webRTCHoldsCamera.set(initUseCase(_imageAnalysis, block = true))
      } else if (_webRTCHoldsCamera.getAndSet(false)) {
        deinitUseCase(_imageAnalysis)
      }
    }
  }

  @SuppressLint("UnsafeExperimentalUsageError")
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    _fpsLimit = _cameraSettings.fpsLimit?.toIntOrNull() ?: -1
    Thread { _mjpegServer.startServer() }.start()
    _octoprintHandler.isCameraServerRunning = true
    return START_STICKY
  }

  private fun initCameraProvider() {
    val isMainThread = Looper.getMainLooper().thread == Thread.currentThread()
    assert(isMainThread)
    if (_cameraProcessProvider != null) {
      return
    }

    if (!checkForCamera()) {
      return
    }

    val providerFuture = ProcessCameraProvider.getInstance(applicationContext)
    providerFuture.addListener(
        {
          try {
            _cameraProcessProvider = providerFuture.get()
            _cameraProcessProvider?.unbindAll()
            _logger.log(this) { "Camera initialized" }
          } catch (e: Exception) {
            _logger.log(this) { "Failed to bind to camera: $e" }
          }
        },
        ContextCompat.getMainExecutor(applicationContext))
  }

  private fun initUseCase(useCase: UseCase, block: Boolean = false): Boolean {
    if (_cameraProcessProvider == null) {
      _logger.log(this) { "Can't init use case $useCase, camera not initialized!" }
      return false
    }
    var waitForInitNeeded = false
    var initState: CompletableInitState
    synchronized(_cameraBoundUseCases) {
      if (!_cameraBoundUseCases.contains(useCase)) {
        _cameraBoundUseCases[useCase] = CompletableInitState(InitState.NOT_INITIALIZED)
      }
      initState = _cameraBoundUseCases[useCase]!!
    }
    _logger.log(this) { "Entering initUseCase: [$useCase]  [$initState]" }
    synchronized(initState) {
      if (initState.state == InitState.UNINITIALIZING) {
        initState.unbindTimer.cancel()
        initState.setState(InitState.INITIALIZED)
      }
      if (initState.state == InitState.INITIALIZED) {
        initState.refcnt += 1
        return true
      }
      if (initState.state == InitState.INITIALIZING && block) {
        waitForInitNeeded = true
      }
      if (initState.state == InitState.NOT_INITIALIZED) {
        initState.setState(InitState.INITIALIZING)
      }
    }
    if (waitForInitNeeded) {
      // Another caller is binding. Start over once it's done, so this caller takes its own
      // reference; returning without one would let that caller's deinit unbind under this one.
      initState.waitEvent.wait()
      return initUseCase(useCase, block)
    }
    if (initState.state != InitState.INITIALIZING) {
      return (initState.state == InitState.INITIALIZED)
    }

    @SuppressLint("UnsafeExperimentalUsageError", "RestrictedApi")
    fun bindCamera() {
      _logger.log(this) { "bind camera called"}
      var camera: Camera?
      try {
        camera = _cameraProcessProvider?.bindToLifecycle(this, _cameraSelector, useCase)

        synchronized(initState) {
          initState.refcnt += 1
          initState.cameraControl = camera?.cameraControl
          initState.setState(InitState.INITIALIZED)
          initState.minFocalLength = null
          updateCameraParameters()

          try {
            val cameraInfo = camera?.cameraInfo!!
            val camChars = Camera2CameraInfo.extractCameraCharacteristics(cameraInfo)
            val minFocus = camChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            _logger.log(this) { "Min camera length focus $minFocus" }
            initState.minFocalLength = minFocus!!
          } catch (e: Exception) {
            _logger.log(this) { "Failed to get min focus length: $e" }
          }
        }
      } catch (e: Exception) {
        initState.setState(InitState.FAILED)
        _logger.log(this) { "Failed to bind camera: $e" }
      } finally {
        initState.waitEvent.set()
      }
    }

    val handler = Handler(Looper.getMainLooper())
    if (block) {
      val isMainThread = Looper.getMainLooper().thread == Thread.currentThread()
      if (isMainThread) {
        bindCamera()
      } else {
        /* Block while main loop binds */
        handler.post { bindCamera() }
        initState.waitEvent.wait()
      }
    } else {
      handler.post { bindCamera() }
    }
    return (initState.state == InitState.INITIALIZED ||
        (!block && initState.state == InitState.INITIALIZING))
  }

  private fun deinitUseCase(useCase: UseCase) {
    var initState: CompletableInitState
    synchronized(_cameraBoundUseCases) {
      if (!_cameraBoundUseCases.contains(useCase)) {
        _cameraBoundUseCases[useCase] = CompletableInitState(InitState.NOT_INITIALIZED)
      }

      initState = _cameraBoundUseCases[useCase]!!
      if (initState.refcnt == 0) return
    }
    synchronized(initState) {
      _logger.log(this) { "Entering deinitUseCase [$useCase] : [$initState]" }
      assert(initState.refcnt > 0)
      initState.refcnt -= 1
      if (initState.refcnt == 0) {
        initState.setState(InitState.UNINITIALIZING)
        initState.unbindTimer.start(
            initState.unbindDelayMs
        ) {
          synchronized(initState) {
            if (initState.state == InitState.UNINITIALIZING) {
              _cameraProcessProvider?.unbind(useCase)
              initState.cameraControl = null
              initState.setState(InitState.NOT_INITIALIZED)
              initState.waitEvent.reset()

              _logger.log(this) {
                "Unbound use case $useCase after unreferenced for ${initState.unbindDelayMs} ms"
              }
            }
          }
        }
      }
    }
  }

  private fun checkForCamera(): Boolean {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
        PackageManager.PERMISSION_GRANTED) {
      return false
    }
    // ensure that the cameras are enumerated
    _cameraEnumerationRepository.enumerateCameras()
    // check if the device has any cameras at all
    // some TV boxes have no cameras at all, and it causes cameraSelector to throw an exception
    if (_cameraEnumerationRepository.enumeratedCameras.value?.isEmpty() != false) {
      return false
    }
    return true
  }
}
