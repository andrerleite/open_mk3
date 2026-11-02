cmake_minimum_required(VERSION 3.22.1)
project(openmk3 CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(openmk3 SHARED engine.cpp)
target_compile_options(openmk3 PRIVATE -O3 -ffast-math -Wall)
target_link_libraries(openmk3 aaudio log)
