// PEANUTS: a Peanuts test file (the feeder ring in hle_audio.c, DM_AUDIO_FEED)
// The Android output path of hle_audio.c on Linux (stubbed AAudio): a six-channel AudioOut played through the stereo device callback, and the feeder ring that keeps a game with
// a shallow queue (3 blocks of 256 frames) from underrunning a 960-frame burst (Octopath Traveler 0, issue #9). The "game" is a thread that gives each released block back at once.
#include <stdbool.h>
#include "../hle.h"
// the take-overs' trampolines are arm64 assembly and a section table: on Linux they are plain functions nothing calls
#undef HLE_FORCE
#undef HLE_FORCE_BIG
#define HLE_FORCE(mangled, why, ret, cname, ...) static ret cname(__VA_ARGS__) __attribute__((unused)); static ret cname(__VA_ARGS__)
#define HLE_FORCE_BIG HLE_FORCE
plat_sem_t *plat_sem_create(long v) { (void)v; return NULL; }   // the release notify and host outputs, not reached
void plat_sem_signal(plat_sem_t *s) { (void)s; }
int plat_sem_wait_ns(plat_sem_t *s, int64_t ns) { (void)s; (void)ns; return -1; }
void plat_sem_destroy(plat_sem_t *s) { (void)s; }
void *dm_bigstack_caller(void *ra) { return ra; }
#include "../hle_audio.c"
#include <assert.h>
#include <time.h>
__thread _Atomic int dm_host_work;
uint64_t now_ns(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (uint64_t)t.tv_sec * 1000000000ull + t.tv_nsec; }
void *objmap_get(const void *k) { (void)k; return NULL; }
void objmap_put(const void *k, void *v) { (void)k; (void)v; }
void *objmap_del(const void *k) { (void)k; return NULL; }
void hle_signal_system_event(void *g) { (void)g; }
void hle_bind_system_event(void *g, int ac) { (void)g; (void)ac; }
module_t *module_of(const void *p) { (void)p; return NULL; }
int dm_guest_as_contains(uintptr_t a) { (void)a; return 0; }
void dm_host_helper_qos(plat_qos_t q) { (void)q; }

// the NDK's calls the stream's open and close reach; no test starts a stream
aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **b) { (void)b; return -1; }
const char *AAudio_convertResultToText(aaudio_result_t r) { (void)r; return "stub"; }
const char *AAudio_convertStreamStateToText(aaudio_stream_state_t s) { (void)s; return "stub"; }
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *b, int v) { (void)b; (void)v; }
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *b, int v) { (void)b; (void)v; }
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *b, int v) { (void)b; (void)v; }
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *b, int v) { (void)b; (void)v; }
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *b, int v) { (void)b; (void)v; }
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *b, int v) { (void)b; (void)v; }
void AAudioStreamBuilder_setUsage(AAudioStreamBuilder *b, int v) { (void)b; (void)v; }
void AAudioStreamBuilder_setContentType(AAudioStreamBuilder *b, int v) { (void)b; (void)v; }
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *b, AAudioStream_dataCallback f, void *c) { (void)b; (void)f; (void)c; }
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *b, AAudioStream_errorCallback f, void *c) { (void)b; (void)f; (void)c; }
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *b, AAudioStream **s) { (void)b; (void)s; return -1; }
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *b) { (void)b; return 0; }
aaudio_result_t AAudioStream_close(AAudioStream *s) { (void)s; return 0; }
aaudio_result_t AAudioStream_requestStart(AAudioStream *s) { (void)s; return 0; }
aaudio_result_t AAudioStream_requestStop(AAudioStream *s) { (void)s; return 0; }
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream *s) { (void)s; return 0; }
int32_t AAudioStream_getDeviceId(AAudioStream *s) { (void)s; return 0; }
int32_t AAudioStream_getFramesPerBurst(AAudioStream *s) { (void)s; return 0; }
int32_t AAudioStream_getPerformanceMode(AAudioStream *s) { (void)s; return 0; }
int32_t AAudioStream_getSampleRate(AAudioStream *s) { (void)s; return 0; }
int32_t AAudioStream_getSharingMode(AAudioStream *s) { (void)s; return 0; }
aaudio_stream_state_t AAudioStream_getState(AAudioStream *s) { (void)s; return 0; }
aaudio_result_t AAudioStream_setBufferSizeInFrames(AAudioStream *s, int32_t n) { (void)s; return n; }
aaudio_result_t AAudioStream_waitForStateChange(AAudioStream *s, aaudio_stream_state_t i, aaudio_stream_state_t *o, int64_t t) { (void)s; (void)i; (void)o; (void)t; return -1; }

enum { NB = 3, BF = 256, BURST = 960 };
static int16_t pcm[NB][BF * 6];
static gbuf blk[NB];
static aout A; static struct aa_out O;
static _Atomic int game_run;
static void *game(void *p) {   // as the game's audio thread: a released block goes straight back to the pending queue
    (void)p;
    while (atomic_load(&game_run)) {
        uint32_t rh = atomic_load(&A.rel_head), rt = atomic_load(&A.rel_tail);
        if (rh == rt) { usleep(200); continue; }
        gbuf *b = A.rel[rh & (RELMAX - 1)]; atomic_store(&A.rel_head, rh + 1);
        uint32_t qt = atomic_load(&A.q_tail); A.q[qt & (QMAX - 1)] = b; atomic_store(&A.q_tail, qt + 1);
    }
    return NULL;
}
static void setup(int feed) {
    memset(&A, 0, sizeof A); memset(&O, 0, sizeof O);
    A.ch = 6; atomic_init(&A.state, STATE_STARTED); atomic_init(&A.vol, 0x3f800000u);
    for (int i = 0; i < NB; i++) {
        for (int f = 0; f < BF; f++) { int16_t *s = pcm[i] + f * 6; memset(s, 0, 12); s[0] = 10000; s[2] = 10000; }   // front left and center: left 10000 + 7070, right 7070
        blk[i] = (gbuf){pcm[i], sizeof pcm[i], sizeof pcm[i]}; A.q[i] = &blk[i];
    }
    atomic_init(&A.q_tail, NB);
    O.a = &A; O.ch = 2; O.rate = RATE; atomic_init(&O.lost, 0);
    if (feed) { O.ring = malloc((size_t)FEED_RING * 4); O.target = BURST + RATE / 200; }
}
static unsigned run(int feed, int *bad_samples) {
    setup(feed); atomic_store(&game_run, 1); pthread_t g; pthread_create(&g, NULL, game, NULL);
    aa_feed_start(&O);
    int16_t out[BURST * 2]; *bad_samples = 0;
    for (int c = 0; c < 25; c++) {
        memset(out, 0x55, sizeof out);
        aa_render(NULL, &O, out, BURST);
        if (c) for (int f = 0; f < BURST; f++) if (out[f * 2] != 10000 + 7070 || out[f * 2 + 1] != 7070) (*bad_samples)++;
        usleep(20000);
    }
    aa_feed_stop(&O); atomic_store(&game_run, 0); pthread_join(g, NULL); free(O.ring);
    return (unsigned)atomic_load(&A.under_n);
}
int main(void) {
    // one callback, no ring: a 6-channel block reaches the stereo device whole and in order (the frame stride is the guest's 12 bytes, the device's 4), the rest is silence
    setup(0); int16_t out[BURST * 2]; memset(out, 0x55, sizeof out);
    aa_render(NULL, &O, out, BURST);
    for (int f = 0; f < NB * BF; f++) assert(out[f * 2] == 10000 + 7070 && out[f * 2 + 1] == 7070);
    for (int f = NB * BF; f < BURST; f++) assert(out[f * 2] == 0 && out[f * 2 + 1] == 0);
    assert(atomic_load(&A.under_n) >= 1 && atomic_load(&A.under_b) == (BURST - NB * BF) * 12);   // the shortfall in the guest's 6-channel bytes (one underrun per 128-frame pull that found the queue empty)
    assert(atomic_load(&A.rel_tail) == NB);
    int bad; unsigned plain = run(0, &bad), fed = run(1, &bad);
    printf("audio feed: burst of %d frames, %d-frame blocks queued x%d: %u underruns without the ring, %u with it (%d wrong samples)\n", BURST, BF, NB, plain, fed, bad);
    fflush(stdout); assert(plain >= 20);   // a callback comes up short each time: the shallow queue, as in the report
    assert(fed <= 1 && bad == 0);   // the ring is primed before the stream starts; every sample is the downmix
    puts("audio feed: six-channel blocks through the stereo path, ring, and underrun accounting passed");
    return 0;
}
