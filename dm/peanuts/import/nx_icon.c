// libnximport: the NACP icon (JPEG) → PNG with stb_image / stb_image_write (dm/peanuts/third_party/stb, public domain / MIT). Kept in its own file so the
// stb implementations are compiled once, static, and never clash with the host's or peanuts.c's copies.
#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_STATIC
#define STBI_ONLY_JPEG
#define STBI_NO_STDIO
#include "../third_party/stb/stb_image.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STB_IMAGE_WRITE_STATIC
#include "../third_party/stb/stb_image_write.h"
#include "nx_internal.h"

int nx_jpeg_to_png(const uint8_t *jpg, size_t n, const char *out_png) {
    int w, h, comp;
    unsigned char *px = stbi_load_from_memory(jpg, (int)n, &w, &h, &comp, 3);
    if (!px) { nx_err("icon: not a JPEG the decoder reads (%s)", stbi_failure_reason()); return -1; }
    int ok = stbi_write_png(out_png, w, h, 3, px, w * 3);
    stbi_image_free(px);
    if (!ok) { nx_err("icon: cannot write %s", out_png); return -1; }
    return 0;
}
