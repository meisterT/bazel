import os
import random
from collections import defaultdict

repo_dir = "/tmp/slow_repo"
os.makedirs(repo_dir, exist_ok=True)

# Create WORKSPACE
with open(os.path.join(repo_dir, "WORKSPACE"), "w") as f:
    f.write('workspace(name = "slow_repo")\n')

# Create rules.bzl with a slow rule (using 20000 iterations for ~25-30s baseline)
with open(os.path.join(repo_dir, "rules.bzl"), "w") as f:
    f.write('''
MyInfo = provider()

def _slow_rule_impl(ctx):
    # Dummy work to slow down analysis
    x = ""
    for i in range(20000):
        x += str(i)
    
    out = ctx.actions.declare_file(ctx.label.name + ".out")
    
    dep_files = []
    for dep in ctx.attr.deps:
        dep_files.append(dep[DefaultInfo].files)
    inputs = depset(transitive = dep_files)
    
    ctx.actions.run_shell(
        outputs = [out],
        inputs = inputs,
        command = "echo 'hello' > " + out.path,
    )
    return [DefaultInfo(files = depset([out])), MyInfo(val = x)]

slow_rule = rule(
    implementation = _slow_rule_impl,
    attrs = {
        "srcs": attr.label_list(allow_files = True),
        "deps": attr.label_list(),
    },
)
''')

# Create BUILD at root (will append top-level target later)
with open(os.path.join(repo_dir, "BUILD"), "w") as f:
    pass

# Graph parameters
num_packages = 30
targets_per_package = 50
num_nodes = num_packages * targets_per_package
random.seed(42)

# Generate scale-free DAG in memory
pool = [0]
edges = defaultdict(list)
in_degree = defaultdict(int)
out_degree = defaultdict(int)

def get_num_deps():
    r = random.random()
    if r < 0.10: return 0
    elif r < 0.30: return 1
    elif r < 0.55: return 2
    elif r < 0.75: return 3
    elif r < 0.90: return 5
    elif r < 0.97: return 10
    else: return 30

window_size = 150

for i in range(1, num_nodes):
    m = get_num_deps()
    m = min(m, i)
    
    if m == 0:
        pool.append(i)
        continue
        
    chosen = set()
    attempts = 0
    while len(chosen) < m and attempts < 100:
        attempts += 1
        if random.random() < 0.9:
            min_idx = max(0, i - window_size)
            max_idx = i - 1
        else:
            min_idx = 0
            max_idx = i - 1
            
        if max_idx < min_idx:
            max_idx = min_idx
            
        valid_pool = [x for x in pool if min_idx <= x <= max_idx]
        
        if random.random() < 0.8 and valid_pool:
            dep = random.choice(valid_pool)
        else:
            dep = random.randint(min_idx, max_idx)
            
        if dep != i:
            chosen.add(dep)
            
    for dep in chosen:
        edges[i].append(dep)
        out_degree[i] += 1
        in_degree[dep] += 1
        pool.append(dep)
        
    pool.append(i)

# Identify root nodes (nodes that nothing depends on)
roots = [n for n in range(num_nodes) if in_degree[n] == 0]

# Write package BUILD files
for i in range(num_packages):
    pkg_dir = os.path.join(repo_dir, f"pkg_{i}")
    os.makedirs(pkg_dir, exist_ok=True)
    with open(os.path.join(pkg_dir, "BUILD"), "w") as f:
        f.write('load("//:rules.bzl", "slow_rule")\n\n')
        for j in range(targets_per_package):
            g = i * targets_per_package + j
            dep_labels = []
            for dep_g in edges[g]:
                dep_pkg = dep_g // targets_per_package
                dep_target = dep_g % targets_per_package
                dep_labels.append(f'"//pkg_{dep_pkg}:target_{dep_target}"')
            
            f.write(f'''
slow_rule(
    name = "target_{j}",
    deps = [{", ".join(dep_labels)}],
    visibility = ["//visibility:public"],
)
''')

# Create the top-level target in the root BUILD that depends on all roots of the DAG
with open(os.path.join(repo_dir, "BUILD"), "a") as f:
    f.write('load("//:rules.bzl", "slow_rule")\n\n')
    dep_labels = []
    for r_g in roots:
        r_pkg = r_g // targets_per_package
        r_target = r_g % targets_per_package
        dep_labels.append(f'"//pkg_{r_pkg}:target_{r_target}"')
        
    f.write(f'''
slow_rule(
    name = "target",
    deps = [{", ".join(dep_labels)}],
    visibility = ["//visibility:public"],
)
''')

print(f"Generated realistic DAG repo with {num_nodes} targets (roots connected to top-level //:target)")
