// nxpack_worker: `python3 <flags> -c <code> <args>` for a process that has no python executable (the on-device packer, android/ONDEVICE.md: CPython 3.14
// embedded in the app). shader_split.py's pool workers run it (dm/gfx/tools/split_pool.py, DM_SPLIT_WORKER), on the phone through /system/bin/linker64 (an app
// may not execute a file of its own storage, Android 10+; PackService sets the command) with the APK's libpython3.14.so on LD_LIBRARY_PATH (…/base.apk!/lib/arm64-v8a:
// the APK's libraries are not extracted). The Python home is the folder above this program's (<home>/bin/nxpack_worker, as build.sh stages it into python/bin,
// which PackNative unpacks with the rest of the Python home), or NXPACK_PYHOME. Python then parses the arguments itself (isolated, as dm_pack.c starts it) and runs -c.
// Build: dm/peanuts/pack_worker/build.sh (NDK clang, against the stage's libpython3.14.so); the same source builds on Linux against the system Python for tests.
#include <Python.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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
