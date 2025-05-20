Project: /_project.yaml
Book: /_book.yaml

# Repository Rules

{% include "_buttons.html" %}

This page covers how to define repository rules and provides examples for more
details.

An [external repository](/external/overview#repository) is a directory tree,
containing source files usable in a Bazel build, which is generated on demand by
running its corresponding **repo rule**. Repos can be defined in a multitude of
ways, but ultimately, each repo is defined by invoking a repo rule, just as
build targets are defined by invoking build rules. They can be used to depend on
third-party libraries (such as Maven packaged libraries) but also to generate
`BUILD` files specific to the host Bazel is running on.

## Repository rule definition

In a `.bzl` file, use the
[repository_rule](/rules/lib/globals/bzl#repository_rule) function to define a
new repo rule and store it in a global variable. After a repo rule is defined,
it can be invoked as a function to define repos. This invocation is usually
performed from inside a [module extension](/external/extension) implementation
function.

The two major components of a repo rule definition are its attribute schema and
implementation function. The attribute schema determines the names and types of
attributes passed to a repo rule invocation, and the implementation function is
run when the repo needs to be fetched.

## Attributes

Attributes are arguments passed to the repo rule invocation. The schema of
attributes accepted by a repo rule is specified using the `attrs` argument when
the repo rule is defined with a call to `repository_rule`. An example defining
`url` and `sha256` attributes as strings:

```python
http_archive = repository_rule(
    implementation=_impl,
    attrs={
        "url": attr.string(mandatory=True),
        "sha256": attr.string(mandatory=True),
    }
)
```

To access an attribute within the implementation function, use
`repository_ctx.attr.<attribute_name>`:

```python
def _impl(repository_ctx):
    url = repository_ctx.attr.url
    checksum = repository_ctx.attr.sha256
```

All `repository_rule`s have the implicitly defined attribute `name`. This is a
string attribute that behaves somewhat magically: when specified as an input to
a repo rule invocation, it takes an apparent repo name; but when read from the
repo rule's implementation function using `repository_ctx.attr.name`, it returns
the canonical repo name.

## Implementation function

Every repo rule requires an `implementation` function. It contains the actual
logic of the rule and is executed strictly in the Loading Phase.

The function has exactly one input parameter, `repository_ctx`. The function
returns either `None` to signify that the rule is reproducible given the
specified parameters, or a dict with a set of parameters for that rule that
would turn that rule into a reproducible one generating the same repo. For
example, for a rule tracking a git repository that would mean returning a
specific commit identifier instead of a floating branch that was originally
specified.

The input parameter `repository_ctx` can be used to access attribute values, and
non-hermetic functions (finding a binary, executing a binary, creating a file in
the repository or downloading a file from the Internet). See [the API
docs](/rules/lib/builtins/repository_ctx) for more context. Example:

```python
def _impl(repository_ctx):
  repository_ctx.symlink(repository_ctx.attr.path, "")

local_repository = repository_rule(
    implementation=_impl,
    ...)
```

## When is the implementation function executed?

The implementation function of a repo rule is executed when Bazel needs a target
from that repository, for example when another target (in another repo) depends
on it or if it is mentioned on the command line. The implementation function is
then expected to create the repo in the file system. This is called "fetching"
the repo.

In contrast to regular targets, repos are not necessarily re-fetched when
something changes that would cause the repo to be different. This is because
there are things that Bazel either cannot detect changes to or it would cause
too much overhead on every build (for example, things that are fetched from the
network). Therefore, repos are re-fetched only if one of the following things
changes:

*   The attributes passed to the repo rule invocation.
*   The Starlark code comprising the implementation of the repo rule.
*   The value of any environment variable passed to `repository_ctx`'s
    `getenv()` method or declared with the `environ` attribute of the
    [`repository_rule`](/rules/lib/globals/bzl#repository_rule). The values of
    these environment variables can be hard-wired on the command line with the
    [`--repo_env`](/reference/command-line-reference#flag--repo_env) flag.
*   The existence, contents, and type of any paths being
    [`watch`ed](/rules/lib/builtins/repository_ctx#watch) in the implementation
    function of the repo rule.
    *   Certain other methods of `repository_ctx` with a `watch` parameter, such
        as `read()`, `execute()`, and `extract()`, can also cause paths to be
        watched.
    *   Similarly, [`repository_ctx.watch_tree`](/rules/lib/builtins/repository_ctx#watch_tree)
        and [`path.readdir`](/rules/lib/builtins/path#readdir) can cause paths
        to be watched in other ways.
*   When `bazel fetch --force` is executed.

There are two parameters of `repository_rule` that control when the repositories
are re-fetched:

*   If the `configure` flag is set, the repository is re-fetched on `bazel
    fetch --force --configure` (non-`configure` repositories are not
    re-fetched).
*   If the `local` flag is set, in addition to the above cases, the repo is also
    re-fetched when the Bazel server restarts.

## `hg_repository` Rule

The `hg_repository` rule fetches an external repository by cloning it from a
Mercurial (hg) version control system.

It allows Bazel to depend on source code managed in Mercurial. The rule clones
the repository from a specified remote URI, checks out a particular revision,
tag, or branch, and makes the contents available for Bazel builds. It also
supports applying patches and customizing the `BUILD` and `WORKSPACE` files
for the fetched repository.

**Prerequisites:**

Mercurial (`hg`) must be installed on the system where Bazel is executed. The `hg`
executable should be available in the system's `PATH`. Alternatively, the path
to the `hg` executable can be specified via the `_hg_tool` attribute (typically
configured globally via a toolchain or by overriding the default Label, e.g.,
`@bazel_tools//tools/mercurial:hg`).

**Reproducibility:**

For hermeticity and reproducibility, if a `tag` or `branch` is specified,
`hg_repository` resolves it to a specific changeset ID during the initial fetch.
This resolved changeset ID is then stored. Subsequent fetches (e.g., on a clean
build or when the repository cache is cleared) will use this exact changeset ID,
ensuring that the build uses the same source code version over time. If the
`revision` attribute (a specific changeset ID) is provided, it is used directly,
guaranteeing reproducibility from the outset.

**Attributes:**

| Attribute                | Description                                                                                                | Type        | Mandatory | Default                                                |
| :----------------------- | :--------------------------------------------------------------------------------------------------------- | :---------- | :-------- | :----------------------------------------------------- |
| `name`                   | A unique name for this repository.                                                                         | String      | Yes       |                                                        |
| `remote`                 | The URI of the remote Mercurial repository.                                                                | String      | Yes       |                                                        |
| `revision`               | Specific revision (changeset ID) to check out. Takes precedence over `tag` or `branch` for reproducibility.  | String      | No        | `""`                                                   |
| `tag`                    | Tag to check out. Used if `revision` is not set.                                                           | String      | No        | `""`                                                   |
| `branch`                 | Branch to check out. Used if `revision` and `tag` are not set.                                             | String      | No        | `""`                                                   |
| `patches`                | A list of Label references to patch files to apply after checkout.                                         | List of Labels | No        | `[]`                                                   |
| `patch_tool`             | Path to the patch utility (e.g., `patch`). Defaults to the system's patch tool found in the PATH.          | String      | No        | `""`                                                   |
| `patch_args`             | List of arguments for the patch tool.                                                                      | List of Strings | No        | `[]`                                                   |
| `patch_strip`            | Number of leading path segments to strip from paths in patches (equivalent to `patch -p<N>`).                | Integer     | No        | `0`                                                    |
| `patch_cmds`             | Bash commands (on Linux/macOS) to run after patches are applied.                                           | List of Strings | No        | `[]`                                                   |
| `patch_cmds_win`         | PowerShell commands (on Windows) to run after patches are applied.                                         | List of Strings | No        | `[]`                                                   |
| `build_file`             | Label of a file to use as the `BUILD` file for this repository. Overrides `build_file_content`.            | Label       | No        | `None`                                                 |
| `build_file_content`     | Content for the `BUILD` file for this repository if `build_file` is not specified.                         | String      | No        | `""`                                                   |
| `workspace_file`         | Label of a file to use as the `WORKSPACE` file for this repository. Overrides `workspace_file_content`.    | Label       | No        | `None`                                                 |
| `workspace_file_content` | Content for the `WORKSPACE` file for this repository if `workspace_file` is not specified.                 | String      | No        | `""`                                                   |
| `strip_prefix`           | A directory prefix to strip from the extracted files.                                                      | String      | No        | `""`                                                   |
| `verbose`                | If true, enables verbose output during hg operations.                                                      | Boolean     | No        | `False`                                                |
| `_hg_tool`               | Path to the Mercurial (hg) executable.                                                                     | Label       | No        | `@bazel_tools//tools/mercurial:hg` (or system default) |

**Basic Usage Examples:**

*Cloning a repository using a tag:*

```starlark
load("@your_rules_alias//:hg.bzl", "hg_repository") # Adjust load path as needed

hg_repository(
    name = "mercurial_hello_tagged",
    remote = "https://www.mercurial-scm.org/repo/hello",
    tag = "0.1",
    build_file_content = """
package(default_visibility = ["//visibility:public"])
filegroup(
    name = "hello_srcs",
    srcs = glob(["*.c", "README"]),
)
""",
)
```

*Cloning a repository using a specific revision (changeset ID):*

```starlark
hg_repository(
    name = "mercurial_hello_specific_rev",
    remote = "https://www.mercurial-scm.org/repo/hello",
    revision = "03eff24f1f01b038a0f43ba2586827f89cee4a41", # Corresponds to tag 0.1
)
```

*Applying a patch:*

```starlark
# Assuming you have a patch file my_fix.patch in your workspace:
# //patches:my_fix.patch

hg_repository(
    name = "mercurial_hello_patched",
    remote = "https://www.mercurial-scm.org/repo/hello",
    tag = "0.1",
    patches = ["//patches:my_fix.patch"],
    patch_strip = 1,
)
```

**Current Limitations:**

*   Submodule/subrepository support is not yet implemented.
*   The `strip_prefix` attribute's implementation might have limitations with symlinks or complex directory structures.

## Forcing refetch of external repos

Sometimes, an external repo can become outdated without any change to its
definition or dependencies. For example, a repo fetching sources might follow a
particular branch of a third-party repository, and new commits are available on
that branch. In this case, you can ask bazel to refetch all external repos
unconditionally by calling `bazel fetch --force --all`.

Moreover, some repo rules inspect the local machine and might become outdated if
the local machine was upgraded. Here you can ask Bazel to only refetch those
external repos where the [`repository_rule`](/rules/lib/globals#repository_rule)
definition has the `configure` attribute set, use `bazel fetch --force
--configure`.

## Examples

-   [C++ auto-configured
    toolchain](https://cs.opensource.google/bazel/bazel/+/master:tools/cpp/cc_configure.bzl;drc=644b7d41748e09eff9e47cbab2be2263bb71f29a;l=176):
    it uses a repo rule to automatically create the C++ configuration files for
    Bazel by looking for the local C++ compiler, the environment and the flags
    the C++ compiler supports.

-   [Go repositories](https://github.com/bazelbuild/rules_go/blob/67bc217b6210a0922d76d252472b87e9a6118fdf/go/private/go_repositories.bzl#L195)
    uses several `repository_rule` to defines the list of dependencies needed to
    use the Go rules.

-   [rules_jvm_external](https://github.com/bazelbuild/rules_jvm_external)
    creates an external repository called `@maven` by default that generates
    build targets for every Maven artifact in the transitive dependency tree.