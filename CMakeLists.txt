cmake_minimum_required(VERSION 3.18)
project(win_fg CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

# ---- self-identifying build stamp -------------------------------------------
# CI passes -DWINFG_GIT_DESCRIBE=<git describe>; fall back to a local git describe,
# then to the VERSION file, so every build (CI or local) embeds an identifiable
# string. Recover it from a shipped .so with:
#     strings libwin_fg.so | grep win-fg-build
#     nm -D libwin_fg.so   | grep win_fg_build_version
if(NOT WINFG_GIT_DESCRIBE)
  execute_process(
    COMMAND git describe --tags --always --dirty --long
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
    OUTPUT_VARIABLE WINFG_GIT_DESCRIBE
    OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
endif()
if(NOT WINFG_GIT_DESCRIBE)
  file(STRINGS ${CMAKE_SOURCE_DIR}/VERSION _winfg_ver LIMIT_COUNT 1)
  set(WINFG_GIT_DESCRIBE "v${_winfg_ver}-nogit")
endif()
string(TIMESTAMP WINFG_BUILD_DATE "%Y-%m-%dT%H:%M:%SZ" UTC)
message(STATUS "win-fg build stamp: ${WINFG_GIT_DESCRIBE} (${WINFG_BUILD_DATE})")
configure_file(${CMAKE_SOURCE_DIR}/src/version_stamp.cpp.in
               ${CMAKE_BINARY_DIR}/version_stamp.cpp @ONLY)

# VULKAN_INCLUDE points at a Vulkan-Headers include/ (vk_layer.h + vulkan.h).
add_library(win_fg SHARED src/layer.cpp src/framegen.cpp
            ${CMAKE_BINARY_DIR}/version_stamp.cpp)
target_include_directories(win_fg PRIVATE src ${VULKAN_INCLUDE})
target_compile_definitions(win_fg PRIVATE VK_NO_PROTOTYPES=1 VK_USE_PLATFORM_ANDROID_KHR)
set_target_properties(win_fg PROPERTIES OUTPUT_NAME win_fg)
if(ANDROID)
  find_library(log-lib log)
  target_link_libraries(win_fg ${log-lib})

  # No-root screen-capture overlay. This is a standalone Vulkan renderer that
  # reuses FrameGen directly (no Vulkan layer injection), so it can run in this
  # app's own process on an unrooted device.
  add_library(winfg_overlay SHARED src/overlay_renderer.cpp src/framegen.cpp
              ${CMAKE_BINARY_DIR}/version_stamp.cpp)
  target_include_directories(winfg_overlay PRIVATE src ${VULKAN_INCLUDE})
  target_compile_definitions(winfg_overlay PRIVATE VK_USE_PLATFORM_ANDROID_KHR)
  set_target_properties(winfg_overlay PROPERTIES OUTPUT_NAME winfg_overlay)
  find_library(android-lib android)
  find_library(nativewindow-lib nativewindow)
  find_library(vulkan-lib vulkan)
  find_library(log-lib log)
  find_library(dl-lib dl)
  target_link_libraries(winfg_overlay ${android-lib} ${nativewindow-lib}
                        ${vulkan-lib} ${dl-lib} ${log-lib})

  # ---- ZeroFG (Apache-2.0) — the frame-generation engine ---------------------
  # ZeroFG records the generated frame into a command buffer we own; it never
  # submits, waits or presents, so it drops into this overlay's existing capture
  # path. Shaders ship prebuilt, so the build needs only the Vulkan headers.
  set(ZEROFG_VULKAN_HEADERS_DIR "${VULKAN_INCLUDE}" CACHE PATH "" FORCE)
  set(ZEROFG_PREBUILT_SHADERS "ON" CACHE STRING "" FORCE)
  add_subdirectory(third_party/zerofg ${CMAKE_BINARY_DIR}/zerofg)
  target_compile_features(winfg_overlay PRIVATE cxx_std_20)
  target_include_directories(winfg_overlay PRIVATE third_party/zerofg/include)
  target_link_libraries(winfg_overlay zerofg::zerofg)
endif()
