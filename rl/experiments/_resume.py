"""
Incremental saving for long experiment grids: rows are written to the results JSON after every (method, n) block,
and a rerun skips blocks already present. A killed run therefore loses at most one block.
"""
import argparse
import json
import os


def args(default_runs):
    ap = argparse.ArgumentParser()
    ap.add_argument("--methods", nargs="*", default=None, help="subset of methods to run (default: all)")
    ap.add_argument("--runs", type=int, default=default_runs)
    ap.add_argument("--plot-only", action="store_true", help="only write CSV/figure from saved rows")
    return ap.parse_args()


def load(path, meta):
    if os.path.exists(path):
        d = json.load(open(path))
        if any(d.get(k) != v for k, v in meta.items()):
            raise SystemExit(f"{path} was produced with different settings {[(k, d.get(k)) for k in meta]}; "
                             f"move it aside to start over")
        return d["rows"]
    return []


def save(path, meta, rows):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(dict(meta, rows=rows), f, indent=1)
    os.replace(tmp, path)


def done(rows, method, n):
    return any(r["method"] == method and r["n"] == n for r in rows)
