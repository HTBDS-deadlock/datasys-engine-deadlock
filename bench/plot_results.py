#!/usr/bin/env python3
"""Turns the benchmark logs into the two report deliverables (exercise 12).

Inputs (run bench/make_runs.sh and bench/analysis.sql first):
  generated/joinbench/logs.csv   the four benchmark logs, concatenated
  bench/runs.csv                 sessionId,config,repetition
  bench/analysis_result.csv      the engine's own join of logs and runs

Outputs:
  bench/runtime.png              durationMs per configuration, every run + median
  bench/runtime_summary.csv      n, median, min, max per configuration
  bench/intermediate_sizes.md    estRows vs rowsOut per join and configuration
"""
import csv
import re
import statistics

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

CONFIGS = ["min", "max", "opt_min_from", "opt_max_from"]
LABELS = {
    "min": "min\n-j syntax\ncust, ship, ord",
    "max": "max\n-j syntax\nord, ship, cust",
    "opt_min_from": "optimized\nFROM cust, ship, ord",
    "opt_max_from": "optimized\nFROM ord, ship, cust",
}


def field(message, name):
    m = re.search(rf"\b{name}=(\S+)", message)
    return m.group(1) if m else None


def main():
    runs = {}
    with open("bench/runs.csv", encoding="utf-8") as f:
        for session, config, rep in csv.reader(f):
            runs[session] = (config, int(rep))

    # Runtime: the Executor's statement=SELECT line of each benchmark session.
    durations = {c: [] for c in CONFIGS}
    with open("generated/joinbench/logs.csv", encoding="utf-8") as f:
        for row in csv.reader(f):
            if len(row) < 7 or row[1] not in runs or "statement=SELECT tables=" not in row[6]:
                continue
            durations[runs[row[1]][0]].append(int(field(row[6], "durationMs")))

    with open("bench/runtime_summary.csv", "w", encoding="utf-8", newline="\n") as f:
        f.write("config,n,median_ms,min_ms,max_ms\n")
        for c in CONFIGS:
            d = durations[c]
            f.write(f"{c},{len(d)},{statistics.median(d):g},{min(d)},{max(d)}\n")

    # Intermediate sizes: from the engine's own join of logs and runs.
    joins = {}
    with open("bench/analysis_result.csv", encoding="utf-8") as f:
        for row in csv.reader(f):
            if len(row) < 10 or row[5] != "HashJoinOperator":
                continue
            config, message = row[8], row[6]
            key = (config, field(message, "tables"))
            joins.setdefault(key, set()).add((int(field(message, "estRows")), int(field(message, "rowsOut"))))

    rows = []
    for c in CONFIGS:
        for (config, tables), values in sorted(joins.items(), key=lambda kv: len(kv[0][1])):
            if config != c:
                continue
            for est, out in sorted(values):
                note = "" if len(values) == 1 else " (varies across runs)"
                rows.append([c, tables.replace(';', ' join '), f"{est:,}", f"{out:,}", f"{est / out:.2f}{note}"])

    # Padded columns, so the table is readable as plain text too.
    header = ["Configuration", "Join (build side first)", "estRows", "rowsOut", "est / actual"]
    widths = [max(len(r[i]) for r in rows + [header]) for i in range(len(header))]
    right = [False, False, True, True, True]

    def line(cells):
        return "| " + " | ".join(c.rjust(w) if r else c.ljust(w) for c, w, r in zip(cells, widths, right)) + " |\n"

    with open("bench/intermediate_sizes.md", "w", encoding="utf-8", newline="\n") as f:
        f.write(line(header))
        f.write("|" + "|".join(("-" * (w + 1) + ":") if r else ("-" * (w + 2)) for w, r in zip(widths, right)) + "|\n")
        for r in rows:
            f.write(line(r))

    # Chart: one dot per run, a median tick per configuration.
    ink, muted, grid, series = "#0b0b0b", "#52514e", "#e4e3df", "#2a78d6"
    fig, ax = plt.subplots(figsize=(7.5, 4.5), dpi=200)
    for i, c in enumerate(CONFIGS):
        d = sorted(durations[c])
        offsets = [(-0.12 + 0.06 * k) for k in range(len(d))]
        ax.scatter([i + o for o in offsets], d, s=36, color=series, edgecolor="white", linewidth=1.5, zorder=3)
        med = statistics.median(d)
        ax.hlines(med, i - 0.25, i + 0.25, color=ink, linewidth=2, zorder=4)
        ax.annotate(f"median {med:g} ms", (i + 0.28, med), va="center", fontsize=8, color=muted)

    ax.set_xticks(range(len(CONFIGS)))
    ax.set_xticklabels([LABELS[c] for c in CONFIGS], fontsize=8, color=ink)
    ax.set_xlim(-0.5, len(CONFIGS) - 0.1)
    ax.set_ylim(0, max(max(v) for v in durations.values()) * 1.15)
    ax.set_ylabel("End-to-end runtime, durationMs (ms)", fontsize=9, color=ink)
    ax.set_title("Join-order benchmark: runtime per configuration (5 runs each, cold JVM)",
                 fontsize=10, color=ink, loc="left")
    ax.grid(axis="y", color=grid, linewidth=0.8)
    ax.set_axisbelow(True)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(muted)
    ax.tick_params(colors=muted, labelsize=8)
    fig.tight_layout()
    fig.savefig("bench/runtime.png")

    print(open("bench/runtime_summary.csv", encoding="utf-8").read())
    print(open("bench/intermediate_sizes.md", encoding="utf-8").read())


if __name__ == "__main__":
    main()
