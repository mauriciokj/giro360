cmake_minimum_required(VERSION 3.22.1)
project(giro360_stitcher LANGUAGES CXX)

find_package(OpenCV REQUIRED CONFIG)

add_library(
    giro360_stitcher
    SHARED
    "${CMAKE_CURRENT_LIST_DIR}/../../../../ios/Classes/giro360_stitcher.cpp"
)

target_compile_features(giro360_stitcher PRIVATE cxx_std_17)
target_compile_options(giro360_stitcher PRIVATE -O2)
target_link_libraries(
    giro360_stitcher
    PRIVATE
    OpenCV::opencv_java4
    android
    log
)
