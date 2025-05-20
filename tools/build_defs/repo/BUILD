load("@bazel_skylib//lib:bzl_library.bzl", "bzl_library")

package(default_visibility = ["//visibility:public"])

bzl_library(
    name = "hg_lib",
    srcs = [
        "hg.bzl",
        "hg_worker.bzl",
    ],
    deps = [
        ":utils_lib", # Local reference to utils_lib
        "@bazel_tools//tools/build_defs/repo:http.bzl", # For repository_rule, Label, etc.
        "@bazel_skylib//lib:paths",
        "@bazel_skylib//lib:types",
    ],
)

bzl_library(
    name = "utils_lib",
    srcs = [
        "utils/utils.bzl",
        "utils/patch.bzl", # Assuming patch.bzl is also in the utils directory
    ],
    visibility = ["//visibility:public"], # Make sure this is visible to hg_lib and tests
    deps = [
        "@bazel_skylib//lib:paths",
        "@bazel_skylib//lib:shell",
    ],
)
