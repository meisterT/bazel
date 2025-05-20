load("@bazel_skylib//lib:unittest.bzl", "bzl_library")
load(":hg_repository_test.bzl", "hg_repository_test_suite")

test_suite(
    name = "hg_repository_tests",
    tests = [":hg_repository_test_suite_nodefault"], # Will define this bzl_test target
)

# This target will run the bzl_test.
# We need to ensure the hg_repository rule itself is available.
# The test execution needs a WORKSPACE file.
bzl_test(
    name = "hg_repository_test_suite_nodefault",
    srcs = ["hg_repository_test.bzl"],
    # The test needs access to the hg_repository rule definition
    # and any tools it depends on (like hg itself).
    # This might require setting up a test WORKSPACE or using toolchains.
    deps = [
        "//tools/build_defs/repo:hg_lib", # Assuming hg.bzl is part of a bzl_library named hg_lib
        "@bazel_skylib//lib:unittest",
    ],
)

bzl_library(
    name = "hg_repository_test_lib",
    srcs = ["hg_repository_test.bzl"],
    deps = [
        "//tools/build_defs/repo:hg_lib",
        "@bazel_skylib//lib:unittest",
    ],
)

# A dummy build file to be used by one of the tests
filegroup(
    name = "dummy_build_file_for_test",
    srcs = ["BUILD.test-hg-repo"],
    visibility = ["//visibility:public"],
)

# Content for BUILD.test-hg-repo
# This will be created by the test itself if needed, or here.
# For now, let's create it here.
exports_files(["BUILD.test-hg-repo"])
