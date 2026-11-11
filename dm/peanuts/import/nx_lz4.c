// LZ4 block decoder for libnximport (the compressed NCA sections' entries, nx_nca.c). The block format only, no frame:
// sequences of a token (literal length in the high nibble, match length - 4 in the low one; 15 = more bytes follow, each added, until one is not 255),
// the literals, a 2-byte little-endian match offset (1..65535, into what was decoded) and the match length's extra bytes; the last sequence is literals only.
#include "nx_internal.h"
#include <string.h>

long nx_lz4_decompress(const uint8_t *src, size_t slen, uint8_t *dst, size_t dlen) {
    size_t ip = 0, op = 0;
    while (ip < slen) {
        uint8_t tok = src[ip++]; size_t lit = tok >> 4, ml = tok & 15;
        if (lit == 15) { uint8_t b; do { if (ip >= slen) return -1; b = src[ip++]; lit += b; } while (b == 255); }
        if (lit > slen - ip || lit > dlen - op) return -1;
        memcpy(dst + op, src + ip, lit); ip += lit; op += lit;
        if (ip == slen) break;   // the block ends with literals
        if (ip + 2 > slen) return -1;
        size_t off = (size_t)src[ip] | (size_t)src[ip + 1] << 8; ip += 2;
        if (!off || off > op) return -1;
        if (ml == 15) { uint8_t b; do { if (ip >= slen) return -1; b = src[ip++]; ml += b; } while (b == 255); }
        ml += 4;
        if (ml > dlen - op) return -1;
        const uint8_t *m = dst + op - off;
        if (off >= ml) memcpy(dst + op, m, ml);
        else for (size_t i = 0; i < ml; i++) dst[op + i] = m[i];   // overlapping: a repeated pattern of off bytes
        op += ml;
    }
    return (long)op;
}
