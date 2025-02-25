# project name
project(spark-thestral-plugin)

# required cmake version
cmake_minimum_required(VERSION 3.10)

# configure cmake
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_COMPILER "g++")

set(root_directory ${PROJECT_BINARY_DIR})

set(CMAKE_CXX_FLAGS_DEBUG "-pipe -g -Wall -fPIC -fno-common -fno-stack-protector")
set(CMAKE_CXX_FLAGS_RELEASE "-O2 -pipe -Wall -Wtrampolines -D_FORTIFY_SOURCE=2 -O2 -fPIC -finline-functions -fstack-protector-strong -s -Wl,-z,noexecstack -Wl,-z,relro,-z,now")

if (DEFINED COVERAGE)
    if(${COVERAGE} STREQUAL "ON")
        set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -ftest-coverage -fprofile-arcs")
    endif()
endif()
# configure file
configure_file(
    "${PROJECT_SOURCE_DIR}/config.h.in"
    "${PROJECT_SOURCE_DIR}/config.h"
)

aux_source_directory(${CMAKE_CURRENT_LIST_DIR} ROOT_SRCS)
# for header searching
include_directories(SYSTEM src)

# compile library
add_subdirectory(src)

message(STATUS "Build by ${CMAKE_BUILD_TYPE}")

option(BUILD_CPP_TESTS "test" ON)
message(STATUS "Option BUILD_CPP_TESTS: ${BUILD_CPP_TESTS}")
if(${BUILD_CPP_TESTS})
    enable_testing()
    add_subdirectory(test)
endif ()

# options
option(DEBUG_RUNTIME "Debug" OFF)
message(STATUS "Option DEBUG: ${DEBUG_RUNTIME}")

option(TRACE_RUNTIME "Trace" OFF)
message(STATUS "Option TRACE: ${TRACE_RUNTIME}")
