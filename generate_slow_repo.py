import os
import random

repo_dir = "/tmp/slow_repo"
os.makedirs(repo_dir, exist_ok=True)

# Create WORKSPACE
with open(os.path.join(repo_dir, "WORKSPACE"), "w") as f:
    f.write('workspace(name = "slow_repo")\n')

# Create rules.bzl with a slow rule
with open(os.path.join(repo_dir, "rules.bzl"), "w") as f:
    f.write('''
MyInfo = provider()

def _slow_rule_impl(ctx):
    # Dummy work to slow down analysis using string concatenation (O(N^2))
    x = ""
    for i in range(10000):
        x += str(i)
    return [DefaultInfo(), MyInfo(val = x)]

slow_rule = rule(
    implementation = _slow_rule_impl,
    attrs = {
        "srcs": attr.label_list(allow_files = True),
        "deps": attr.label_list(),
    },
)
''')

# Create BUILD at root
with open(os.path.join(repo_dir, "BUILD"), "w") as f:
    pass

# Parameters to control repo size and complexity
num_packages = 30
targets_per_package = 100
num_deps = 10

random.seed(42)

for i in range(num_packages):
    pkg_dir = os.path.join(repo_dir, f"pkg_{i}")
    os.makedirs(pkg_dir, exist_ok=True)
    with open(os.path.join(pkg_dir, "BUILD"), "w") as f:
        f.write('load("//:rules.bzl", "slow_rule")\n\n')
        for j in range(targets_per_package):
            deps = []
            if i > 0:
                # depend on N targets from previous package
                dep_indices = random.sample(range(targets_per_package), num_deps)
                for dep_j in dep_indices:
                    deps.append(f'"//pkg_{i-1}:target_{dep_j}"')
            
            f.write(f'''
slow_rule(
    name = "target_{j}",
    deps = [{", ".join(deps)}],
    visibility = ["//visibility:public"],
)
''')

# Create a top-level target in the root BUILD that depends on the last package
with open(os.path.join(repo_dir, "BUILD"), "a") as f:
    f.write('load("//:rules.bzl", "slow_rule")\n\n')
    deps = [f'"//pkg_{num_packages-1}:target_{j}"' for j in range(targets_per_package)]
    f.write(f'''
slow_rule(
    name = "target",
    deps = [{", ".join(deps)}],
    visibility = ["//visibility:public"],
)
''')
