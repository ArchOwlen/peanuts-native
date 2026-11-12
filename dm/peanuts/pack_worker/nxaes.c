// _nxaes: AES ECB decryption (128- or 256-bit keys) for the on-device packer's Python, which has no OpenSSL (the official Android CPython's _hashlib needs a
// libcrypto_python.so the APK does not carry) and no CommonCrypto. dm/gfx/tools/uepak.py aes_decrypt uses it off the Mac: Unreal pak indexes are AES-256 ECB
// encrypted with a key from main, which find_key looks for by trying main's 32-byte windows (Octopath Traveler 0: 116,287 of them), one block each.
//   _nxaes.ecb_decrypt(key: bytes (16 or 32), data: bytes (a multiple of 16)) -> bytes
// Portable C (the byte-wise cipher of FIPS-197), no tables beyond the two S-boxes; the pak index is a few MB, so speed is not the point.
// Build: dm/peanuts/pack_worker/build.sh (into <stage>/python/lib/python3.14/lib-dynload); the same source builds on Linux against the system Python for tests.
#define PY_SSIZE_T_CLEAN
#include <Python.h>
#include <stdint.h>
#include <string.h>

static const uint8_t SBOX[256] = {
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
    0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
    0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
    0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
    0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
    0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
    0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
    0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16};
static uint8_t INV[256], M9[256], M11[256], M13[256], M14[256];   // the inverse S-box and InvMixColumns' products, made at import

static uint8_t xt(uint8_t x) { return (uint8_t)((x << 1) ^ ((x >> 7) * 0x1b)); }
static uint8_t mul(uint8_t x, uint8_t y) {   // GF(2^8) product
    uint8_t r = 0;
    while (y) { if (y & 1) r ^= x; x = xt(x); y >>= 1; }
    return r;
}

typedef struct { int nr; uint8_t rk[240]; } Key;

static void expand(Key *k, const uint8_t *key, int nk) {   // the encryption key schedule, FIPS-197 5.2: (nr + 1) * 16 bytes
    k->nr = nk + 6;
    int words = 4 * (k->nr + 1);
    memcpy(k->rk, key, (size_t)nk * 4);
    uint8_t rcon = 1;
    for (int i = nk; i < words; i++) {
        uint8_t t[4]; memcpy(t, k->rk + 4 * (i - 1), 4);
        if (i % nk == 0) {
            uint8_t u = t[0]; t[0] = (uint8_t)(SBOX[t[1]] ^ rcon); t[1] = SBOX[t[2]]; t[2] = SBOX[t[3]]; t[3] = SBOX[u];
            rcon = xt(rcon);
        } else if (nk > 6 && i % nk == 4) {
            for (int j = 0; j < 4; j++) t[j] = SBOX[t[j]];
        }
        for (int j = 0; j < 4; j++) k->rk[4 * i + j] = (uint8_t)(k->rk[4 * (i - nk) + j] ^ t[j]);
    }
}

static void decrypt_block(const Key *k, const uint8_t *in, uint8_t *out) {   // the inverse cipher, FIPS-197 5.3; state column-major as the bytes come
    uint8_t s[16], t[16];
    for (int i = 0; i < 16; i++) s[i] = (uint8_t)(in[i] ^ k->rk[16 * k->nr + i]);
    for (int r = k->nr - 1; r >= 0; r--) {
        for (int c = 0; c < 4; c++) for (int row = 0; row < 4; row++) t[4 * ((c + row) & 3) + row] = INV[s[4 * c + row]];   // InvShiftRows + InvSubBytes
        for (int i = 0; i < 16; i++) t[i] ^= k->rk[16 * r + i];
        if (r == 0) { memcpy(s, t, 16); break; }
        for (int c = 0; c < 4; c++) {   // InvMixColumns
            uint8_t *a = t + 4 * c;
            s[4 * c + 0] = (uint8_t)(M14[a[0]] ^ M11[a[1]] ^ M13[a[2]] ^ M9[a[3]]);
            s[4 * c + 1] = (uint8_t)(M9[a[0]] ^ M14[a[1]] ^ M11[a[2]] ^ M13[a[3]]);
            s[4 * c + 2] = (uint8_t)(M13[a[0]] ^ M9[a[1]] ^ M14[a[2]] ^ M11[a[3]]);
            s[4 * c + 3] = (uint8_t)(M11[a[0]] ^ M13[a[1]] ^ M9[a[2]] ^ M14[a[3]]);
        }
    }
    memcpy(out, s, 16);
}

static PyObject *ecb_decrypt(PyObject *self, PyObject *args) {
    (void)self;
    Py_buffer key, data;
    if (!PyArg_ParseTuple(args, "y*y*", &key, &data)) return NULL;
    PyObject *res = NULL;
    if (key.len != 16 && key.len != 32) PyErr_SetString(PyExc_ValueError, "AES key must be 16 or 32 bytes");
    else if (data.len % 16) PyErr_SetString(PyExc_ValueError, "AES ECB data must be a multiple of 16 bytes");
    else if ((res = PyBytes_FromStringAndSize(NULL, data.len))) {
        Key k; expand(&k, key.buf, (int)key.len / 4);
        const uint8_t *in = data.buf; uint8_t *out = (uint8_t *)PyBytes_AS_STRING(res);
        Py_BEGIN_ALLOW_THREADS
        for (Py_ssize_t o = 0; o < data.len; o += 16) decrypt_block(&k, in + o, out + o);
        Py_END_ALLOW_THREADS
    }
    PyBuffer_Release(&key); PyBuffer_Release(&data);
    return res;
}

static PyMethodDef METHODS[] = {{"ecb_decrypt", ecb_decrypt, METH_VARARGS, "ecb_decrypt(key, data) -> bytes: AES-128/256 ECB decryption"}, {NULL, NULL, 0, NULL}};
static struct PyModuleDef MOD = {PyModuleDef_HEAD_INIT, "_nxaes", NULL, -1, METHODS, NULL, NULL, NULL, NULL};

PyMODINIT_FUNC PyInit__nxaes(void) {
    for (int i = 0; i < 256; i++) {
        INV[SBOX[i]] = (uint8_t)i;
        M9[i] = mul((uint8_t)i, 9); M11[i] = mul((uint8_t)i, 11); M13[i] = mul((uint8_t)i, 13); M14[i] = mul((uint8_t)i, 14);
    }
    return PyModule_Create(&MOD);
}
