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

// Where row out_row of a plane turned clockwise by rotation starts in the unturned plane, and
// how far apart that row's pixels are there. width and height are the unturned plane's.
struct RowWalk {
  int start;
  int step;
};

RowWalk walkRow(int rotation, int out_row, int width, int height, int row_stride,
                int pixel_stride) {
  switch (rotation) {
    case 90:
      // Column out_row, bottom to top
      return {(height - 1) * row_stride + out_row * pixel_stride, -row_stride};
    case 180:
      // Rows bottom to top, each right to left
      return {(height - 1 - out_row) * row_stride + (width - 1) * pixel_stride, -pixel_stride};
    case 270:
      // Columns right to left, each top to bottom
      return {(width - 1 - out_row) * pixel_stride, row_stride};
    default:
      return {out_row * row_stride, pixel_stride};
  }
}

// Copies the crop area of a YUV_420_888 image into nv21 as an NV21 frame turned clockwise by
// rotation (0, 90, 180 or 270), so a frame needs no full-size copy or separate rotation pass.
// A quarter turn swaps the output's width and height. Y pixel stride is always 1; the crop
// offsets and size must be even, since chroma covers 2x2 pixel blocks.
void yuv420toNv21(int crop_left, int crop_top, int crop_width, int crop_height, int rotation,
                  const int8_t* y_buffer, const int8_t* u_buffer, const int8_t* v_buffer,
                  int uv_pixel_stride, int y_row_stride, int uv_row_stride, int8_t *nv21) {
  bool quarter_turn = rotation == 90 || rotation == 270;
  int out_width = quarter_turn ? crop_height : crop_width;
  int out_height = quarter_turn ? crop_width : crop_height;

  const int8_t* y_crop = y_buffer + crop_top * y_row_stride + crop_left;
  for (int y = 0; y < out_height; ++y) {
    RowWalk walk = walkRow(rotation, y, crop_width, crop_height, y_row_stride, 1);
    int8_t* out = nv21 + out_width * y;
    if (walk.step == 1) {
      memcpy(out, y_crop + walk.start, out_width);
      continue;
    }
    const int8_t* in = y_crop + walk.start;
    for (int x = 0; x < out_width; ++x, in += walk.step) {
      out[x] = *in;
    }
  }

  int8_t* vu = nv21 + out_width * out_height;
  int uv_crop = (crop_top / 2) * uv_row_stride + (crop_left / 2) * uv_pixel_stride;
  // Many cameras already lay chroma out as NV21, with U one byte after V, so unturned rows copy
  // whole
  bool interleaved_vu = uv_pixel_stride == 2 && u_buffer == v_buffer + 1;
  for (int y = 0; y < out_height / 2; ++y) {
    RowWalk walk = walkRow(rotation, y, crop_width / 2, crop_height / 2, uv_row_stride,
                           uv_pixel_stride);
    int in = uv_crop + walk.start;
    if (interleaved_vu && walk.step == 2) {
      memcpy(vu, v_buffer + in, out_width);
      vu += out_width;
      continue;
    }
    for (int x = 0; x < out_width / 2; ++x, in += walk.step) {
      // V channel.
      *vu++ = v_buffer[in];
      // U channel.
      *vu++ = u_buffer[in];
    }
  }
}

}  // namespace

extern "C" {

jboolean Java_com_octo4a_camera_NativeCameraUtils_yuv420toNv21(
    JNIEnv *env, jclass clazz,
    jint crop_left, jint crop_top, jint crop_width, jint crop_height, jint rotation,
    jobject y_byte_buffer, jobject u_byte_buffer, jobject v_byte_buffer,
    jint uv_pixel_stride, jint y_row_stride, jint uv_row_stride,
    jbyteArray nv21_array) {

    auto y_buffer = static_cast<jbyte*>(env->GetDirectBufferAddress(y_byte_buffer));
    auto u_buffer = static_cast<jbyte*>(env->GetDirectBufferAddress(u_byte_buffer));
    auto v_buffer = static_cast<jbyte*>(env->GetDirectBufferAddress(v_byte_buffer));
    if (y_buffer == nullptr || u_buffer == nullptr || v_buffer == nullptr
        || env->GetArrayLength(nv21_array) < crop_width * crop_height * 3 / 2
        || (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
        return false;
    }

    jbyte* nv21 = env->GetByteArrayElements(nv21_array, nullptr);
    if (nv21 == nullptr) {
        return false;
    }

    yuv420toNv21(crop_left, crop_top, crop_width, crop_height, rotation, y_buffer, u_buffer,
                 v_buffer, uv_pixel_stride, y_row_stride, uv_row_stride, nv21);

    env->ReleaseByteArrayElements(nv21_array, nv21, 0);
    return true;
}

}  // extern "C"