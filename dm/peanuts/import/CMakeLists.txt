# libnximport — Peanuts Native's importer (nximport.h), plain C11. Two ways in:
#   - the app's native build adds this folder (android/app/src/main/cpp/CMakeLists.txt, one PEANUTS hook line, see HOOKS.md): target `nximport`,
#     a shared library libnximport.so with the JNI (dev.peanuts.importer.Native), 16 KB page aligned like the host's libraries;
#   - standalone on a PC for the tests: cmake -S dm/peanuts/import -B build && cmake --build build → nximport_cli (and libnximport.so).
cmake_minimum_required(VERSION 3.22)
project(nximport C)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(NXIMPORT_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_crypto.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_nca.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nx_icon.c
    ${CMAKE_CURRENT_SOURCE_DIR}/nximport.c)
if(ANDROID)
  add_library(nximport SHARED ${NXIMPORT_SOURCES} ${CMAKE_CURRENT_SOURCE_DIR}/nximport_jni.c)
  target_link_options(nximport PRIVATE -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
  target_link_libraries(nximport PRIVATE log)
else()
  add_library(nximport SHARED ${NXIMPORT_SOURCES})
  add_executable(nximport_cli ${CMAKE_CURRENT_SOURCE_DIR}/nximport_cli.c ${NXIMPORT_SOURCES})   # the sources themselves: its ncainfo command uses the internals
  target_include_directories(nximport_cli PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
  target_compile_options(nximport_cli PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation)
endif()
target_include_directories(nximport PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(nximport PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-misleading-indentation -fvisibility=hidden)
# exported: the nximport.h API (NXIMPORT_API) and the JNI (JNIEXPORT); the nx_* internals stay hidden
