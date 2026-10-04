package com.octo4a.camera

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

class NativeCameraUtils {
    init {
        System.loadLibrary("yuv2rgb")
    }

    external fun yuv420toNv21(
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rotation: Int,
        yByteBuffer: ByteBuffer?,
        uByteBuffer: ByteBuffer?,
        vByteBuffer: ByteBuffer?,
        uvPixelStride: Int,
        yRowStride: Int,
        uvRowStride: Int,
        nv21Output: ByteArray?
    ): Boolean

    // Converts the crop area of the image to NV21 in one pass, turned clockwise by rotation (0, 90,
    // 180 or 270), into nv21, which must hold at least the crop area. Handles interleaved and
    // planar chroma. Crop offsets and size must be even.
    fun toNv21(image: ImageProxy, crop: Rect, rotation: Int, nv21: ByteArray): Boolean =
        yuv420toNv21(
            crop.left,
            crop.top,
            crop.width(),
            crop.height(),
            rotation,
            image.planes[0].buffer,  // Y buffer
            image.planes[1].buffer,  // U buffer
            image.planes[2].buffer,  // V buffer
            image.planes[1].pixelStride,  // U/V pixel stride
            image.planes[0].rowStride,  // Y row stride
            image.planes[1].rowStride,  // U/V row stride
            nv21
        )
}
