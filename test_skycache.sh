#!/bin/bash
set -e

BAZEL_WORKSPACE="/usr/local/google/home/twerth/bazel"
TEST_REPO="/tmp/slow_repo"
BAZEL_DEV="/tmp/bazel-dev"
OUTPUT_BASE="/tmp/ob-slow"
INSTALL_BASE="/tmp/ib-slow"

# 1. Build bazel-dev
echo "=== Building bazel-dev ==="
cd "$BAZEL_WORKSPACE"
bazel build //src:bazel-dev
rm -f "$BAZEL_DEV"
cp bazel-bin/src/bazel-dev "$BAZEL_DEV"

# 2. Generate slow repo
echo "=== Generating slow repo ==="
rm -rf "$TEST_REPO"
python3 generate_slow_repo.py

# Write .bazelrc for remote caching
echo "=== Writing .bazelrc ==="
cat << 'EOF' > "$TEST_REPO/.bazelrc"
build:remote --remote_instance_name=projects/bazel-untrusted/instances/default_instance
build:remote --remote_cache=grpcs://remotebuildexecution.googleapis.com
build:remote --remote_timeout=600
build:remote --google_default_credentials
build:remote --remote_default_exec_properties=dockerNetwork=standard
build:remote --remote_default_exec_properties=dockerPrivileged=true
build:remote --remote_default_exec_properties=Pool=default
EOF

cd "$TEST_REPO"

# 3. Warm up Bzlmod and external repos
echo "=== Warming up Bzlmod ==="
rm -rf "$OUTPUT_BASE/skycache"
"$BAZEL_DEV" --install_base="$INSTALL_BASE" --output_base="$OUTPUT_BASE" build --nobuild //:target

# Initialize git after warmup so generated MODULE.bazel and MODULE.bazel.lock are tracked
git init
git config user.name "Test"
git config config.email "test@example.com" || git config user.email "test@example.com"
git add .
git commit -m "initial commit"

# 4. Clean to reset in-memory graph (makes next build cold for Skyframe, but keeps Bzlmod cache)
echo "=== Cleaning to reset Skyframe (Cold baseline) ==="
"$BAZEL_DEV" --install_base="$INSTALL_BASE" --output_base="$OUTPUT_BASE" clean

# 5. Cold run (Vanilla) - Baseline (now fast because Bzlmod is warm in output_base)
echo "=== Running cold vanilla build ==="
time "$BAZEL_DEV" --install_base="$INSTALL_BASE" --output_base="$OUTPUT_BASE" build \
  --profile=/tmp/profile_vanilla.json.gz \
  //:target

# ==================== REMOTE CACHE TESTING ====================
# 6. Clean to reset Skyframe
"$BAZEL_DEV" --install_base="$INSTALL_BASE" --output_base="$OUTPUT_BASE" clean

# 7. Upload run (Skyframe is cold)
echo "=== Running remote upload build ==="
time "$BAZEL_DEV" --install_base="$INSTALL_BASE" --output_base="$OUTPUT_BASE" build \
  --config=remote \
  --experimental_remote_analysis_cache_mode=upload \
  --experimental_remote_analysis_cache_storage=REMOTE \
  --experimental_skycache_analysis_only=false \
  --profile=/tmp/profile_upload_remote.json.gz \
  //:target

# 8. Clean to reset Skyframe (forces download next run)
echo "=== Cleaning ==="
"$BAZEL_DEV" --install_base="$INSTALL_BASE" --output_base="$OUTPUT_BASE" clean

# 9. Download run (Hits expected from Skycache)
echo "=== Running remote download build (Hits expected) ==="
time "$BAZEL_DEV" --install_base="$INSTALL_BASE" --output_base="$OUTPUT_BASE" build \
  --config=remote \
  --experimental_remote_analysis_cache_mode=download \
  --experimental_remote_analysis_cache_storage=REMOTE \
  --experimental_skycache_analysis_only=false \
  //:target

# 10. Modify a file (last package to test fine-grained invalidation)
echo "=== Modifying pkg_4/BUILD ==="
echo "# dirty" >> "$TEST_REPO/pkg_4/BUILD"

# 11. Clean to reset Skyframe
"$BAZEL_DEV" --install_base="$INSTALL_BASE" --output_base="$OUTPUT_BASE" clean

# 12. Download run after change (Fine-grained invalidation expected)
echo "=== Running remote download build after change ==="
time "$BAZEL_DEV" --install_base="$INSTALL_BASE" --output_base="$OUTPUT_BASE" build \
  --config=remote \
  --experimental_remote_analysis_cache_mode=download \
  --experimental_remote_analysis_cache_storage=REMOTE \
  --experimental_skycache_analysis_only=false \
  --profile=/tmp/profile_download_remote.json.gz \
  //:target

# Restore the file to avoid polluting the repo for next runs
git checkout pkg_4/BUILD
