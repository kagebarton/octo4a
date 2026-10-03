#include <string.h>
#include <jni.h>
#include <string>
#include <stdio.h>
#include <sys/select.h>
#include <sys/types.h>
#include <stddef.h>
#include <sys/wait.h>
#include <unistd.h>
#include <stdlib.h>

namespace {

// Copies the crop area of a YUV_420_888 image into nv21 as a crop_width x crop_height NV21
// frame, so a cropped frame needs no full-size copy first. Y pixel stride is always 1; the
// crop offsets and size must be even, since chroma covers 2x2 pixel blocks.
void yuv420toNv21(int crop_left, int crop_top, int crop_width, int crop_height,
                  const int8_t* y_buffer, const int8_t* u_buffer, const int8_t* v_buffer,
                  int uv_pixel_stride, int y_row_stride, int uv_row_stride, int8_t *nv21) {
  for (int y = 0; y < crop_height; ++y) {
    memcpy(nv21 + crop_width * y, y_buffer + (crop_top + y) * y_row_stride + crop_left, crop_width);
  }

  int8_t* vu = nv21 + crop_width * crop_height;
  int uv_left = crop_left / 2;
  int uv_top = crop_top / 2;
  int uv_width = crop_width / 2;
  int uv_height = crop_height / 2;
  // Many cameras already lay chroma out as NV21, with U one byte after V, so whole rows copy
  bool interleaved_vu = uv_pixel_stride == 2 && u_buffer == v_buffer + 1;
  for (int y = 0; y < uv_height; ++y) {
    int row = (uv_top + y) * uv_row_stride + uv_left * uv_pixel_stride;
    if (interleaved_vu) {
      memcpy(vu, v_buffer + row, crop_width);
      vu += crop_width;
      continue;
    }
    for (int x = 0; x < uv_width; ++x) {
      int bufferIndex = row + x * uv_pixel_stride;
      // V channel.
      *vu++ = v_buffer[bufferIndex];
      // U channel.
      *vu++ = u_buffer[bufferIndex];
    }
  }
}

}  // namespace

extern "C" {

jboolean Java_com_octo4a_camera_NativeCameraUtils_yuv420toNv21(
    JNIEnv *env, jclass clazz,
    jint crop_left, jint crop_top, jint crop_width, jint crop_height,
    jobject y_byte_buffer, jobject u_byte_buffer, jobject v_byte_buffer,
    jint uv_pixel_stride, jint y_row_stride, jint uv_row_stride,
    jbyteArray nv21_array) {

    auto y_buffer = static_cast<jbyte*>(env->GetDirectBufferAddress(y_byte_buffer));
    auto u_buffer = static_cast<jbyte*>(env->GetDirectBufferAddress(u_byte_buffer));
    auto v_buffer = static_cast<jbyte*>(env->GetDirectBufferAddress(v_byte_buffer));
    if (y_buffer == nullptr || u_buffer == nullptr || v_buffer == nullptr
        || env->GetArrayLength(nv21_array) < crop_width * crop_height * 3 / 2) {
        return false;
    }

    jbyte* nv21 = env->GetByteArrayElements(nv21_array, nullptr);
    if (nv21 == nullptr) {
        return false;
    }

    yuv420toNv21(crop_left, crop_top, crop_width, crop_height, y_buffer, u_buffer, v_buffer,
                 uv_pixel_stride, y_row_stride, uv_row_stride, nv21);

    env->ReleaseByteArrayElements(nv21_array, nv21, 0);
    return true;
}

}  // extern "C"