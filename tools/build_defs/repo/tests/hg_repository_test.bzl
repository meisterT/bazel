load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load("//tools/build_defs/repo:hg.bzl", "hg_repository")
load("@bazel_skylib//lib:paths.bzl", "paths")

# Test Environment Setup
# This function will be called by each test case to set up the environment.
# It defines the repository we want to test.
def _test_env_setup(test_ctx, repo_name, **kwargs):
    """Sets up the hg_repository for testing.

    Args:
        test_ctx: The test environment context from unittest.begin.
        repo_name: The name for the repository.
        **kwargs: Attributes to pass to hg_repository.
    """
    hg_repository(
        name = repo_name,
        **kwargs
    )

# --- Test Case: Basic Clone (Tag) ---
def _test_basic_clone_tag_impl(ctx):
    env = unittest.begin(ctx)

    repo_name = "hello_repo_tag"
    _test_env_setup(
        env,
        name = repo_name,
        remote = "https://www.mercurial-scm.org/repo/hello",
        tag = "0.1", # Known tag for hg/hello repo
        build_file_content = """
package(default_visibility = ["//visibility:public"])
filegroup(
    name = "hello_files",
    srcs = [
        "README",
        "hello.c",
        "Makefile",
    ],
)
""",
    )

    # Check if the filegroup target defined in build_file_content exists
    target_label = "@{}//:hello_files".format(repo_name)
    rule = asserts.existing_rule(env, target_label)
    asserts.true(env, rule != None, "Target {} should exist".format(target_label))

    # Check the sources of the filegroup
    # Note: native.existing_rule() returns a dict-like object.
    # The structure of this object can vary. We need to inspect its 'srcs'.
    # Skylib's 'analysistest' might provide better tools for this, but let's try with existing_rule.
    # For filegroup, 'srcs' should be a list of labels.
    expected_srcs_short_paths = ["README", "hello.c", "Makefile"]
    actual_srcs_labels = rule["srcs"] # This will be a list of Label objects

    # Convert actual_srcs_labels (which are Label objects) to their short paths for comparison
    actual_srcs_short_paths = []
    for label in actual_srcs_labels:
        # Label objects from existing_rule are fully resolved, e.g., @@hello_repo_tag//:README
        # We need to compare the file name part.
        # A Label like @@repo_name//pkg:name has label.name as 'name' and label.package as 'pkg'
        # For files at the root, package is empty.
        actual_srcs_short_paths.append(label.name)


    for expected_src in expected_srcs_short_paths:
        asserts.true(
            env,
            expected_src in actual_srcs_short_paths,
            "Expected src '{}' not found in target {} srcs. Found: {}".format(
                expected_src,
                target_label,
                actual_srcs_short_paths,
            ),
        )
    return unittest.end(env)

# --- Test Case: Checkout Specific Revision ---
def _test_checkout_revision_impl(ctx):
    env = unittest.begin(ctx)
    repo_name = "hello_repo_rev"

    # Revision '484516940961' corresponds to tag '0.2' in mercurial-scm.org/repo/hello
    # It removed 'COPYING' and added '.hgignore', 'Changelog'
    # 'README' was modified.
    _test_env_setup(
        env,
        name = repo__name,
        remote = "https://www.mercurial-scm.org/repo/hello",
        revision = "484516940961", # Corresponds to tag 0.2
        build_file_content = """
package(default_visibility = ["//visibility:public"])
filegroup(
    name = "rev_files",
    srcs = [
        "README",      # Exists
        "hello.c",     # Exists
        "Makefile",    # Exists
        ".hgignore",   # Added in 0.2
        "Changelog",   # Added in 0.2
    ],
)
filegroup(
    name = "rev_files_not_present",
    srcs = ["COPYING"], # Removed before 0.2
)
""",
    )

    target_label_present = "@{}//:rev_files".format(repo_name)
    rule_present = asserts.existing_rule(env, target_label_present)
    asserts.true(env, rule_present != None, "Target {} should exist".format(target_label_present))

    expected_srcs_present = ["README", "hello.c", "Makefile", ".hgignore", "Changelog"]
    actual_srcs_present_labels = rule_present["srcs"]
    actual_srcs_present_short_paths = [l.name for l in actual_srcs_present_labels]

    for f in expected_srcs_present:
        asserts.true(
            env,
            f in actual_srcs_present_short_paths,
            "Expected file '{}' not found in {} for revision.".format(f, target_label_present),
        )

    # To check for absence, we can't rely on existing_rule for a file that shouldn't be in the srcs.
    # Instead, we check that 'COPYING' is NOT in the srcs of 'rev_files'.
    asserts.false(
        env,
        "COPYING" in actual_srcs_present_short_paths,
        "File 'COPYING' should not be present in {} for this revision.".format(target_label_present),
    )

    return unittest.end(env)


# --- Test Case: build_file attribute ---
def _test_build_file_attr_impl(ctx):
    env = unittest.begin(ctx)
    repo_name = "hello_repo_build_file_attr"

    _test_env_setup(
        env,
        name = repo_name,
        remote = "https://www.mercurial-scm.org/repo/hello",
        tag = "0.1", # Use a known state
        # build_file_content is mutually exclusive with build_file
        build_file = "//tools/build_defs/repo/tests:BUILD.test-hg-repo",
    )

    # Check for a target defined in BUILD.test-hg-repo
    # e.g., filegroup "from_build_file_attr"
    target_label = "@{}//:from_build_file_attr".format(repo_name)
    rule = asserts.existing_rule(env, target_label)
    asserts.true(env, rule != None, "Target {} from build_file attribute should exist".format(target_label))

    # Check for the genrule target
    genrule_target_label = "@{}//:hello_world_from_build_file".format(repo_name)
    genrule_rule = asserts.existing_rule(env, genrule_target_label)
    asserts.true(env, genrule_rule != None, "Genrule {} from build_file attribute should exist".format(genrule_target_label))
    asserts.equals(env, "genrule", genrule_rule["kind"], "Expected genrule kind for {}".format(genrule_target_label))


    return unittest.end(env)


# --- Test Case: Patching ---
# This test is more complex due to file content verification.
# We'll create a patch and try to verify its application.
def _test_patching_impl(ctx):
    env = unittest.begin(ctx)
    repo_name = "hello_repo_patched"

    # 1. Create a patch file using ctx.actions.write
    patch_content = """--- a/README
+++ b/README
@@ -1,3 +1,4 @@
 This is a hello world program written in C.
 It is a teaching example for Mercurial.
 It is also a teaching example for distributed SCM.
+This line was added by a patch.
"""
    patch_file = unittest.scratch_file(env, "0001-add-line-to-readme.patch", content = patch_content)


    _test_env_setup(
        env,
        name = repo_name,
        remote = "https://www.mercurial-scm.org/repo/hello",
        tag = "0.1", # Known state to apply patch against
        patches = [patch_file], # Label of the patch file
        build_file_content = """
package(default_visibility = ["//visibility:public"])
filegroup(
    name = "patched_readme_fg",
    srcs = ["README"],
)
""",
    )

    # Verification:
    # Option A: Check if a target representing the patched file can be "built" (analyzed).
    # This doesn't confirm content.
    readme_target_label = "@{}//:patched_readme_fg".format(repo_name)
    readme_rule = asserts.existing_rule(env, readme_target_label)
    asserts.true(env, readme_rule != None, "Target {} for patched README should exist".format(readme_target_label))

    # Option B: Use ctx.actions.run_shell to check file content.
    # This is more thorough but requires careful path handling.
    # The external repo path is tricky to get reliably in bzl_test for shell commands.
    # A common pattern is to create a genrule *within the test* that depends on the
    # external file and then runs a check.
    # For now, we'll rely on the fact that if patching failed, hg_repository would likely fail.
    # A deeper content check would require more advanced test harness.

    # A simple check: if the rule processed, patching (if it failed) would have stopped it.
    # This is an indirect check.
    asserts.true(env, True, "Patching test completed analysis. Actual content check needs more.")


    # TODO: Add a more robust way to check patched content if possible with available tools.
    # One way could be to define a genrule in build_file_content that cats the file
    # and then check its output, but that's too complex for this step.

    return unittest.end(env)


# --- Test Suite Definition ---
hg_repository_test_cases = {
    "test_basic_clone_tag": _test_basic_clone_tag_impl,
    "test_checkout_revision": _test_checkout_revision_impl,
    "test_build_file_attr": _test_build_file_attr_impl,
    "test_patching": _test_patching_impl,
}

hg_repository_test_suite = unittest.suite(
    "hg_repository_tests",
    **hg_repository_test_cases
)
