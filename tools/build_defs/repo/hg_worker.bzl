"""Worker functions for Mercurial repository operations."""

# Environment variables to ensure clean execution of hg commands.
# HGPLAIN=1: Disables UI features, ensures plain, parsable output.
# HGRCPATH, HGUSER: Unset to avoid interference from user configurations.
_HG_ENV_CLEANUP = {
    "HGPLAIN": "1",
    "HGRCPATH": None,  # Unset variable
    "HGUSER": None,    # Unset variable
}

def _hg(ctx, hg_repo_info, command, *args):
    """
    Generic helper to execute hg commands.

    Args:
        ctx: The repository context.
        hg_repo_info: A struct containing information about the hg repository,
                      including the working directory (hg_repo_info.directory).
        command: The hg command to run (e.g., "init", "pull", "log").
        *args: Additional arguments for the hg command.

    Returns:
        The standard output of the command if successful.

    Fails:
        If the command returns a non-zero exit code.
    """
    cmd_parts = [ctx.attr._hg_tool.path] + [command] + list(args)
    env = ctx.os.environ.copy()
    env.update(_HG_ENV_CLEANUP)

    # Remove None values from env, as execute doesn't like them.
    cleaned_env = {k: v for k, v in env.items() if v != None}

    working_directory = hg_repo_info.directory.path

    if ctx.attr.verbose:
        print("Executing hg command: %s in %s" % (" ".join(cmd_parts), working_directory))

    result = ctx.execute(
        cmd_parts,
        environment = cleaned_env,
        working_directory = working_directory,
        quiet = not ctx.attr.verbose,
    )

    if result.return_code != 0:
        error_message = (
            "Mercurial command failed: %s\n"
            "Exit code: %d\n"
            "Stdout:\n%s\n"
            "Stderr:\n%s" %
            (" ".join(cmd_parts), result.return_code, result.stdout, result.stderr)
        )
        fail(error_message)

    return result.stdout.strip()

def init(ctx, hg_repo_info):
    """Runs `hg init` in the specified directory."""
    _hg(ctx, hg_repo_info, "init")

def pull(ctx, hg_repo_info):
    """Runs `hg pull` from the remote specified in hg_repo_info."""
    if not hg_repo_info.remote:
        fail("Remote URI not specified for hg pull.")
    _hg(ctx, hg_repo_info, "pull", hg_repo_info.remote)

def update(ctx, hg_repo_info):
    """Runs `hg update <revision/tag/branch>`."""
    target = hg_repo_info.revision or hg_repo_info.tag or hg_repo_info.branch or "default"
    if not target:
        # Should default to tip or default branch if nothing is specified.
        # 'hg update null' or 'hg update default' are common.
        # Let's use 'default' if nothing else is specified.
        target = "default"
    _hg(ctx, hg_repo_info, "update", target)

def clean(ctx, hg_repo_info):
    """Runs `hg purge --all` to remove untracked files."""
    _hg(ctx, hg_repo_info, "purge", "--all")

def _get_current_revision(ctx, hg_repo_info):
    """Runs `hg id -i` to get the current changeset ID."""
    # The '+' indicates a dirty working directory, we should have a clean one.
    # We are interested in the pure changeset ID.
    full_id = _hg(ctx, hg_repo_info, "id", "-i")
    return full_id.rstrip("+")

def hg_clone_or_update(ctx, hg_repo_info):
    """
    Clones a Mercurial repository, or updates it if it already exists.

    Args:
        ctx: The repository context.
        hg_repo_info: A struct containing necessary information:
            - directory: The path object for the repository checkout.
            - remote: The remote URI.
            - revision: Specific revision to checkout.
            - tag: Specific tag to checkout.
            - branch: Specific branch to checkout.

    Returns:
        A struct containing the actual resolved `changeset_id`.
    """
    repo_dir = hg_repo_info.directory

    # 1. Delete target directory if it exists (ensures clean clone)
    # This might be too aggressive if we want to support incremental updates later.
    # For now, following the subtask to delete.
    if repo_dir.exists:
        ctx.report_progress("Removing existing directory: %s" % repo_dir.path)
        if ctx.os.name.lower() == "windows":
            ctx.actions.execute(["cmd.exe", "/c", "rmdir", "/s", "/q", repo_dir.path], quiet = not ctx.attr.verbose)
        else:
            ctx.actions.execute(["rm", "-rf", repo_dir.path], quiet = not ctx.attr.verbose)
    ctx.actions.execute(["mkdir", "-p", repo_dir.path])


    ctx.report_progress("Initializing hg repository at %s" % repo_dir.path)
    # 2. Call init()
    # init() uses hg_repo_info.directory, which is now created and empty.
    init(ctx, hg_repo_info)

    ctx.report_progress("Pulling from remote: %s" % hg_repo_info.remote)
    # 3. Call pull()
    pull(ctx, hg_repo_info)

    update_target = hg_repo_info.revision or hg_repo_info.tag or hg_repo_info.branch or "default"
    ctx.report_progress("Updating to: %s" % update_target)
    # 4. Call update()
    update(ctx, hg_repo_info)

    ctx.report_progress("Cleaning repository (hg purge --all)")
    # 5. Call clean()
    clean(ctx, hg_repo_info)

    ctx.report_progress("Getting current revision ID")
    # 6. Call _get_current_revision()
    actual_revision = _get_current_revision(ctx, hg_repo_info)
    if ctx.attr.verbose:
        print("Resolved changeset ID: %s" % actual_revision)

    return struct(
        changeset_id = actual_revision,
    )
