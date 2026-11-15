# libnximport — Peanuts Native's importer (nximport.h), plain C11. Two ways in:
#   - the app's native build adds this folder (android/app/src/main/cpp/CMakeLists.txt, one PEANUTS hook line, see HOOKS.md): target `nximport`,
#     a shared library libnximport.so with the JNI (dev.peanuts.importer.Native), 16 KB page aligned like the host's libraries;
#   - standalone on a PC for the tests: cmake -S dm/peanuts/import -B build && cmake --build build → nximport_cli (and libnximport.so).
#   - standalone on Windows with MinGW-w64 GCC (MSYS2 UCRT64 or WinLibs): cmake -G "MinGW Makefiles" -S dm/peanuts/import -B build; MSVC is not supported.
#     No libnximport there (its hidden-visibility export has no use on Windows): nximport_cli, comp_test and zip_test, linked -static so the .exe runs alone.
#     Needs zlib with its static library there too (MSYS2: mingw-w64-ucrt-x86_64-zlib).
cmake_minimum_required(VERSION 3.22)
project(nximport C)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(NXIMPORT_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_crypto.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_lz4.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_nca.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_icon.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_zip.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nximport.c)
if(ANDROID)
  add_library(nximport SHARED ${NXIMPORT_SOURCES} ${CMAKE_CURRENT_SOURCE_DIR}/nximport_jni.c)
  target_link_options(nximport PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
  target_link_libraries(nximport PRIVATE log z)   # z: zlib, for a deflated member of a firmware .zip (nx_zip.c / nximport.c font_try_zip_deflate); the NDK ships it
else()
  find_package(Threads REQUIRED)   # the writer's pipeline (pthreads; bionic has them in libc)
  find_package(ZLIB REQUIRED)      # a deflated member of a firmware .zip (nximport.c font_try_zip_deflate)
  if(NOT WIN32)
    add_library(nximport SHARED ${NXIMPORT_SOURCES})
    target_link_libraries(nximport PRIVATE Threads::Threads ZLIB::ZLIB)
  endif()
  add_executable(nximport_cli ${CMAKE_CURRENT_SOURCE_DIR}/nximport_cli.c ${NXIMPORT_SOURCES})   # the sources themselves: its ncainfo command uses the internals
  target_include_directories(nximport_cli PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_link_libraries(nximport_cli PRIVATE Threads::Threads ZLIB::ZLIB)
  target_compile_options(nximport_cli PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation)
  add_executable(comp_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/comp_test.c ${NXIMPORT_SOURCES})   # the compression layer against the reference LZ4 (tests/comp_check.py)
  target_include_directories(comp_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_link_libraries(comp_test PRIVATE Threads::Threads ZLIB::ZLIB)
  target_compile_options(comp_test PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation)
  add_executable(zip_test ${CMAKE_CURRENT_SOURCE_DIR}/tests/zip_test.c ${CMAKE_CURRENT_SOURCE_DIR}/nx_zip.c ${CMAKE_CURRENT_SOURCE_DIR}/nx_crypto.c ${CMAKE_CURRENT_SOURCE_DIR}/nx_nca.c ${CMAKE_CURRENT_SOURCE_DIR}/nx_lz4.c)   # nx_zip.c against a synthetic fixture (tests/make_zip_fixture.py); the rest only for nx_err/nx_pread_all (nx_nca.c references nx_lz4_decompress even unused here), no keys needed
  target_include_directories(zip_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_link_libraries(zip_test PRIVATE ZLIB::ZLIB)
  target_compile_options(zip_test PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation)
  if(WIN32)
    # MinGW's DLLs (libgcc, winpthread) and zlib are linked in, so the .exe files run from any folder (zlib's static library must be installed: MSYS2's zlib package has it)
    target_link_options(nximport_cli PRIVATE -static)
    target_link_options(comp_test PRIVATE -static)
    target_link_options(zip_test PRIVATE -static)
  endif()
endif()
if(TARGET nximport)
  target_include_directories(nximport PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
  target_compile_options(nximport PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation -fvisibility=hidden)
endif()
# exported: the nximport.h API (NXIMPORT_API) and the JNI (JNIEXPORT); the nx_* internals stay hidden
