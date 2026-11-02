cmake_minimum_required(VERSION 3.22.1)
project(openmk3 CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Mutable Instruments Plaits (MIT), see third_party/mutable/README.md.
# TEST selects stmlib's portable C code instead of Cortex-M assembly.
set(MI ${CMAKE_CURRENT_SOURCE_DIR}/third_party/mutable)
file(GLOB_RECURSE PLAITS_SOURCES ${MI}/plaits/dsp/*.cc)
add_library(mutable STATIC
    ${PLAITS_SOURCES}
    ${MI}/plaits/resources.cc
    ${MI}/stmlib/dsp/units.cc
    ${MI}/stmlib/utils/random.cc)
target_include_directories(mutable PUBLIC ${MI})
target_compile_definitions(mutable PUBLIC TEST)
target_compile_options(mutable PRIVATE -O3 -ffast-math -w)

# Dexed's FM engine (msfa: Apache-2.0; Dexed changes: GPL-3.0), see third_party/dexed/README.md.
set(DX ${CMAKE_CURRENT_SOURCE_DIR}/third_party/dexed)
add_library(dexed STATIC
    ${DX}/msfa/dx7note.cc ${DX}/msfa/env.cc ${DX}/msfa/exp2.cc ${DX}/msfa/fm_core.cc
    ${DX}/msfa/fm_op_kernel.cc ${DX}/msfa/freqlut.cc ${DX}/msfa/lfo.cc ${DX}/msfa/pitchenv.cc
    ${DX}/msfa/porta.cpp ${DX}/msfa/sin.cc ${DX}/msfa/tuning_standard.cc)
target_include_directories(dexed PUBLIC ${DX} ${DX}/msfa ${DX}/shim)
target_compile_options(dexed PRIVATE -O3 -w)

add_library(openmk3 SHARED engine.cpp synth.cpp fm.cpp)
target_compile_options(openmk3 PRIVATE -O3 -ffast-math -Wall)
target_link_libraries(openmk3 mutable dexed aaudio log)
