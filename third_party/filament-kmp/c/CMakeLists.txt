cmake_minimum_required(VERSION 3.19...3.28)

# --- Build options ---
# When ON, builds a single combined SHARED library (libfilament-c) that statically
# links all four C-wrapper source sets and the union of their Filament archives.
# This is the JVM/Panama path. Default OFF keeps the iOS static-only build untouched.
option(FILAMENT_BUILD_SHARED "Build the combined libfilament-c shared library for the JVM" OFF)

# Filament prebuilts are built with clang/libc++ — use the same toolchain on Linux
# for the SHARED build (mixing with libstdc++ fails to link). Must run before project().
if (FILAMENT_BUILD_SHARED AND UNIX AND NOT APPLE)
    find_program(CLANGPP clang++)
    if (CLANGPP)
        set(CMAKE_CXX_COMPILER ${CLANGPP})
    else()
        message(FATAL_ERROR "clang++ not found. Install it with: sudo apt install clang libc++-dev libc++abi-dev")
    endif()
endif()

# --- Platform specific setup ---
set(FILAMENT_PLATFORM "ios-simulator" CACHE STRING "Target platform (ios, ios-simulator)")
set(FILAMENT_ARCH "arm64" CACHE STRING "Target architecture (arm64, x64)")

# --- Platform specific setup ---
if (FILAMENT_PLATFORM STREQUAL "ios")
    set(CMAKE_SYSTEM_NAME iOS)
    set(CMAKE_OSX_SYSROOT iphoneos)
    set(CMAKE_OSX_ARCHITECTURES arm64)
    set(CMAKE_OSX_DEPLOYMENT_TARGET "15.0")
    set(FILAMENT_LIB_DIR_DEFAULT "${CMAKE_CURRENT_SOURCE_DIR}/../prebuilts/iosArm64/lib")
elseif (FILAMENT_PLATFORM STREQUAL "ios-simulator")
    set(CMAKE_SYSTEM_NAME iOS)
    set(CMAKE_OSX_SYSROOT iphonesimulator)
    set(CMAKE_OSX_DEPLOYMENT_TARGET "15.0")
    if (FILAMENT_ARCH STREQUAL "x64")
        set(CMAKE_OSX_ARCHITECTURES "x86_64")
        set(FILAMENT_LIB_DIR_DEFAULT "${CMAKE_CURRENT_SOURCE_DIR}/../prebuilts/iosX64/lib")
    else()
        set(CMAKE_OSX_ARCHITECTURES "arm64")
        set(FILAMENT_LIB_DIR_DEFAULT "${CMAKE_CURRENT_SOURCE_DIR}/../prebuilts/iosSimulatorArm64/lib")
    endif()
elseif (FILAMENT_BUILD_SHARED)
    # JVM/Panama host build (macos, linux, windows). The prebuilt dir name combines the
    # platform (windows → mingw) with the capitalised arch, e.g. macosArm64, linuxX64, mingwX64.
    if (FILAMENT_ARCH STREQUAL "x64" OR FILAMENT_ARCH STREQUAL "X64")
        set(_FILA_ARCH_DIR "X64")
    else()
        set(_FILA_ARCH_DIR "Arm64")
    endif()
    if (FILAMENT_PLATFORM STREQUAL "windows")
        set(_FILA_PLAT_DIR "mingw")
    else()
        set(_FILA_PLAT_DIR "${FILAMENT_PLATFORM}")
    endif()
    set(FILAMENT_LIB_DIR_DEFAULT "${CMAKE_CURRENT_SOURCE_DIR}/../prebuilts/${_FILA_PLAT_DIR}${_FILA_ARCH_DIR}/lib")
endif()

project(filament-c)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_C_STANDARD 11)

# Filament directory setup
if (NOT FILAMENT_DIST_DIR)
    set(FILAMENT_DIST_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../filament-main")
endif()

if (NOT FILAMENT_INCLUDE_DIR)
    set(FILAMENT_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../include")
endif()

if (NOT FILAMENT_LIB_DIR)
    set(FILAMENT_LIB_DIR "${FILAMENT_LIB_DIR_DEFAULT}")
endif()

# Platform detection
if (WIN32)
    set(LIB_PREFIX "")
    set(LIB_SUFFIX ".lib")
else()
    set(LIB_PREFIX "lib")
    set(LIB_SUFFIX ".a")
endif()

message(STATUS "Using Filament distribution at: ${FILAMENT_DIST_DIR}")
message(STATUS "Using Filament headers at: ${FILAMENT_INCLUDE_DIR}")
message(STATUS "Using Filament libraries at: ${FILAMENT_LIB_DIR}")

# Imported libraries helper. add_filament_library(NAME) maps target NAME to
# lib<NAME>.a; add_filament_library_as(TARGET FILE) handles the cases where the
# CMake target name differs from the archive filename (e.g. iblprefilter).
macro(add_filament_library_as TARGET FILE)
    add_library(${TARGET} STATIC IMPORTED)
    set_target_properties(${TARGET} PROPERTIES IMPORTED_LOCATION
            ${FILAMENT_LIB_DIR}/${LIB_PREFIX}${FILE}${LIB_SUFFIX})
endmacro()

macro(add_filament_library NAME)
    add_filament_library_as(${NAME} ${NAME})
endmacro()

add_filament_library(filament)
add_filament_library(backend)
add_filament_library(utils)
add_filament_library(geometry)
add_filament_library(ibl-lite)
add_filament_library(filamat)
add_filament_library(shaders)
add_filament_library_as(iblprefilter filament-iblprefilter)

add_filament_library(camutils)
add_filament_library(image)
add_filament_library(imageio-lite)
add_filament_library(filaflat)
add_filament_library(filabridge)
add_filament_library(zstd)
add_filament_library(smol-v)
add_filament_library(ktxreader)
add_filament_library(png)
add_filament_library(tinyexr)
add_filament_library(z)
add_filament_library(gltfio_core)
add_filament_library(dracodec)
add_filament_library(stb)

# The C-wrapper library
set(FILAMENT_C_SOURCES
    filament/cpp/Engine.cpp
    filament/cpp/Camera.cpp
    filament/cpp/Material.cpp
    filament/cpp/RenderableManager.cpp
    filament/cpp/TransformManager.cpp
    filament/cpp/LightManager.cpp
    filament/cpp/Renderer.cpp
    filament/cpp/View.cpp
    filament/cpp/Scene.cpp
    filament/cpp/SwapChain.cpp
    filament/cpp/VertexBuffer.cpp
    filament/cpp/IndexBuffer.cpp
    filament/cpp/BufferObject.cpp
    filament/cpp/Texture.cpp
    filament/cpp/RenderTarget.cpp
    filament/cpp/MorphTargetBuffer.cpp
    filament/cpp/SkinningBuffer.cpp
    filament/cpp/IndirectLight.cpp
    filament/cpp/Skybox.cpp
    filament/cpp/Stream.cpp
    filament/cpp/Fence.cpp
    filament/cpp/ToneMapper.cpp
    filament/cpp/ColorGrading.cpp
    filament/cpp/MaterialInstance.cpp
    filament/cpp/MathUtils.cpp
    filament/cpp/EntityManager.cpp
    filament/cpp/TextureSampler.cpp
    filament/cpp/Colors.cpp
    filament/cpp/SurfaceOrientation.cpp
)

add_library(filament-c STATIC ${FILAMENT_C_SOURCES})

target_include_directories(filament-c PUBLIC
    filament/c
)

target_include_directories(filament-c PRIVATE
    filament/cpp
    ${FILAMENT_INCLUDE_DIR}
)

target_link_libraries(filament-c
    PRIVATE filament
    PRIVATE backend
    PRIVATE utils
    PRIVATE geometry
    PRIVATE ibl-lite
    PRIVATE filaflat
    PRIVATE filabridge
    PRIVATE zstd
    PRIVATE smol-v
)

if (APPLE)
    if (FILAMENT_PLATFORM STREQUAL "ios" OR FILAMENT_PLATFORM STREQUAL "ios-simulator")
        target_link_libraries(filament-c PRIVATE
            "-framework Metal"
            "-framework Foundation"
            "-framework UIKit"
            "-framework QuartzCore"
            "-framework CoreVideo"
        )
    else()
        target_link_libraries(filament-c PRIVATE
            "-framework Metal"
            "-framework Foundation"
            "-framework AppKit"
            "-framework QuartzCore"
            "-framework CoreVideo"
        )
    endif()
endif()

# filamat-c library
set(FILAMAT_C_SOURCES
    filamat/cpp/MaterialBuilder.cpp
)

add_library(filamat-c STATIC ${FILAMAT_C_SOURCES})

target_link_libraries(filamat-c
    PRIVATE filamat
    PRIVATE filament
    PRIVATE backend
    PRIVATE filabridge
    PRIVATE shaders
    PRIVATE smol-v
    PRIVATE utils
)

target_include_directories(filamat-c PUBLIC
    filamat/c
    filament/c
)

target_include_directories(filamat-c PRIVATE
    filamat/cpp
    filament/cpp
    ${FILAMENT_INCLUDE_DIR}
)

# filament-utils-c library
set(FILAMENT_UTILS_C_SOURCES
    filament-utils/cpp/IBLPrefilter.cpp
    filament-utils/cpp/Manipulator.cpp
    filament-utils/cpp/HDRLoader.cpp
    filament-utils/cpp/KTX1Loader.cpp
    filament-utils/cpp/TextureLoader.cpp
)

add_library(filament-utils-c STATIC ${FILAMENT_UTILS_C_SOURCES})

target_link_libraries(filament-utils-c
    PRIVATE filament-c
    PRIVATE iblprefilter
    PRIVATE ktxreader
    PRIVATE camutils
    PRIVATE image
    PRIVATE imageio-lite
    PRIVATE zstd
    PRIVATE utils
    PRIVATE filaflat
    PRIVATE filabridge
)

target_include_directories(filament-utils-c PUBLIC
    filament-utils/c
    filament/c
)

target_include_directories(filament-utils-c PRIVATE
    filament-utils/cpp
    filament/cpp
    ${FILAMENT_INCLUDE_DIR}
)

# gltfio-c library
set(GLTFIO_C_SOURCES
    gltfio/cpp/AssetLoader.cpp
    gltfio/cpp/FilamentAsset.cpp
    gltfio/cpp/FilamentInstance.cpp
    gltfio/cpp/Animator.cpp
    gltfio/cpp/MaterialProvider.cpp
    gltfio/cpp/ResourceLoader.cpp
)

add_library(gltfio-c STATIC ${GLTFIO_C_SOURCES})

target_link_libraries(gltfio-c
    PRIVATE gltfio_core
    PRIVATE dracodec
    PRIVATE stb
    PRIVATE filament
    PRIVATE backend
    PRIVATE utils
    PRIVATE filaflat
    PRIVATE filabridge
    PRIVATE image
    PRIVATE imageio-lite
    PRIVATE ktxreader
    PRIVATE zstd
)

target_include_directories(gltfio-c PUBLIC
    gltfio/c
    filament/c
)

target_include_directories(gltfio-c PRIVATE
    gltfio/cpp
    filament/cpp
    ${FILAMENT_INCLUDE_DIR}
)

if (FILAMENT_SUPPORTS_VULKAN)
    add_definitions(-DFILAMENT_SUPPORTS_VULKAN=1)
    # Add bluevk/smol-v if vulkan is enabled
else()
    add_definitions(-DFILAMENT_SUPPORTS_VULKAN=0)
endif()

# ── Combined SHARED library for the JVM (Project Panama / FFM) ─────────────────
#
# One shared image links all four C-wrapper source sets and the *union* of their
# Filament static archives, each included exactly once. Shipping a single image
# (instead of four) keeps Filament's process-global singletons — notably
# EntityManager — to a single instance, the same reason java/CMakeLists.txt
# combines its JNI submodules into one libfilament-jni.
if (FILAMENT_BUILD_SHARED)
    # Extra archives the combined image needs beyond the four static targets' imports.
    # Mirrors the proven java/CMakeLists.txt (libfilament-jni) link set, which exercises
    # the same Filament APIs through a different wrapper.
    add_filament_library(meshoptimizer)
    add_filament_library(bluegl)
    add_filament_library(bluevk)
    add_filament_library(ibl)
    add_filament_library(filament-generatePrefilterMipmap)
    add_filament_library(gltfio)
    add_filament_library(uberzlib)
    add_filament_library(uberarchive)
    add_filament_library(basis_transcoder)

    add_library(filament-c-jvm SHARED
        ${FILAMENT_C_SOURCES}
        ${FILAMAT_C_SOURCES}
        ${FILAMENT_UTILS_C_SOURCES}
        ${GLTFIO_C_SOURCES}
    )

    set_target_properties(filament-c-jvm PROPERTIES OUTPUT_NAME "filament-c")

    target_include_directories(filament-c-jvm PRIVATE
        filament/c
        filamat/c
        filament-utils/c
        gltfio/c
        filament/cpp
        filamat/cpp
        filament-utils/cpp
        gltfio/cpp
        ${FILAMENT_INCLUDE_DIR}
    )

    # Union of every archive the combined image references, each listed once.
    # Note: full `ibl` (not ibl-lite) — the combined build pulls in gltfio/iblprefilter
    # IBL paths, matching the JNI build (doc §3.3).
    target_link_libraries(filament-c-jvm PRIVATE
        filament backend utils
        filaflat filabridge zstd smol-v
        bluegl bluevk ibl image geometry meshoptimizer
        camutils ktxreader filament-generatePrefilterMipmap
        filamat shaders
        gltfio gltfio_core uberzlib uberarchive dracodec stb basis_transcoder
        imageio-lite iblprefilter
    )

    if (APPLE)
        target_link_libraries(filament-c-jvm PRIVATE
            "-framework Cocoa"
            "-framework Metal"
            "-framework Foundation"
            "-framework AppKit"
            "-framework QuartzCore"
            "-framework CoreVideo"
            "-framework OpenGL"
        )
        # Seal: export only the Fila* C API; keep filament's C++ internals local
        # (smaller symbol table, no clashes with other libs in the host process).
        # With the seal in place, dead-strip drops code unreachable from the exports.
        target_link_options(filament-c-jvm PRIVATE
            "LINKER:-exported_symbol,_Fila*"
            "LINKER:-dead_strip")
    elseif (WIN32)
        # Seal: a DLL exports nothing by default; generate a .def of the Fila* C API from
        # the wrapper headers (the exact surface jextract binds — matches the macOS/Linux
        # seal) instead of WINDOWS_EXPORT_ALL_SYMBOLS' full C++ export table.
        file(GLOB _fila_api_headers CONFIGURE_DEPENDS
            "${CMAKE_CURRENT_SOURCE_DIR}/filament/c/*.h"
            "${CMAKE_CURRENT_SOURCE_DIR}/filamat/c/*.h"
            "${CMAKE_CURRENT_SOURCE_DIR}/filament-utils/c/*.h"
            "${CMAKE_CURRENT_SOURCE_DIR}/gltfio/c/*.h")
        set(_fila_exports "")
        foreach(_hdr IN LISTS _fila_api_headers)
            file(READ "${_hdr}" _hdr_text)
            # Function declarations only: Fila<Type>_<method>( not preceded by an
            # identifier char (skips typedefs/params — no '(' right after their name).
            string(REGEX MATCHALL "[^A-Za-z0-9_]Fila[A-Za-z0-9]+_[A-Za-z0-9_]+[ \t\r\n]*\\(" _matches "${_hdr_text}")
            foreach(_m IN LISTS _matches)
                string(REGEX REPLACE "^[^A-Za-z0-9_]" "" _m "${_m}")
                string(REGEX REPLACE "[ \t\r\n]*\\($" "" _m "${_m}")
                list(APPEND _fila_exports "${_m}")
            endforeach()
        endforeach()
        list(REMOVE_DUPLICATES _fila_exports)
        list(SORT _fila_exports)
        list(LENGTH _fila_exports _fila_export_count)
        if (_fila_export_count LESS 500)
            message(FATAL_ERROR "filament-c.def generation found only ${_fila_export_count} Fila* symbols — header scan is broken")
        endif()
        message(STATUS "filament-c.def: exporting ${_fila_export_count} Fila* symbols")
        list(JOIN _fila_exports "\n    " _fila_exports_text)
        file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/filament-c.def" "EXPORTS\n    ${_fila_exports_text}\n")
        target_sources(filament-c-jvm PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/filament-c.def")

        # Statically link the MSVC C++ runtime (/MT) to avoid msvcp140.dll conflicts
        # with the JVM. The prebuilts downloaded by Gradle must also be the /MT versions.
        set_property(TARGET filament-c-jvm PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

        # FIX: workaround for missing <cstring> in upstream utils/algorithm.h.
        # MSVC two-phase name lookup requires memcpy to be declared at the template-
        # definition point of utils::bit_cast (algorithm.h:280), not just at the
        # instantiation site. /FI force-includes <cstring> as the first line of every
        # TU so memcpy is always in scope when algorithm.h is first parsed.
        # TODO: remove once upstream filament adds #include <cstring> to algorithm.h.
        target_compile_options(filament-c-jvm PRIVATE /FIcstring)

        # PlatformWGL / BlueGLWindows need the Win32 OpenGL + GDI libs;
        # shlwapi is used by utils::Path.
        target_link_libraries(filament-c-jvm PRIVATE opengl32 gdi32 user32 shlwapi)
    elseif (UNIX)
        # Prebuilts are built against libc++, so the wrapper must compile + link against it too,
        # or the .so won't dlopen (undefined std::__1 symbols). Must be target_* options:
        # add_compile_options/add_link_options are directory-scoped and only affect later targets.
        target_compile_options(filament-c-jvm PRIVATE -stdlib=libc++ -ffunction-sections -fdata-sections)
        target_link_options(filament-c-jvm PRIVATE -stdlib=libc++)
        target_link_libraries(filament-c-jvm PRIVATE GL)
        # With the version-script seal below, gc-sections drops code unreachable from
        # the Fila* exports (ELF analogue of the macOS -dead_strip above).
        target_link_options(filament-c-jvm PRIVATE "LINKER:--gc-sections")
        # GNU ld scans static archives left-to-right in a single pass, so cross-refs
        # between Filament archives can drop members if ordering is wrong. Wrap them
        # in a group so ld rescans until all references resolve. Also force a
        # non-executable stack — one prebuilt .S object lacks the .note.GNU-stack marker.
        target_link_options(filament-c-jvm PRIVATE
            "LINKER:-z,noexecstack"
            "LINKER:--start-group"
        )
        target_link_libraries(filament-c-jvm PRIVATE "-Wl,--end-group")
        # Seal: same as the macOS -exported_symbol above, via a version script.
        target_link_options(filament-c-jvm PRIVATE
            "LINKER:--version-script=${CMAKE_CURRENT_SOURCE_DIR}/filament-c.map")
        set_target_properties(filament-c-jvm PROPERTIES
            LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/filament-c.map")
    endif()

    # Release ships stripped (smaller runtime jars + extraction cache); the dynamic
    # Fila* exports are untouched. Debug builds (-Pfilament.debug=true) keep symbols.
    if (CMAKE_BUILD_TYPE STREQUAL "Release")
        if (APPLE)
            add_custom_command(TARGET filament-c-jvm POST_BUILD
                COMMAND strip -x "$<TARGET_FILE:filament-c-jvm>")
        elseif (UNIX)
            add_custom_command(TARGET filament-c-jvm POST_BUILD
                COMMAND strip --strip-unneeded "$<TARGET_FILE:filament-c-jvm>")
        endif()
    endif()
endif()
