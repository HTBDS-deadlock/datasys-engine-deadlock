#!/usr/bin/env bash
# Prepares the log analysis (exercise 12): reads the per-configuration engine
# logs (left untouched) and writes
#   generated/joinbench/logs.csv  all lines of the four logs in one file
#   bench/runs.csv                one line per run: sessionId,config,repetition
# Run from the project root.
set -euo pipefail

# log file -> configuration name
declare -A CONFIGS=(
  [logs/engine_syntax_cust_ship_order.log]=min
  [logs/engine_syntax_orders_ship_cust.log]=max
  [logs/engine_optimi_cust_ship_order.log]=opt_min_from
  [logs/engine_optimi_orders_ship_cust.log]=opt_max_from
)
ORDER=(logs/engine_syntax_cust_ship_order.log logs/engine_syntax_orders_ship_cust.log
       logs/engine_optimi_cust_ship_order.log logs/engine_optimi_orders_ship_cust.log)

mkdir -p generated/joinbench
: > generated/joinbench/logs.csv
: > bench/runs.csv

for f in "${ORDER[@]}"; do
  [ -f "$f" ] || { echo "ERROR: $f not found" >&2; exit 1; }
  cat "$f" >> generated/joinbench/logs.csv
  # One session per run; the order of SELECT lines is the repetition number.
  grep 'statement=SELECT tables=' "$f" | cut -d, -f2 \
    | awk -v c="${CONFIGS[$f]}" '{ print $0 "," c "," NR }' >> bench/runs.csv
done

echo "logs.csv: $(wc -l < generated/joinbench/logs.csv) lines"
echo "runs.csv: $(wc -l < bench/runs.csv) runs"
