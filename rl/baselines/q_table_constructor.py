"""
Headless port of Demo Deliverable/ParaDEVS_Barb_Q_Constructor.ipynb (training cells 2, 3, 5, 6), so the Q-table
policies used by the Java engine can be regenerated locally without Colab.

The logic is kept as in the notebook (numpy in place of torch; same action map, reward, exploration schedule,
row-normalisation). Differences: a seed for reproducibility, file paths as arguments, and no plots.

Notebook semantics worth knowing (see the RL plan, Section "Progress"):
  * actions: 0 hold, 1 sell one share, 2 buy one share (per stock); shares are clipped to [0, max_shares]
  * reward = 1 if the new holding equals the reachable holding of largest value  sum(shares * price)  at the current
    date, else 0. With positive prices that is always the largest share count, so the reward amounts to "buy"
    (or hold at the cap), independent of price movements
  * the date index moves backwards one row per step from a random start
  * the saved "Q-table" is Q divided by its row sum (a stochastic policy proportional to Q); unvisited rows uniform

Run from rl/:
  .venv/Scripts/python.exe baselines/q_table_constructor.py --data "../../Demo Deliverable/ParaDEVS/src/Models/data/input/Train_Valid/Single_Stock/Train_AAPL.csv" --max-shares 15 --out results/Q_table_AAPL_1_15.csv
"""
import argparse

import numpy as np
import pandas as pd


class QLearning:
    def __init__(self, num_states, num_actions, rng, learning_rate=0.0001, discount_factor=0.9, exploration_rate=0.99):
        self.num_actions = num_actions
        self.learning_rate = learning_rate
        self.discount_factor = discount_factor
        self.exploration_rate = exploration_rate
        self.q_table = np.zeros((num_states, num_actions))
        self.rng = rng

    def choose_action(self, state):
        if self.rng.random() < self.exploration_rate:
            return int(self.rng.integers(0, self.num_actions))
        return int(np.argmax(self.q_table[state]))           # ties -> first index, as torch.argmax

    def update_q_table(self, state, action, reward, next_state):
        best_next = int(np.argmax(self.q_table[next_state]))
        td_target = reward + self.discount_factor * self.q_table[next_state, best_next]
        self.q_table[state, action] += self.learning_rate * (td_target - self.q_table[state, action])


def state_to_index(state, base):
    return sum(s * base ** i for i, s in enumerate(state))


class StockEnvironment:
    def __init__(self, stocks, max_shares, filename, rng):
        self.data = pd.read_csv(filename).values
        self.num_states = (max_shares + 1) ** stocks
        self.num_actions = 2 * stocks + 1
        self.price = self.data[:, 1:stocks + 1].astype(float)
        self.max_shares, self.stocks, self.rng = max_shares, stocks, rng
        self.action_map = {0: (0,) * stocks}
        idx = 1
        for i in range(stocks):
            for d in (-1, 1):                                  # sell, buy
                act = [0] * stocks; act[i] = d
                self.action_map[idx] = tuple(act); idx += 1
        self.current_state = (0,) * stocks
        self.date_index = 0

    def reset(self):
        self.current_state = (0,) * self.stocks
        self.date_index = int(self.rng.integers(0, self.data.shape[0] - self.max_shares - 1))
        return self.current_state

    def _apply(self, state, act):
        return tuple(max(min(s + c, self.max_shares), 0) for s, c in zip(state, act))

    def step(self, action):
        before = self.current_state
        possible = [before] + [self._apply(before, a) for a in self.action_map.values()]
        self.current_state = self._apply(before, self.action_map[action])
        max_value, target = 0, before
        for st in possible:
            v = float(np.dot(st, self.price[self.date_index]))
            if v > max_value:
                max_value, target = v, st
        self.date_index -= 1
        reward = 1 if self.current_state == target else 0
        return self.current_state, reward


def train(data, stocks=1, max_shares=6, episodes=10000, seed=0):
    rng = np.random.default_rng(seed)
    env = StockEnvironment(stocks, max_shares, data, rng)
    agent = QLearning(env.num_states, env.num_actions, rng)
    base = max_shares + 1
    for _ in range(episodes):
        state = env.reset()
        for _ in range(max_shares):                            # max_steps = max_shares
            s_idx = state_to_index(state, base)
            action = agent.choose_action(s_idx)
            nxt, reward = env.step(action)
            agent.update_q_table(s_idx, action, reward, state_to_index(nxt, base))
            state = nxt
        agent.exploration_rate = max(0.1, agent.exploration_rate - 0.0001)
    return agent.q_table


def to_policy_csv(q, stocks, path):
    df = pd.DataFrame(q)
    df = df.div(df.sum(axis=1), axis=0).fillna(1 / (2 * stocks + 1))
    df.to_csv(path, index_label=None)
    return df


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--stocks", type=int, default=1)
    ap.add_argument("--max-shares", type=int, default=6)
    ap.add_argument("--episodes", type=int, default=10000)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    q = train(a.data, a.stocks, a.max_shares, a.episodes, a.seed)
    df = to_policy_csv(q, a.stocks, a.out)
    print(df.head(6).round(4).to_string())
    print(f"wrote {a.out}")
