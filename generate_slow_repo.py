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
def _slow_rule_impl(ctx):
    return [DefaultInfo()]

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

# We want 5 packages, each with 10 targets. Total 50 targets.
num_packages = 5
targets_per_package = 10

random.seed(42)

for i in range(num_packages):
    pkg_dir = os.path.join(repo_dir, f"pkg_{i}")
    os.makedirs(pkg_dir, exist_ok=True)
    with open(os.path.join(pkg_dir, "BUILD"), "w") as f:
        f.write('load("//:rules.bzl", "slow_rule")\n\n')
        for j in range(targets_per_package):
            deps = []
            if i > 0:
                # depend on 5 targets from previous package
                dep_indices = random.sample(range(targets_per_package), 5)
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
