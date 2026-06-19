# Skycache Research Notes

This document summarizes the research on the existing Skycache implementation for Blaze in the codebase, focusing on the cache key structure, stored value format, and the retrieval/invalidation flow.

## Cache Key Structure

The cache key for a given `SkyKey` is a fingerprint of the serialized key combined with the build configuration and version metadata.

$$\text{Cache Key} = \text{Fingerprint}(\text{FrontierNodeVersion} + \text{Serialized}(\text{SkyKey}))$$

Where:
*   `Serialized(SkyKey)` is the byte representation of the `SkyKey` after serialization.
*   `FrontierNodeVersion` is a precomputed fingerprint (SHA-256) of the following metadata:
    *   **Top-level Configuration Checksum**: Checksum of the top-level build configuration (trimmed of test options).
    *   **Blaze Installation MD5**: MD5 hash of the Blaze binary, ensuring invalidation if the binary changes.
    *   **Starlark Semantics Fingerprint**: Fingerprint of the Starlark semantics.
    *   **Evaluating Version**: The source code version (baseline CL) being evaluated.
    *   **Use Fake Stamp Data**: Boolean indicating if fake stamp data is used.
    *   **Distinguisher**: Bytes used for testing to isolate cache entries.

This structure ensures that any change in the environment (code version, config, binary) automatically shifts the cache to a new namespace, preventing invalid reads.

## Stored Value Format

The values stored in the Content Addressable Storage (CAS) are prefixed with invalidation metadata:

$$\text{Stored Value} = [\text{DataType (Enum)}] \, [\text{Invalidation Key (optional)}] \, [\text{Serialized SkyValue}]$$

*   `DataType` (written as varint enum):
    *   `DATA_TYPE_EMPTY` (1): No validation needed.
    *   `DATA_TYPE_FILE` (2): Value depends on a file. The `Invalidation Key` is a string (MTSV + path).
    *   `DATA_TYPE_LISTING` (3): Value depends on a directory listing. The `Invalidation Key` is a string.
    *   `DATA_TYPE_ANALYSIS_NODE` (4): Value depends on a set of analysis-phase dependencies. The `Invalidation Key` is a 16-byte fingerprint of the dependency node.
    *   `DATA_TYPE_EXECUTION_NODE` (5): Value depends on a set of execution-phase dependencies. The `Invalidation Key` is a 16-byte fingerprint of the dependency node.
*   `Invalidation Key`: Used by the server to check if the dependency has changed. Format depends on `DataType` (String for file/listing, 16-byte fingerprint for nodes).
*   `Serialized SkyValue`: The actual serialized `SkyValue`.

## MTSV (Max Transitive Source Version)

The **Max Transitive Source Version (MTSV)** is a key concept in Skycache's fine-grained invalidation. It represents the canonical version of a file (or directory listing), taking into account all its transitive dependencies (parent directories, symlinks).

$$MTSV(file) = \max \left( \text{version}(file), \, MTSV(\text{parent\_dir}), \, \max_{s \in \text{symlinks}} (MTSV(s.target\_parent)) \right)$$

*   **Version of file**: Obtained via `LongVersionGetter`. In Google3, this is typically the CL number when the file was last modified, retrieved via filesystem extended attributes (`xattrs`) in CitC.
*   **Transitive Propagation**: When serializing dependencies, `FileDependencySerializer` recursively resolves parents and symlinks, propagating the maximum version upwards.
*   **Canonical Key**: The invalidation data for a file is stored in the cache using a key that includes its MTSV:
    $$\text{Invalidation Key} = \text{Base64}(MTSV) + \text{delimiter} + \text{path}$$
    Delimiters are `:` for files and `;` for directory listings.

## Versioned Invalidation (Fine-Grained)

Instead of simple dirty/clean flags, Skycache uses version numbers (CLs) to determine validity.

### Key Components:
1.  **Validity Horizon (VH)**: The version up to which a cached entry is known to be valid. Initially, $VH = MTSV$.
2.  **Client Version (VC)**: The version the client is currently synced to (e.g., current synced CL).
3.  **VersionedChanges**: A structure containing a map of paths to sorted arrays of versions (CLs) where they changed.
    *   It is populated with:
        *   Local changes in the client workspace (marked with a special `CLIENT_CHANGE` version).
        *   Depot changes that occurred in the range $(VH, VC]$ (retrieved from VCS).

### Validation Algorithm:
When validating a dependency:
1.  The client queries the cache for the dependency's invalidation data using $KEY = E(MTSV_{cached}) : path$.
2.  If found, the server/service checks if the path (or its transitively resolved dependencies) has changed by calling `VersionedChanges.matchFileChange(path, VH)`.
3.  `matchFileChange` looks for any registered change to `path` with a version $\ge VH + 1$.
4.  If a change is found (version $\ge VH + 1$):
    *   If it is a `CLIENT_CHANGE`, or if it is within $(VH, VC]$, the dependency is **invalidated** (Cache Miss).
5.  If no changes are found $\ge VH + 1$:
    *   The dependency is valid. The Validity Horizon can be pushed forward to $VC$.

This allows a cached entry to remain valid even if the global version (VC) has advanced, as long as the specific files it depends on have not changed.

### Implementation Limitations & Scope

While the design supports cross-version tracking using version arrays, the current production implementation has key limitations:
*   **Same-Baseline-CL Reuse Only**: The client bails out (disables download mode) if the baseline CL or configuration changes between builds. Thus, cache reuse is only possible when building at the exact same baseline CL (e.g., sharing builds at a release CL, or iterating locally).
*   **Local Changes Tracking**: Fine-grained invalidation is primarily used to check if local, uncommitted changes (registered as `CLIENT_CHANGE`) invalidate cached entries from the matched baseline CL.
*   **No Depot Changes Tracking**: Production code does not register depot changes (changes between different CLs) in `VersionedChanges`. The validity horizon ($VH$) is effectively set to the client's synced version ($VC$), and the system only checks for changes greater than $VC$ (i.e., local changes).

## Retrieval and Invalidation Flow

The following diagram illustrates the retrieval and invalidation flow, highlighting the role of MTSV and `VersionedChanges`.

```
+-------------------------------------------------------------------------------------------------+
|                                         Bazel Build (Reader)                                    |
+-------------------------------------------------------------------------------------------------+
       |
       | 1. Start Analysis
       v
+------------------+
|    BuildView     |
+------------------+
       |
       | 2. queryMetadataAndMaybeBailout()
       v
+-----------------------------+
|  RemoteAnalysisCacheClient  | ----(Metadata Query: Evaluating Version, Config Hash)----> [Metadata Store]
+-----------------------------+                                                                   |
       |                                                                                           |
       |<------------------------------(Match / No Match)------------------------------------------+
       |
       | 3. If Match, enable DOWNLOAD mode.
       |    Establish ClientSession (loads local changes into VersionedChanges as CLIENT_CHANGE).
       v
+-------------------------------------------------------------------------------------------------+
|                                    Skyframe Evaluation                                          |
+-------------------------------------------------------------------------------------------------+
       |
       | 4. Compute SkyValue for SkyKey
       v
+------------------------+
|   SkyValueRetriever    |
+------------------------+
       |
       | 5. Compute Cache Key: Fingerprint(FrontierNodeVersion + Serialized(SkyKey))
       | 6. lookup(Cache Key)
       v
+-------------------------------------------------------------------------------------------------+
|                                   AnalysisCacheService (Server)                                 |
+-------------------------------------------------------------------------------------------------+
       |
       | 7. Retrieve stored blob: [DataType] [Invalidation Key] [Serialized Value]
       v
       | 8. Check Invalidation (e.g. DataType == FILE)
       |    Invalidation Key contains MTSV_cached.
       |    Retrieve FileInvalidationData using Invalidation Key.
       |    
       |    Call ClientSession.matches(FileInvalidationData, VH = MTSV_cached)
       +------------------------------------+
                                            |
                                            v (VersionedChangesValidator)
                                    [VersionedChanges]
                                    - Has file changed at version >= VH + 1?
                                    - Query VCS for changes in (VH, VC] and add to VersionedChanges.
                                            |
                                            +----------------------------------+
                                            |                                  |
                                            | Yes (Change >= VH + 1)           | No
                                            v                                  v
                                    [Cache Miss]                       [Cache Hit]
                                    Return Empty                       Return Serialized Value
                                            |                                  |
       +------------------------------------+                                  |
       |                                                                       |
       |<-------------------(Empty / Serialized Value)-------------------------+
       |
       v
+------------------------+
|   SkyValueRetriever    |
+------------------------+
       |
       | 9. If Hit: Deserialize Value (may trigger recursive lookups)
       |    If Miss: Fallback to local computation
       v
+------------------------+
|        Skyframe        |
+------------------------+
```

# Proposed Design: Skycache for Bazel

This section proposes a design for implementing Skycache in Bazel, supporting both local and remote caching, and using a VCS-agnostic Merkle-tree approach with Git optimizations.

## User Journeys & Goals

1.  **Exact Match (CI -> User)**: CI builds commit $A$. User at commit $A$ gets 100% analysis cache hits.
2.  **Linear Git History (CI -> User)**: CI builds commit $A$. User is at commit $B$ (where $B$ is a descendant of $A$, e.g., $B = A + \text{local commits}$). User gets high hit rate for unchanged parts.
3.  **Local Modifications**: User has uncommitted local changes. They get a high hit rate for parts of the graph not affected by these changes.
4.  **Peer-to-Peer Sharing**: User U1 builds at state $X$, U2 at state $Y$ gets hits for shared parts.

---

## Core Invalidation: Bazel-Native Merkle Caching (VCS-Agnostic)

Instead of relying on Git for core correctness, the proposed design uses **Merkle Trees** built directly on the Skyframe dependency graph. This ensures it is VCS-agnostic and correctly handles files outside Git control (ignored files, external repositories).

### 1. Node State Hash (NSH)

Every Skyframe node $N$ is assigned a **Node State Hash (NSH)** representing its state and the state of all its transitive dependencies:

*   **Leaf Nodes (Source Files)**: $NSH(F) = \text{Digest}(F)$ (content hash, reusing Bazel's configured digest function, e.g., SHA-256).
*   **Directory Listings**: $NSH(D) = \text{Hash}(\text{names and digests of children})$.
*   **Evaluation Nodes (Rules, Packages)**:
    $$NSH(N) = \text{Hash}\left(\text{Content}(N) + \text{NSH}(D_1) + \text{NSH}(D_2) + \dots + \text{NSH}(D_m)\right)$$
    Where $\text{Content}(N)$ is the node's own definition (e.g., rule attributes, configuration, Starlark AST) and $D_i$ are its **direct dependencies** in Skyframe.

#### NSH Combination Hash Function
For combining hashes of dependencies into a node's NSH, we use **MurmurHash3 (128-bit)** (`Hashing.murmur3_128()` from Guava). It is non-cryptographic, extremely fast, has low collision rates, and is already available in Bazel's dependencies.

#### Memory Implications & Blaze Isolation
To minimize JVM heap overhead in Skyframe (which can have millions of nodes):
*   **Primitive Storage**: Store the NSH as **two primitive `long` fields** directly in the node entry class, adding exactly **16 bytes** per node with zero GC overhead.
*   **Blaze Isolation**: To ensure this overhead only affects Bazel, we introduce a Bazel-specific subclass:
    ```java
    public class BazelIncrementalInMemoryNodeEntry extends IncrementalInMemoryNodeEntry {
      private transient long nshLow;
      private transient long nshHigh;
    }
    ```
    Bazel's evaluator factory instantiates this subclass, while Google-internal Blaze continues to use `IncrementalInMemoryNodeEntry` (or `IncrementalSkybuildNodeEntry`), leaving Blaze's RAM completely unaffected.
*   **Scale**: $\approx 16\text{MB}$ overhead per 1M nodes ($\approx 160\text{MB}$ for 10M nodes, typically $<1\%$ of heap).

### 2. Analysis vs. Execution Dependencies (Source Files Exclusion)

A critical optimization and correctness property of Bazel is that the **analysis phase does not depend on the contents of source files** (e.g., `.cc`, `.java` files). It only depends on metadata files (`BUILD`, `.bzl`), directory listings (for globs), and configuration.

In Skyframe, this separation is naturally reflected in the dependency graph:
*   `ConfiguredTargetValue` nodes (representing target analysis) do **not** have dependency edges to the `FileValue`/`FileStateValue` of source files.
*   They depend on `InputFileConfiguredTarget` nodes, which only contain the file's identity (label/path) and do not depend on the file's content.
*   Therefore, the transitive NSH of a `ConfiguredTargetValue` will **naturally not change** when a source file's content is modified.
*   Source file changes will only invalidate execution-phase nodes (like `ActionExecutionValue`), which are not part of the analysis cache.
*   This ensures high analysis cache hit rates even when developers are actively modifying source code, without requiring any manual filtering of source files during NSH computation.

### 3. Client-Side Invalidation: The Index + CAS Design

To ensure Bazel Skycache works out-of-the-box with standard, passive remote caches (RBE CAS/AC) and local disk caches, we **avoid implementing a stateful, VCS-aware `AnalysisCacheService`** on the server. All validation logic is moved to the **Bazel client**.

Without VCS versions, using a simple `SkyKey -> Value` mapping would cause cache entries to be overwritten when switching branches. To support branch switching and version-independence, we split the cache structure into two parts: the **Index** and the **CAS**.

```
[ Logical Invalidation Flow (Slow Path / Fallback) ]

                      +------------------+
                      |   Bazel Client   |
                      +------------------+
                        /              \
         1. Lookup     /                \  3. Fetch Metadata
         Candidates   /                  \    (NSH_candidate)
                     v                    v
             +-------+            +-------+
             | Index |            |  CAS  |
             +-------+            +-------+
                 |                    |
                 v 2. Returns         v 4. Returns
            [NSH_1, NSH_2]       (Value, Deps, DepNSHs)
                                      |
                                      | 5. Validate Deps (Recursively)
                                      v
                                  Is current NSH(D_i) == expected?
                                  - Yes: Use cached Value
                                  - No:  Try next NSH / Rebuild

---------------------------------------------------------------------------------

[ Optimized Flow (Fast Path via Graph Manifest) ]

  1. Client computes local diff (Git) -> Unaffected nodes marked "Clean"
  2. For Clean Node N, client looks up NSH_valid in local Manifest (no Index query)
  3. Client fetches Value directly from CAS (no validation required)

                      +------------------+
                      |   Bazel Client   |
                      +------------------+
                               |
                               | Fetch Value (NSH_valid)
                               v
                            +-------+
                            |  CAS  |
                            +-------+
```

#### Cache Structure:
1.  **Index**: A version-independent store mapping `SkyKey -> List<NSH>`. It stores multiple historical state hashes (NSHs) for a single key, allowing the cache to hold values for multiple branches simultaneously.
2.  **CAS (Content Addressable Store)**: A state-dependent store mapping `NSH -> (Serialized Value, List<SkyKey> directDeps, List<NSH> expectedDepsNsh)`.

### 4. Top-Down Recursive Validation Flow

To evaluate a node $N$ using the cache:

1.  **Lookup Candidates**: Query the **Index** using `SkyKey(N)` to retrieve a list of candidate NSHs.
2.  **Validate Candidates**: For each candidate NSH (starting with the most recent):
    *   Retrieve the metadata from the **CAS** using the candidate NSH.
    *   For each direct dependency $D_i$ in the metadata:
        *   If $D_i$ is already evaluated/validated in the current build: Compare its current NSH with the expected NSH from the metadata.
        *   If $D_i$ is **not** evaluated:
            *   Recursively validate $D_i$ (call this validation flow for $D_i$).
            *   If $D_i$ is valid, its current NSH is confirmed to be the expected NSH.
            *   If $D_i$ is invalid (or cache miss), then this candidate NSH for $N$ is invalid. Break and try the next candidate.
    *   If all dependencies $D_i$ are valid, then the candidate NSH is valid for the current workspace state.
    *   We retrieve the `Serialized Value` from the CAS, deserialize it, inject it into the Skyframe graph, and return.
3.  **Fallback**: If no candidates are valid (or index is empty), we fall back to local evaluation.
    *   After local evaluation, we compute `NSH(N) = Hash(Key(N) + NSH(D_1) + NSH(D_2) + ...)`.
    *   Write the new value and metadata to the **CAS** keyed by `NSH(N)`.
    *   Append `NSH(N)` to the candidate list for `SkyKey(N)` in the **Index**.

#### The Challenge: Network Round-Trip Storm (RTT Storm)
In a remote cache setup, recursively validating dependencies top-down requires sequential network requests proportional to the graph depth ($O(\text{depth})$). Even with low latency, this "RTT Storm" can destroy performance.
*   **Mitigation**: For the local disk cache MVP, RTT is negligible. For remote caches, we rely on **Git-based Graph Pruning** to mark large sub-graphs as clean in bulk, bypassing remote lookups.

### 5. Glob Invalidation and Correctness

A key correctness requirement is that adding or removing files must correctly invalidate cached packages/targets that use globs.

*   **Analysis of Glob Invalidation**:
    *   In Skyframe, `GlobValue` nodes depend on `DirectoryListingValue` nodes.
    *   `DirectoryListingValue` nodes depend on `DirectoryListingStateValue` nodes, which represent the actual directory contents on disk.
    *   If a file is added, removed, or renamed, the `DirectoryListingStateValue` (and thus `DirectoryListingValue`) of its parent directory changes.
    *   This change propagates to the `GlobValue` that performed the listing.
    *   During package loading, the `PackageValue` registers dependencies on all `GlobValue`s evaluated during `BUILD` file execution.
    *   Therefore, any filesystem change affecting a glob will transitively change the NSH of the `PackageValue` and any `ConfiguredTargetValue` in that package.
*   **Verification Case: Adding a matching file in a new subdirectory**:
    *   Suppose we have `glob(["**/*.txt"])` in `foo/BUILD`.
    *   We add `foo/new_dir/bar.txt`.
    *   The directory listing of `foo/` changes because it now contains `new_dir`.
    *   Since `GlobValue(foo, **/*.txt)` depended on `DirectoryListingValue(foo)`, and that listing changed, the `GlobValue` NSH changes.
    *   This correctly invalidates the cache, and a rebuild will discover `foo/new_dir/bar.txt` and create a new dependency on `GlobValue(foo/new_dir, **/*.txt)`.
*   **Conclusion**: The NSH-based propagation correctly handles glob invalidation because the dependency graph structure (tracked via Skyframe) naturally links packages to the directory listings they depend on. No special glob handling is needed in the caching layer other than ensuring `DirectoryListingStateValue` and `GlobValue` have correct NSH implementations.

---

## Performance Optimizations

### 1. Git-Based Graph Pruning via Graph Manifest

To make remote client-side validation performant and avoid the sequential network lookup overhead (RTT Storm) for unchanged parts of the graph, we introduce the **Graph Manifest**:

1.  **The Graph Manifest**: When a build is cached (e.g., by CI), the cache writer uploads a compact **Graph Manifest** representing the dependency structure (edges) and NSHs of the graph at that baseline commit. This manifest only contains metadata (no large serialized values) and is small enough to be downloaded in a single request ($\approx 10\text{-}20\text{MB}$ for large projects).
2.  **Local Diff Computation**: At startup, the client identifies the baseline commit $V_{target}$ (see Section 2 below) and runs:
    ```bash
    git diff --name-only V_{target}
    ```
    This returns the list of files modified in the workspace relative to the cached baseline.
    *   *Optimization*: If Git's `fsmonitor` is enabled, this diff is returned instantly ($<50\text{ms}$).
3.  **In-Memory Reachability Analysis**: The client downloads the Graph Manifest, loads it into memory, and performs a reverse-dependency traversal starting from the modified files.
    *   Any node that can transitively reach a modified file is marked as **dirty**.
    *   All other nodes are marked as **clean**.
4.  **Bulk Validation**: 
    *   For all **clean** nodes, their cached state is guaranteed to be valid. Bazel registers their NSHs from the manifest as valid. When Skyframe requests these nodes, Bazel directly fetches their values from the CAS using these NSHs, bypassing all recursive validation calls.
    *   Only nodes in the **dirty** subgraph (the frontier of changes) are validated recursively using the Index + CAS flow.

### 2. Target Cache Commit Selection ($V_{target}$)

When starting a build at $V_{client}$, the client must choose which cached commit to validate against. We use a **Recorded Baseline** approach for $O(1)$ startup:

1.  **Sync Time Registration**: The organization's sync tool writes the synced green commit hash $A$ to `.bazel_baseline_commit`.
2.  **Bazel Startup**: Bazel reads $V_{target} = A$ from the file.
3.  **Sanity Check**: Bazel runs `git merge-base --is-ancestor V_{target} HEAD` ($<5\text{ms}$ using Git `commit-graph`).
4.  **Result**: If valid, Bazel runs `git diff --name-only V_{target}` to obtain the change list for pruning.
5.  **Fallback**: If the check fails (e.g., user manual branch switch), Bazel falls back to traversing history (up to 1000 commits) to find the closest ancestor primed in the Cache Index.

### 3. Local Cache I/O vs. Re-analysis Performance

For a local disk cache, we avoid network latency, but we introduce disk I/O for validation.
*   **The Trade-off**: Validating a node requires reading its metadata from disk (via NSH lookup in CAS) to get its dependencies. If the target has a deep dependency tree, we might perform many small disk reads.
*   **Performance Goal**: The time to recursively read NSHs from disk must be significantly less than the time to re-run the Starlark analysis for those targets.
*   **Mitigation**: 
    *   Use a fast local key-value store (e.g., SQLite or a specialized LSM-tree-based store) to minimize lookup latency.
    *   Even with disk I/O, avoiding Starlark evaluation (which involves JVM execution, class loading, and memory allocation) is expected to be much faster. We must measure this in Phase 5 of the MVP.
    *   **Local Iteration Optimization (Daemon Restarts)**: When the Bazel daemon restarts, we can avoid scanning the entire local cache by leveraging a filesystem watcher or Git's `fsmonitor` integration (`core.fsmonitor`). 
        *   If we can obtain a verified list of file changes since the last build timestamp $T_{last\_build}$, we can perform the same pruning logic locally.
        *   If no files affecting the analysis phase have changed, we can validate the entire local graph in $O(1)$ time and restore it instantly, making daemon restarts transparent.

### 4. Index Growth and Eviction

Since the **Index** (`SkyKey -> List<NSH>`) stores multiple NSHs per key to support branch switching, it will grow over time.
*   **Validation Overhead**: If a `SkyKey` has many candidate NSHs, and the current workspace state matches an older version, we might have to validate multiple candidates before finding the correct one.
*   **Mitigation**:
    *   **LIFO Ordering**: Always check the most recent candidate NSH first. In most developer workflows, they iterate on the current branch or switch to a recent branch, so the correct NSH is likely near the top of the list.
    *   **Eviction Policy**: Implement an eviction policy (e.g., LRU or maximum list size) for the candidate list in the Index to prevent unbounded growth and limit the worst-case validation attempts.

---

## MVP Strategy: Local-First Correctness & Real Key Serialization

To ensure a fast and risk-mitigated path to a working MVP, we adopt the following strategy:

1.  **Local-First Verification**: We target a local disk cache. This eliminates network latency (RTT Storm) as a variable, allowing us to focus purely on the correctness of Merkle invalidation.
2.  **Real Key Serialization**: We use Bazel's mature `ObjectCodecs` framework to serialize `SkyKey`s for NSH computation. This ensures machine-independent hashes from the start, enabling future cross-machine sharing tests.
3.  **No Server-Side Changes**: We perform all validation on the client (Bazel), using the local disk cache as a passive key-value store (NSH -> Serialized Value + Deps).

## Detailed MVP Implementation Plan

We break down the work into 5 sequential, reviewable phases.

### Phase 1: Graph Extension & NSH Storage
Inject Bazel-specific node entries to store the 128-bit NSH (2 `long` fields) without affecting Blaze memory.
1.  **Define `NshNodeEntry` Interface**:
    Define a simple interface for nodes that store NSH:
    ```java
    public interface NshNodeEntry {
      long getNshLow();
      long getNshHigh();
      void setNsh(long low, long high);
    }
    ```
2.  **Implement `BazelIncrementalInMemoryNodeEntry`**:
    Subclass `IncrementalInMemoryNodeEntry` to implement `NshNodeEntry` and add `nshLow` and `nshHigh` fields.
3.  **Implement `BazelInMemoryGraphImpl`**:
    Subclass `InMemoryGraphImpl` and override `newNodeEntry` to instantiate `BazelIncrementalInMemoryNodeEntry` when `keepEdges` is true.
4.  **Inject Graph Factory**:
    *   Modify `SequencedSkyframeExecutor.Builder` to accept a `graphFactory` function: `BiFunction<Boolean, Boolean, InMemoryGraph>`.
    *   Update `SequencedSkyframeExecutor.createEvaluator` to use this factory.
    *   Configure the factory in `BazelSkyframeExecutorConstants` to return `BazelInMemoryGraphImpl` for Bazel.
    *   Add a constructor to `InMemoryMemoizingEvaluator` to accept `InMemoryGraph` directly.

### Phase 2: Key Serializer Injection & NSH Computation Flow
Inject the key serializer into Skyframe evaluation and compute NSH when nodes transition to "done".
1.  **Extend `EvaluationContext`**:
    *   Add `Function<SkyKey, byte[]> keySerializer` field and builder method to `EvaluationContext`.
    *   Pass this serializer to `ParallelEvaluatorContext` and then to `SkyFunctionEnvironment`.
2.  **Configure Serializer in Bazel**:
    *   In `SkyframeExecutor`, store `SerializationDependenciesProvider` (set by `BuildTool` per command).
    *   In `SkyframeExecutor.newEvaluationContextBuilder`, set the `keySerializer` lambda that uses `SerializationDependenciesProvider.getObjectCodecs()` to serialize keys.
3.  **Implement `computeNsh` in `SkyFunctionEnvironment`**:
    *   Add NSH computation logic in `SkyFunctionEnvironment.commitAndGetParents` just before `primaryEntry.setValue`.
    *   For leaf nodes (`FILE_STATE`): Use `FileStateValue.getValueFingerprint()`.
    *   For leaf nodes (`DIRECTORY_LISTING_STATE`): Compute fingerprint from child dirents (name + type).
    *   For evaluation nodes: Compute MurmurHash3 of `keySerializer.apply(key)` + NSH of direct dependencies (retrieved from `primaryEntry.getTemporaryDirectDeps()`).
    *   Set the computed NSH on `primaryEntry` if it implements `NshNodeEntry`.

### Phase 3: Correctness Verification (NSH Invariant Tests)
Verify that NSH changes propagate correctly.
1.  **Add `BazelSkycacheCorrectnessTest`**:
    *   Write integration tests that trigger builds and inspect the in-memory graph.
    *   Verify that:
        *   All nodes in Bazel build are `BazelIncrementalInMemoryNodeEntry` and have non-zero NSH.
        *   Modifying a source file changes its `FILE_STATE` NSH.
        *   This change transitively propagates to all parent nodes (Packages, Configured Targets) that depend on it, changing their NSH.
        *   Unrelated nodes keep their NSH.

### Phase 4: Local Cache Integration (Write Path)
Serialize and store evaluated nodes to local disk.
1.  **Implement `LocalSkycacheWriter`**:
    *   Hook into Skyframe evaluation completion (e.g., in `SkyframeExecutor` or via a listener).
    *   For completed nodes, serialize `(SkyValue, List<SkyKey> directDeps)` using Bazel's existing serialization framework.
    *   Store the serialized blob in a local disk directory using the node's `InputNSH` (hex string) as the filename.

### Phase 5: Speculative Retrieval & Validation (Read Path)
Reuse cached values for dirty nodes.
1.  **Implement `LocalSkycacheReader`**:
    *   Before evaluating a dirty node `N` in `ParallelEvaluator`:
        *   Retrieve its recorded dependencies `D` from the in-memory graph.
        *   Compute `NSH_speculative(N) = Hash(keySerializer.apply(N) + current_NSH(D))`.
        *   Look up `NSH_speculative(N)` in the local disk cache.
        *   If hit:
            *   Deserialize `(Value, ActualDeps)`.
            *   Verify `ActualDeps` matches `D`.
            *   If verified, inject `Value` into the graph, mark `N` as done, and skip evaluation.
        *   If miss: Proceed with normal evaluation.

---


## Other Major Technical Questions

Beyond invalidation, several high-risk technical areas need investigation before implementing Skycache in Bazel:

### 1. Serialization Framework Maturity
*   **Status**: Bazel's open-source codebase already makes extensive use of the `@AutoCodec` annotation on critical analysis-phase classes (e.g., `RuleConfiguredTarget`, `InputFileConfiguredTarget`, `AspectValue`, `BuildConfigurationValue`). This indicates the core serialization framework is structurally present and mature.
*   **Remaining Risks**: 
    *   **Completeness**: If any transitively referenced class in a configured target's graph lacks a codec, serialization will fail. We must verify that all common rule implementations and their internal states are fully serializable.
    *   **Bitrot**: Since Skycache is not enabled by default in open-source Bazel, these codecs may not be regularly exercised in integration tests, leading to potential regression bugs that we must identify.
*   **Task**: Create a test case in the MVP that attempts to serialize and deserialize a complete `ConfiguredTargetValue` for a representative Java/C++ target.

### 2. Action Registration
*   **Status**: **Solved Problem**. In Bazel's Skyframe-driven execution model, the execution phase does not rely on a separate global action registry. Instead, the execution functions (e.g., `ActionExecutionFunction`) evaluate `ActionLookupData` keys by querying Skyframe directly for the corresponding `ActionLookupValue` (the parent class of `ConfiguredTargetValue` and `AspectValue`).
*   **Implication**: By simply restoring the deserialized `ConfiguredTargetValue` node into the local Skyframe graph, the actions are automatically "registered" and visible to the execution phase. No extra integration work is required.


### 3. External Repositories (Bzlmod & Workspaces)
*   **Status**: You are correct that Bzlmod and workspace rules are fully integrated into Skyframe. External repositories are represented by `RepositoryDirectoryValue` nodes, and files within them are tracked by `FileStateValue` nodes that transitively depend on the repository creation node. Correctness is therefore handled automatically by the Merkle tree.
*   **The Optimization Challenge**: 
    *   **Git Bypass**: External repositories reside in Bazel's output base (outside the main Git repository), so `git diff` cannot be used to verify them.
    *   **File-by-File Overhead**: If we fall back to checking the digests of all files we use from external repositories (like large toolchains or rulesets), it could be slow.
*   **Proposed Solution (Coarse-Grained Invalidation)**:
    *   Treat the `RepositoryDirectoryValue` node as a **validation barrier**.
    *   If the NSH of the `RepositoryDirectoryValue` matches the cached NSH (which means the repository rule definition, attributes in `MODULE.bazel`, and transitive lockfile dependencies have not changed), we assume the entire repository content is valid.
    *   This allows us to skip digest checks for all files *inside* that external repository, treating the repository as a single leaf node for validation purposes.
*   **Task**: Verify in the MVP that the `FileStateValue` nodes for external files correctly depend on their parent `RepositoryDirectoryValue` node, ensuring the Merkle tree propagates changes correctly.

### 4. Global Configuration & Starlark Toolchains
*   **Question**: How do we capture changes to global configuration (Bazel flags), command-line options, and Starlark toolchains in the cache key?
*   **Risk**: A change in flags (e.g., `-c opt` vs `-c dbg`) or a Starlark rule change must invalidate the cache. We need to ensure `FrontierNodeVersion`-like metadata is correctly mapped in Bazel.

