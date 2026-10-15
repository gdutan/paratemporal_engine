"""
Merge-vs-variance experiment for n-step sampled lookahead ("Monte Carlo at every stage, merge relevant branches").

Problem: Sutton & Barto's 19-state random walk (states 1..19, terminals 0 and 20). From a nonterminal state, the
walk moves left or right with probability 1/2. Rewards: -1 on entering 0, +1 on entering 20, plus a small step
reward s'/100 for the state entered, so that accumulated rewards differ between paths, as in real RL. The target is
the n-step return G = sum of rewards over n steps + V(s_n), with V(s) = (s-10)/10 as the bootstrap value. Its
exact mean is computed by exhaustive expansion with merging (dynamic programming).

Estimators (all should be unbiased; we compare variance per unit of sampling work):
  mc         N independent rollouts, each weight 1/N                    (classic sample paths)
  tree       k samples per node per stage, weight w/k, no merging       (sparse sampling, k^n leaves)
  tree_safe  as tree, merge nodes with equal (state, reward so far)     (the library's safe merge)
  tree_acc   as tree, merge nodes with equal state; carry the weighted  (accumulator / IH-style merge:
             accumulated reward A = sum w*G_past                         exact for expectations)
  budget     accumulator merge, but each stage draws a fixed budget B,  (merge + re-allocate: keeps the
             spread over merged nodes in proportion to weight,          number of fresh samples per stage)
             at least one each, child weight w/n_i

Work = number of transition samples drawn. Efficiency = variance * work (lower is better; equal efficiency
means equal accuracy for equal compute).

Run:  python rl/experiments/merge_variance.py         (standard library + numpy; about a minute)
"""
import numpy as np

LEFT_T, RIGHT_T, START = 0, 20, 13
GAMMA = 1.0


def terminal(s):
    return s == LEFT_T or s == RIGHT_T


def reward(s_next):
    r = s_next / 100.0
    if s_next == LEFT_T:
        r += -1.0
    elif s_next == RIGHT_T:
        r += 1.0
    return r


def v_boot(s):
    return 0.0 if terminal(s) else (s - 10) / 10.0


def step(s, rng):
    return s + (1 if rng.random() < 0.5 else -1)


def exact_target(n):
    """Exhaustive expansion with accumulator merging = exact expectation (no sampling). Returns (value, work)."""
    nodes = {START: (1.0, 0.0)}  # state -> (W, A)
    work = 0
    for _ in range(n):
        nxt = {}
        for s, (W, A) in nodes.items():
            if terminal(s):
                w, a = nxt.get(s, (0.0, 0.0)); nxt[s] = (w + W, a + A); continue
            for s2 in (s - 1, s + 1):
                work += 1
                cw, ca = W * 0.5, A * 0.5 + W * 0.5 * reward(s2)
                w, a = nxt.get(s2, (0.0, 0.0)); nxt[s2] = (w + cw, a + ca)
        nodes = nxt
    return sum(A + W * v_boot(s) for s, (W, A) in nodes.items()), work


def est_mc(n, N, rng):
    work, total = 0, 0.0
    for _ in range(N):
        s, g = START, 0.0
        for _ in range(n):
            if terminal(s):
                break
            s = step(s, rng); work += 1; g += reward(s)
        total += g + v_boot(s)
    return total / N, work


def est_tree(n, k, rng, key):
    """key: None (no merge), 'safe' (state, reward so far), 'acc' (state only, accumulator)."""
    # node: (state, W, A, G_past) ; G_past only meaningful for unmerged/safe nodes
    nodes = [(START, 1.0, 0.0, 0.0)]
    work = 0
    for _ in range(n):
        children = []
        for s, W, A, G in nodes:
            if terminal(s):
                children.append((s, W, A, G)); continue
            for _ in range(k):
                s2 = step(s, rng); work += 1
                r = reward(s2)
                children.append((s2, W / k, A / k + (W / k) * r, G + r))
        if key is None:
            nodes = children
        else:
            groups = {}
            for s, W, A, G in children:
                kk = (s, round(G, 12)) if key == 'safe' else s
                if kk in groups:
                    s0, W0, A0, G0 = groups[kk]; groups[kk] = (s0, W0 + W, A0 + A, G0)
                else:
                    groups[kk] = (s, W, A, G)
            nodes = list(groups.values())
    return sum(A + W * v_boot(s) for s, W, A, G in nodes), work


def est_budget(n, B, rng):
    """Accumulator merge with a per-stage sampling budget B allocated in proportion to weight (>= 1 each)."""
    nodes = {START: (1.0, 0.0)}
    work = 0
    for _ in range(n):
        live = [(s, W, A) for s, (W, A) in nodes.items() if not terminal(s)]
        done = [(s, W, A) for s, (W, A) in nodes.items() if terminal(s)]
        Wlive = sum(W for _, W, _ in live)
        nxt = {}
        for s, W, A in done:
            w, a = nxt.get(s, (0.0, 0.0)); nxt[s] = (w + W, a + A)
        for s, W, A in live:
            m = max(1, int(round(B * W / Wlive))) if Wlive > 0 else 1
            for _ in range(m):
                s2 = step(s, rng); work += 1
                cw, ca = W / m, A / m + (W / m) * reward(s2)
                w, a = nxt.get(s2, (0.0, 0.0)); nxt[s2] = (w + cw, a + ca)
        nodes = nxt
    return sum(A + W * v_boot(s) for s, (W, A) in nodes.items()), work


def run(name, fn, reps, rng):
    vals, works = [], []
    for _ in range(reps):
        v, w = fn(rng)
        vals.append(v); works.append(w)
    vals = np.array(vals)
    return name, vals.mean(), vals.std(ddof=1) / np.sqrt(reps), vals.var(ddof=1), float(np.mean(works))


def main():
    n, k, reps = 6, 3, 1000
    rng = np.random.default_rng(2026)
    truth, exact_work = exact_target(n)
    rows = [("exhaustive + accumulator merge (exact)", truth, 0.0, 0.0, float(exact_work))]
    rows.append(run(f"tree      k={k} no merge", lambda r: est_tree(n, k, r, None), reps, rng))
    rows.append(run(f"tree_safe k={k} safe merge", lambda r: est_tree(n, k, r, 'safe'), reps, rng))
    rows.append(run(f"tree_acc  k={k} accumulator merge", lambda r: est_tree(n, k, r, 'acc'), reps, rng))
    w_acc = rows[-1][4]
    w_tree = rows[1][4]   # rows[0] is the exhaustive reference
    B = max(1, int(round(w_acc / n)))
    rows.append(run(f"budget    B={B}/stage (= tree_acc work)", lambda r: est_budget(n, B, r), reps, rng))
    B2 = max(1, int(round(w_tree / n)))
    rows.append(run(f"budget    B={B2}/stage (= tree work)", lambda r: est_budget(n, B2, r), reps, rng))
    N1 = max(1, int(round(w_acc / n)))
    rows.append(run(f"mc        N={N1} (~ tree_acc work)", lambda r: est_mc(n, N1, r), reps, rng))
    N2 = max(1, int(round(w_tree / n)))
    rows.append(run(f"mc        N={N2} (~ tree work)", lambda r: est_mc(n, N2, r), reps, rng))

    print(f"19-state random walk, start {START}, n={n}-step target, {reps} replications")
    print(f"exact target (exhaustive + accumulator merge): {truth:.6f}\n")
    print(f"{'estimator':40s} {'mean':>9s} {'(se)':>8s} {'bias/se':>8s} {'variance':>11s} {'work':>8s} {'var*work':>10s}")
    for name, mean, se, var, work in rows:
        bias = f"{(mean - truth) / se:8.2f}" if se > 0 else f"{'exact':>8s}"
        print(f"{name:40s} {mean:9.5f} {se:8.5f} {bias} {var:11.3e} {work:8.0f} {var * work:10.3e}")


if __name__ == "__main__":
    main()
