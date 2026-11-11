# peanuts_rt: the Eden Duo DSMod companion runtime lifted out of the emulator (dm/peanuts/rt/mods,
# original file names) behind a set of shim headers (dm/peanuts/rt/shim) and a tiny C API
# (peanuts_rt.h). Builds as a static library for Linux hosts and arm64 Android (NDK toolchain).
#
#   host:  cmake -S dm/peanuts/rt -B build-host -DPEANUTS_RT_TEST=ON && cmake --build build-host
#   arm64: cmake -S dm/peanuts/rt -B build-arm64 -DCMAKE_TOOLCHAIN_FILE=<ndk>/build/cmake/android.toolchain.cmake \
#          -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-33 -DPEANUTS_RT_TEST=OFF
cmake_minimum_required(VERSION 3.22)
project(peanuts_rt LANGUAGES C CXX)

option(PEANUTS_RT_TEST "Build the Linux host test program rt_test" OFF)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE RelWithDebInfo)
endif()

set(PNRT_DIR ${CMAKE_CURRENT_SOURCE_DIR})
set(PNRT_THIRD_PARTY ${PNRT_DIR}/../third_party)

# The lifted files include each other as "core/mods/<name>" (unchanged Eden paths). Rather than a
# symlink in the tree, a generated include root maps core/mods -> dm/peanuts/rt/mods.
set(PNRT_INC_ROOT ${CMAKE_CURRENT_BINARY_DIR}/pnrt_include)
file(MAKE_DIRECTORY ${PNRT_INC_ROOT}/core)
file(CREATE_LINK ${PNRT_DIR}/mods ${PNRT_INC_ROOT}/core/mods SYMBOLIC)

set(PNRT_LIFTED
  mods/mod_actions.cpp
  mods/mod_assets.cpp
  mods/mod_input.cpp
  mods/mod_load_plan.cpp
  mods/mod_manifest.cpp
  mods/mod_map.cpp
  mods/mod_msbt.cpp
  mods/mod_nx_assets.cpp
  mods/mod_nx_runtime.cpp
  mods/mod_pages.cpp
  mods/mod_redraw.cpp
  mods/mod_runtime.cpp
  mods/mod_state.cpp
  mods/mod_ui.cpp
  mods/mod_ui_canvas.cpp
  mods/mod_ui_expand.cpp
  mods/mod_ui_image.cpp
  mods/mod_ui_map_widget.cpp
  mods/mod_ui_scroll.cpp
  mods/mod_ui_text.cpp
  mods/mod_ui_transitions.cpp
  mods/mod_ui_widget_state.cpp
)

add_library(peanuts_rt STATIC
  ${PNRT_LIFTED}
  mods/pnrt_stubs.cpp
  shim/shim.cpp
  shim/stb_impl.cpp
  ${PNRT_THIRD_PARTY}/bc_decoder/bc_decoder.cpp
  ${PNRT_THIRD_PARTY}/astc/video_core/textures/astc.cpp
  peanuts_rt.cpp
)
target_compile_features(peanuts_rt PUBLIC cxx_std_20)
target_include_directories(peanuts_rt
  PUBLIC ${PNRT_DIR}
  PRIVATE ${PNRT_DIR}/shim ${PNRT_INC_ROOT} ${PNRT_THIRD_PARTY} ${PNRT_THIRD_PARTY}/stb
          ${PNRT_THIRD_PARTY}/bc_decoder ${PNRT_THIRD_PARTY}/astc)
target_compile_definitions(peanuts_rt PRIVATE PNRT=1 EDEN_DSMOD_BUILD_DEV_TOOLS=0)
target_compile_options(peanuts_rt PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden
  -Wall -Wno-unused-parameter -Wno-unused-function -Wno-unused-variable -Wno-unused-but-set-variable
  -Wno-missing-field-initializers)
find_package(Threads REQUIRED)
target_link_libraries(peanuts_rt PUBLIC Threads::Threads)
find_package(ZLIB)
if(ZLIB_FOUND)
  target_link_libraries(peanuts_rt PUBLIC ZLIB::ZLIB)
endif()
if(ANDROID)
  target_link_libraries(peanuts_rt PUBLIC log)
  # The NDK's libc++ keeps std::jthread / std::stop_token (the redraw worker) experimental; Eden's
  # own Android build sets the same flag (eden/CMakeLists.txt).
  target_compile_options(peanuts_rt PUBLIC -fexperimental-library)
  target_link_options(peanuts_rt PUBLIC -fexperimental-library)
endif()

if(PEANUTS_RT_TEST)
  add_executable(rt_test test/rt_test.cpp)
  target_link_libraries(rt_test PRIVATE peanuts_rt)
  target_include_directories(rt_test PRIVATE ${PNRT_THIRD_PARTY}/stb ${PNRT_THIRD_PARTY})
endif()
