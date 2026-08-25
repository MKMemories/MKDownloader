cmake_minimum_required(VERSION 3.22)
project(mkstudio)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# stable-diffusion.cpp (cloné par la CI à un commit épinglé — voir studio.yml).
# CPU uniquement : portable sur tous les téléphones arm64.
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
add_subdirectory(stable-diffusion.cpp EXCLUDE_FROM_ALL)

add_library(mkstudio SHARED sd_jni.cpp)
target_include_directories(mkstudio PRIVATE stable-diffusion.cpp/include)
target_link_libraries(mkstudio PRIVATE stable-diffusion android log)
