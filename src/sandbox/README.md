# Sandbox

The staging area for testing in VS Code. Put new models and experiment runners here (package `sandbox`, or
sub-packages). They compile together with the library and the examples.

- `RandomWalk.java`: template atomic model (draws its next step on state entry, eager style)
- `RunSandbox.java`: template runner. Exhaustive, safe merge and Monte Carlo, checked against the binomial law.

Run from VS Code: **Run and Debug → "Sandbox: RunSandbox"**, or open any file with a `main` and use
**"Run current file"**. The working directory is the library root, so relative data paths such as
`../Demo Deliverable/...` and `runs/...` resolve the same way as in the scripts.

Once a model here is worth keeping, move it to `src/examples/java/examples/<name>/` with a `Verify*` test and
add it to `scripts/build_library.sh`.
