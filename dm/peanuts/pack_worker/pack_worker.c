// nxpack_worker: `python3 <flags> -c <code> <args>` for a process that has no python executable (the on-device packer, android/ONDEVICE.md: CPython 3.14
// embedded in the app). shader_split.py's pool workers run it (dm/gfx/tools/split_pool.py, DM_SPLIT_WORKER), on the phone through /system/bin/linker64 (an app
// may not execute a file of its own storage, Android 10+; PackService sets the command) with the APK's libpython3.14.so on LD_LIBRARY_PATH (…/base.apk!/lib/arm64-v8a:
// the APK's libraries are not extracted). The Python home is the folder above this program's (<home>/bin/nxpack_worker, as build.sh stages it into python/bin,
// which PackNative unpacks with the rest of the Python home), or NXPACK_PYHOME. Python then parses the arguments itself (isolated, as dm_pack.c starts it) and runs -c.
// Build: dm/peanuts/pack_worker/build.sh (NDK clang, against the stage's libpython3.14.so); the same source builds on Linux against the system Python for tests.
#ifndef _GNU_SOURCE
#define _GNU_SOURCE   // sched_setaffinity (the game-yield watch below)
#endif
#include <Python.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef __ANDROID__
#include <dirent.h>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>

// While a game of this app runs (its ":game" process), a worker gives the game the fast cores: it moves itself to the little cores (NXPACK_YIELD_CPUS,
// default 0-2, the Snapdragon 8 Gen 2's Cortex-A510s) at the lowest priority, and back to every core when the game is gone. Checked every 2 s.
// Without this a build running beside a game took the four fast cores at ~95 % each (Animal Crossing fell from 30 to 18 fps, the SoC at 95 °C).
static int game_running(void) {
    DIR *d = opendir("/proc"); if (!d) return 0;
    struct dirent *e; char path[64], cmd[128]; int found = 0;
    while (!found && (e = readdir(d))) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        snprintf(path, sizeof path, "/proc/%s/cmdline", e->d_name);
        FILE *f = fopen(path, "r"); if (!f) continue;
        size_t n = fread(cmd, 1, sizeof cmd - 1, f); fclose(f); cmd[n] = 0;
        size_t l = strlen(cmd);
        if (l > 5 && !strcmp(cmd + l - 5, ":game") && strstr(cmd, "dev.nxnative.android")) found = 1;
    }
    closedir(d);
    return found;
}
static void *yield_watch(void *arg) {
    (void)arg;
    int lo = 0, hi = 2, yielded = -1;
    const char *e = getenv("NXPACK_YIELD_CPUS");
    if (e && sscanf(e, "%d-%d", &lo, &hi) != 2) { lo = 0; hi = 2; }
    for (;;) {
        int g = game_running();
        if (g != yielded) {
            cpu_set_t set; CPU_ZERO(&set);
            long ncpu = sysconf(_SC_NPROCESSORS_CONF);
            for (long c = 0; c < ncpu && c < CPU_SETSIZE; c++) if (!g || (c >= lo && c <= hi)) CPU_SET(c, &set);
            sched_setaffinity(0, sizeof set, &set);   // the whole process: every thread (pid 0 = this thread; the others follow below)
            DIR *t = opendir("/proc/self/task");
            // the nice value is per thread on Linux, so each thread gets it too; an app cannot go back below its nice later (the restore to 0 may fail:
            // the affinity is what matters, and it does come back)
            if (t) { struct dirent *te; while ((te = readdir(t))) if (te->d_name[0] >= '0' && te->d_name[0] <= '9') { sched_setaffinity(atoi(te->d_name), sizeof set, &set); setpriority(PRIO_PROCESS, atoi(te->d_name), g ? 19 : 0); } closedir(t); }
            yielded = g;
        }
        sleep(2);
    }
    return NULL;
}
#endif

static const char *home_of(const char *argv0, char *buf) {
    const char *env = getenv("NXPACK_PYHOME");
    if (env && *env) return env;
    if (!realpath(argv0, buf)) return NULL;
    for (int up = 0; up < 2; up++) {   // <home>/bin/nxpack_worker → <home>
        char *s = strrchr(buf, '/');
        if (!s || s == buf) return NULL;
        *s = 0;
    }
    return buf;
}

int main(int argc, char **argv) {
    char buf[PATH_MAX];
    const char *home = home_of(argv[0], buf);
    if (!home) { fprintf(stderr, "[RED] nxpack_worker: no Python home (%s is not <home>/bin/nxpack_worker, and NXPACK_PYHOME is not set)\n", argv[0]); return 1; }
#ifdef __ANDROID__
    { pthread_t t; if (!pthread_create(&t, NULL, yield_watch, NULL)) pthread_detach(t); }   // give a running game the fast cores
#endif
    PyConfig c; PyStatus st;
    PyConfig_InitIsolatedConfig(&c);   // no PYTHON* variables, no user site, no current folder on sys.path: as the packer's own interpreter (dm_pack.c)
    c.site_import = 1;                 // site-packages (capstone, lz4)
    c.parse_argv = 1;                  // -I -c <code> <args> as python3 takes them: sys.argv = ['-c', <args>...]
    c.install_signal_handlers = 1;     // a process of its own: SIGINT and SIGPIPE as python3 has them
    st = PyConfig_SetBytesString(&c, &c.home, home);
    if (!PyStatus_Exception(st)) st = PyConfig_SetBytesArgv(&c, argc, argv);
    if (!PyStatus_Exception(st)) st = Py_InitializeFromConfig(&c);
    PyConfig_Clear(&c);
    if (PyStatus_Exception(st)) {
        if (PyStatus_IsExit(st)) return st.exitcode;
        fprintf(stderr, "[RED] nxpack_worker: Python did not start (home %s): %s\n", home, st.err_msg ? st.err_msg : "?");
        return 1;
    }
    return Py_RunMain();
}
