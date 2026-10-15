#!/usr/bin/env bash
# Runs the maxRowsPerPartition experiment end to end.
# Run from the PROJECT ROOT (the engine uses ./data and ./logs relative to it).
# Works in Git Bash on Windows.
#
# For each setting it: edits the constant, rebuilds, resets ./data, loads the
# data with q.sql, then runs the query RUNS times (run 1 = cold, discarded).
# durationMs is read from the engine log (logs/engine.log).
#
# Output: results_<timestamp>.csv (every run) and summary_<timestamp>.csv
# (n / median / mean / min / max of the warm runs per setting).

set -euo pipefail

# Picks the first python command that actually runs (on Windows, "python3" can be a
# Microsoft Store stub that exists but does nothing).
find_python() {
  local c
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1 && "$c" --version >/dev/null 2>&1; then
      echo "$c"
      return
    fi
  done
  echo python
}

# ------------------------------------------------------------------
# CONFIG - check these against your project
# ------------------------------------------------------------------
STORAGE_FILE="src/main/java/dk/itu/datasys/StorageEngine.java"  # file with the constant
CONST_NAME="DEFAULT_MAX_ROWS_PER_PARTITION"   # name of the constant in StorageEngine.java
SETTINGS=(7500 10000 12500 15000 65000)
RUNS=6                          # total runs per setting: 1 cold (discarded) + 5 warm
LOAD_CMD=(./engine -f q.sql)    # creates the table and loads the csv
QUERY='SELECT * FROM trips WHERE distance > 100'   # table name must match q.sql
LOG_FILE="logs/engine.log"      # where the engine writes durationMs
DATA_DIR="data"                 # where the StorageEngine stores its files
PYTHON="${PYTHON:-$(find_python)}"   # override: PYTHON=py bash run_experiment.sh
DATA_CSV="generated/tripz.csv"  # the file q.sql must COPY from (point q.sql here)
DATA_ROWS=2000000
DATA_SEED=42                    # fixed seed: the same data every time
MVN_ARGS="-q -DskipTests"
SHUFFLE=1                       # 1 = random order of settings, 0 = order above
# ------------------------------------------------------------------

STAMP=$(date +%Y%m%d_%H%M%S)
OUT="generated/results_${STAMP}.csv"
SUMMARY="generated/summary_${STAMP}.csv"
TMP_STDOUT=$(mktemp "${TMPDIR:-/tmp}/exp_stdout.XXXXXX")
TMP_STDERR=$(mktemp "${TMPDIR:-/tmp}/exp_stderr.XXXXXX")
SRC_BACKUP=$(mktemp "${TMPDIR:-/tmp}/exp_src.XXXXXX")
DATA_BACKUP=""

[ -f "$STORAGE_FILE" ] || { echo "ERROR: $STORAGE_FILE not found (check STORAGE_FILE)." >&2; exit 1; }
[ -x ./engine ] || { echo "ERROR: ./engine not found or not executable (run from the project root)." >&2; exit 1; }
[ -f q.sql ] || { echo "ERROR: q.sql not found in $(pwd)." >&2; exit 1; }

cp "$STORAGE_FILE" "$SRC_BACKUP"

restore() {
  cp "$SRC_BACKUP" "$STORAGE_FILE"
  rm -rf "${DATA_DIR:?}"
  if [ -n "$DATA_BACKUP" ] && [ -d "$DATA_BACKUP" ]; then
    mv "$DATA_BACKUP" "$DATA_DIR"
  fi
  rm -f "$TMP_STDOUT" "$TMP_STDERR" "$SRC_BACKUP"
  echo "Restored $STORAGE_FILE and $DATA_DIR/ to their original state. Rebuild before using the engine normally."
}
trap restore EXIT

# Move any existing data dir aside; it is put back at the end.
if [ -d "$DATA_DIR" ]; then
  DATA_BACKUP="${DATA_DIR}_backup_${STAMP}"
  mv "$DATA_DIR" "$DATA_BACKUP"
fi

set_partition_size() {
  local value="$1"
  # Replaces the number after "<CONST_NAME> =" (digits, optionally with _ or , separators).
  # Written without sed -i so it behaves the same on macOS, Linux and Git Bash.
  local tmp
  tmp=$(mktemp "${TMPDIR:-/tmp}/exp_edit.XXXXXX")
  sed -E "s/(${CONST_NAME}[[:space:]]*=[[:space:]]*)[0-9_,]+/\1${value}/" "$STORAGE_FILE" > "$tmp"
  cp "$tmp" "$STORAGE_FILE"
  rm -f "$tmp"
  if ! grep -qE "${CONST_NAME}[[:space:]]*=[[:space:]]*${value}" "$STORAGE_FILE"; then
    echo "ERROR: could not set ${CONST_NAME} to ${value} in ${STORAGE_FILE}." >&2
    echo "Check STORAGE_FILE / CONST_NAME and how the constant is written." >&2
    exit 1
  fi
}

extract_duration() {
  # Looks at the log lines written during this run, plus stderr, and returns the
  # last durationMs value. Adjust the regex if your log prints it differently.
  local before="$1"
  { if [ -f "$LOG_FILE" ]; then tail -n +"$((before + 1))" "$LOG_FILE"; fi; cat "$TMP_STDERR"; } \
    | grep -oE 'durationMs"?[[:space:]]*[=:][[:space:]]*[0-9]+' \
    | grep -oE '[0-9]+$' | tail -1 || true
}

# All log lines written during this run (engine.log additions + stderr).
new_log_lines() {
  local before="$1"
  if [ -f "$LOG_FILE" ]; then tail -n +"$((before + 1))" "$LOG_FILE"; fi
  cat "$TMP_STDERR"
}

# log_field "<log line>" name  ->  the number after "name=" (empty if absent)
log_field() {
  echo "$1" | grep -oE "$2=[0-9]+" | tail -1 | grep -oE '[0-9]+$' || true
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1     # macOS
  else
    echo "unavailable"
  fi
}

ORDER=()
if [ "$SHUFFLE" -eq 1 ]; then
  # random order using only awk/sort/cut, which exist on macOS, Linux and Git Bash
  while IFS= read -r line; do ORDER+=("$line"); done < <(
    printf '%s\n' "${SETTINGS[@]}" | awk 'BEGIN{srand()} {print rand() "\t" $0}' | sort -n | cut -f2-
  )
else
  ORDER=("${SETTINGS[@]}")
fi
echo "Order of settings: ${ORDER[*]}"

# Always regenerate the dataset from the seed, so every run uses identical data.
[ -f generate_trips.py ] || { echo "ERROR: generate_trips.py not found in $(pwd)." >&2; exit 1; }
echo "Generating dataset (rows=$DATA_ROWS, seed=$DATA_SEED) -> $DATA_CSV"
"$PYTHON" generate_trips.py --rows "$DATA_ROWS" --seed "$DATA_SEED" --out "$DATA_CSV"

META="generated/meta_${STAMP}.txt"
{
  echo "date: $(date -Iseconds 2>/dev/null || date)"
  echo "git commit: $(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "java: $(java -version 2>&1 | head -1)"
  echo "dataset: $DATA_CSV rows=$DATA_ROWS seed=$DATA_SEED"
  echo "dataset sha256: $(sha256_of "$DATA_CSV")"
  echo "query: $QUERY"
  echo "runs per setting: $RUNS (first discarded)"
  echo "settings order: ${ORDER[*]}"
} > "$META"

echo "partitions,run,durationMs,type,outputLines,partitionsRead,partitionsPruned,rowsOut" > "$OUT"

for s in "${ORDER[@]}"; do
  echo "=== ${CONST_NAME} = $s ==="
  set_partition_size "$s"
  mvn $MVN_ARGS package

  rm -rf "${DATA_DIR:?}"          # start from an empty data dir so the new partition size is used
  "${LOAD_CMD[@]}" > /dev/null 2>&1

  first_lines=""
  for r in $(seq 1 "$RUNS"); do
    log_before=0
    if [ -f "$LOG_FILE" ]; then log_before=$(wc -l < "$LOG_FILE" | tr -d ' '); fi

    ./engine -c "$QUERY" > "$TMP_STDOUT" 2> "$TMP_STDERR"

    lines=$(wc -l < "$TMP_STDOUT" | tr -d ' ')
    # Lines this run added to the log FILE (the console copy on stderr is ignored,
    # so nothing is counted twice).
    run_log=""
    if [ -f "$LOG_FILE" ]; then run_log=$(tail -n +"$((log_before + 1))" "$LOG_FILE"); fi

    # Duration: the Executor's line for the SELECT (the SqlParser's own durationMs is ignored).
    sel=$(echo "$run_log" | grep 'statement=SELECT' | grep 'durationMs=' | tail -1 || true)
    if [ -n "$sel" ]; then
      d=$(log_field "$sel" durationMs)
    else
      d=$(extract_duration "$log_before")
    fi

    # Partitions: the Planner logs one "decision=..." line per partition.
    ntotal=$(echo "$run_log" | grep -c 'decision=' || true)
    if [ "$ntotal" -gt 0 ]; then
      pread=$(echo "$run_log" | grep -c 'decision=READ' || true)
      ppruned=$((ntotal - pread))
    else
      pread=""; ppruned=""
    fi
    rout=""

    if [ "$lines" -eq 0 ]; then
      echo "ERROR: the query printed no rows (setting $s, run $r). Check the table name and that q.sql loaded the data." >&2
      echo "Engine stderr:" >&2; head -n 5 "$TMP_STDERR" >&2
      exit 1
    fi
    if [ -z "$d" ]; then
      echo "ERROR: no durationMs found in $LOG_FILE or stderr (setting $s, run $r)." >&2
      echo "Run the query once by hand, look at the log, and adjust extract_duration()." >&2
      exit 1
    fi
    if [ -z "$first_lines" ]; then first_lines="$lines"; fi
    if [ "$lines" != "$first_lines" ]; then
      echo "WARNING: output line count changed ($first_lines -> $lines) for setting $s." >&2
    fi

    if [ "$r" -eq 1 ]; then type="cold"; else type="warm"; fi
    if [ -n "$rout" ] && [ "$rout" != "$lines" ]; then
      echo "WARNING: rowsOut=$rout in the log but $lines lines printed (setting $s, run $r)." >&2
    fi
    echo "$s,$r,$d,$type,$lines,$pread,$ppruned,$rout" | tee -a "$OUT"
  done
done

echo "partitions,n,median,mean,min,max,partitionsRead,partitionsPruned,outputRows" > "$SUMMARY"
for s in "${SETTINGS[@]}"; do
  awk -F, -v s="$s" '$1==s && $4=="warm" {print $3 "," $6 "," $7 "," $5}' "$OUT" | sort -t, -k1,1n | awk -F, -v s="$s" '
    { a[NR]=$1; sum+=$1; pr=$2; pp=$3; ol=$4 }
    END {
      n=NR
      if (n==0) exit
      med = (n%2) ? a[(n+1)/2] : (a[n/2]+a[n/2+1])/2
      printf "%s,%d,%s,%.1f,%s,%s,%s,%s,%s\n", s, n, med, sum/n, a[1], a[n], pr, pp, ol
    }' >> "$SUMMARY"
done

echo
echo "All runs:  $OUT"
echo "Summary:   $SUMMARY"
echo "Metadata:  $META"
cat "$SUMMARY"
