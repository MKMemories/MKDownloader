cmake_minimum_required(VERSION 3.22)
project(mkstudio)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# VITESSE : active les instructions vectorielles des CPU arm64 modernes
# (dotprod = produits scalaires int8 câblés, fp16 = arithmétique demi-précision).
# Gain 2-4× sur les modèles quantifiés. Requiert un CPU 2019+ (Cortex-A76…),
# ce qui est déjà le prérequis de l'app (6 Go RAM).
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options("$<$<COMPILE_LANGUAGE:C>:-march=armv8.2-a+dotprod+fp16>"
                        "$<$<COMPILE_LANGUAGE:CXX>:-march=armv8.2-a+dotprod+fp16>")
endif()

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
