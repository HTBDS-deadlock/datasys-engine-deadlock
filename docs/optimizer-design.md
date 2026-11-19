# Optimizer Design

From v0.11 the planner no longer takes the `FROM` list as the join order. It
estimates every join's output size from catalog statistics and orders the joins
so that the estimated intermediate results stay small. This document records
which statistics we keep, how we estimate, and how we search, together with the
source of each choice.

## 1. Statistics

Per partition, the catalog already holds:

- `rowCount`
- `min` / `max` of every column

New in v0.11, per partition and per column:

- `ndv`: the number of distinct values in that column of that partition.

NDV is collected in `StorageEngine.writePartition`, in the same pass that
computes min/max, using an exact `HashSet` per column. `STRING` columns are
included, because join keys are often strings. COPY and the DELETE rewrite both
write partitions through `writePartition`, so a rewritten partition always gets
a fresh NDV. A partition that DELETE carries over unchanged keeps its NDV, which
is still correct.

Each NDV is logged once, when it is created, on the same line as the min/max:

```
table=orders partition=3 column=o_custname min=Anna max=Dan rows=10240 ndv=997
```

**At scale.** A partition holds at most 10,240 rows, so a `HashSet` is cheap.
At a billion rows we would replace it with a HyperLogLog sketch per partition
and column: a few KB of fixed memory, about 2% error, and an exact merge across
partitions (union of the registers), which also removes the merge problem in
section 2.

## 2. Where NDV lives and how it merges

NDV is stored in `catalog.json` next to min/max:

```json
"stats" : { "o_custname" : { "min" : "Anna", "max" : "Dan", "ndv" : 997 } }
```

The estimator needs one NDV per column, but it is collected per partition.
Unlike min/max, NDV does not merge exactly: the same value can appear in many
partitions. The true table NDV lies between two bounds:

- the maximum of the partition NDVs (a lower bound)
- their sum (an upper bound)

**Rule: table NDV = the maximum partition NDV, taken over the partitions that
survive pruning.** It is implemented as the unit-tested helper
`long tableNdv(List<Long> partitionNdvs)`.

Why we chose max:

1. **Join keys are spread across partitions.** A customer's orders land in many
   partitions, so the sum counts the same value many times. On lecture 11's
   benchmark (slide 21), max gives 665 against a true 1,000, an error of 1.5×.
   With our 10-partition layout, sum would give about 6,650, an error of 6.6×.
2. **Its error is in the safe direction.** NDV is the divisor in the join
   estimate, so an NDV that is too small gives a join estimate that is too
   large. Leis et al. (2015) show that underestimates are the ones that cause
   bad plans.
3. **It degrades gracefully.** The maximum over any subset of the partitions is
   still a lower bound on the true NDV. This is what makes the roll-forward in
   section 3 work.

We considered averaging the partition NDVs and rejected it. The average is
never larger than the maximum, so it is always an even larger underestimate.

## 3. Roll forward and roll back

Tables copied before v0.11 have no `ndv` in the catalog. **A missing NDV means
"unknown", never 0.** Treating it as 0 would make the estimator divide by zero,
or, after `max(ndv, 1)`, would make every filter look like it keeps every row.

### Roll forward (v0.11 reads a v0.10 catalog)

- A missing `ndv` loads as `null`.
- `tableNdv` takes the maximum over the surviving partitions that have an NDV.
  This is still a valid lower bound.
- Only if none of them has an NDV is the column's NDV unknown. The estimator
  then uses the fallbacks in section 4.3.
- When a fallback is used, it is logged (`ndv=unknown`), so it shows up in the
  log analysis.
- Old partitions get an NDV the next time a DELETE rewrites them. New COPYs add
  partitions that have one. No separate backfill step is needed.

### Roll back (v0.10 reads a v0.11 catalog)

v0.10's `ObjectMapper` uses Jackson's default `FAIL_ON_UNKNOWN_PROPERTIES =
true`, and its `StatsEntry` has only `min` and `max`. A catalog written by v0.11
would therefore make v0.10 fail at startup.

The fix is a **tolerant reader first**:

1. Release **v0.10.1**, which changes only one thing: the catalog reader ignores
   unknown properties. Data and behaviour are otherwise identical to v0.10.
2. **v0.10.1 is the rollback target** for v0.11. It ignores `ndv` on read and
   drops it when it rewrites the catalog. That loss is harmless: rolling forward
   again treats the missing value as unknown (above).
3. v0.11 keeps the tolerant reader, so a later rollback from v0.12 is safe in
   the same way.

The binary data file format does not change in v0.11, so only the catalog is
affected.

## 4. The estimator

The estimator is a set of pure functions: catalog numbers in, an estimated row
count out. No data file is opened and nothing is sampled, so it can be
unit-tested on statistics written by hand.

### 4.1 Formulas

| Estimate | Formula | Assumption | Source |
|---|---|---|---|
| Base table | sum of `rowCount` over the partitions that survive pruning | none; exact | lecture 11, slide 11 |
| Equality filter `col = x` | `rows / ndv(col)` | uniformity | lecture 11, slide 11 |
| Range filter `<`, `>` | `rows / 3`, for every column type | uniformity | Selinger et al. 1979 ("magic constant"); lecture 11, slide 11 |
| Several filters on one table | product of their selectivities | independence | lecture 11, slide 22 |
| Equi-join `R.a = S.b` | `rows(R) · rows(S) / max(ndv(R.a), ndv(S.b))` | containment + uniformity | lecture 11, slides 13–14 |

We use 1/3 for range filters on numeric columns too. Interpolating from min/max
would only work for `LONG` and `DOUBLE`. A single constant matches the lecture
examples, so our results can be checked against the slides.

### 4.2 Details

- **NDV after a filter.**
  - Filters change a table's row count but not its NDVs (slide 18), except that
    an equality filter on a column sets that column's NDV to 1.
  - A column's NDV is clamped to the estimated row count of its filtered table:
    `ndv = min(ndv, estRows)`. This is what slide 30 does (`max(3, 1)` for
    `cities` filtered down to one row).
- **NDV of an intermediate result.** An intermediate result has no statistics
  of its own. Its row count is the estimate of the join below it. Each column
  keeps the (filtered) NDV of the base table it came from (lecture 11, slide 18;
  Exercise 11, section 3). The clamp is *not* applied again to intermediate
  results.
- **Rounding.** Round half up (`Math.round`). The result is at least 1 unless
  the input was empty (slide 17), because a zero estimate would make a plan look
  free.
- **Division by zero.** Every divisor is wrapped in `max(…, 1)`.

### 4.3 Fallbacks when NDV is unknown (roll forward)

| Case | Fallback | Source |
|---|---|---|
| Equality filter, NDV unknown | selectivity 1/10: `rows / 10` | Selinger et al. 1979, default for `col = value` without statistics |
| Join, one side's NDV unknown | divide by the known side's NDV alone | own reasoning: use the statistic we have |
| Join, both NDVs unknown | treat the smaller input's join column as a key: `ndv = rows(smaller)`, so the estimate is `rows(larger)` | foreign-key join to a unique key, lecture 11, slide 15 |

## 5. The heuristic: greedy, smallest intermediate first

We use the course baseline: Greedy Operator Ordering (Fegaras 1998), restricted
to left-deep plans (lecture 11, slides 29–31).

1. Estimate every table's size after pruning and filters (section 4).
2. Score every join predicate as a pair of tables, and start with the pair whose
   estimated output is smallest.
3. Repeat until every table has been joined: among the tables connected by a
   predicate to the tables joined so far, add the one whose estimated join
   output is smallest.

Every candidate that is checked writes one log line:

```
JoinOrder - plan=customers;cities table=orders join=o_custname=c_name estRows=2
```

**Tie-breaking.** When two candidates have the same estimate, the table that
comes first in the `FROM` list wins. This keeps the planner deterministic and
testable.

**Why greedy and not exhaustive search.**

- With at most four tables, exhaustive search or dynamic programming would also
  be cheap.
- Greedy is short, and its choice can be read directly from the log.
- On the lecture's four-table query it finds the same plan as full search
  (slide 31).
- The search is not where optimizers usually fail: Leis et al. show that the
  quality of the estimates matters more than the search algorithm. If week 12
  finds a bad plan, we expect the estimates to be the cause.

**Cross-product guardrail.**

- Only tables connected to the plan by a join predicate are candidates, so a
  cross product is never picked while a connected choice exists.
- The engine has no cross-product operator. The binder therefore requires the
  join predicates to form a tree over the tables: every table connected, one
  predicate per table (the hash join has a single key). A disconnected or
  cyclic join graph is rejected in both modes.
- Only `-j syntax` also requires the `FROM` list itself to fold left-deep
  (each table connected to the ones before it). Under `-j optimized`,
  `FROM orders, cities, customers` works even though `cities` connects only
  to `customers`: the order in which the query is written costs nothing.

## 6. Build side

For every join, the input with the smaller estimated size builds the hash table
and the larger one probes (lecture 11, slide 38). The order still has a base
table as one input of every join, so it remains a single list, but the tree
becomes zig-zag instead of strictly left-deep.

**Tie:** if both inputs have the same estimate, the intermediate result builds,
as in week 10.

## 7. Plan modes and column order

- **`-j optimized`** is the default. It uses the join order from sections 5
  and 6.
- **`-j syntax`** reproduces week 10's plan exactly: `FROM`-list order, with the
  intermediate result always building. To force a specific join order, run
  `-j syntax` and write the `FROM` list in that order. No hint syntax is added,
  so every script still runs unchanged in DuckDB.
- **Column order.** `SELECT *` emits columns in `FROM`-list order under any
  plan. A final projection restores that order after a reordered plan.

## 8. Observability

The log records the chosen order and, for every executed join, its estimate next
to its actual output size. Both are attributable to their statement through
`statementNumber`:

```
Executor - statement=SELECT ... joinOrder=customers;cities;orders;products mode=optimized rowsOut=4
HashJoinOperator - tables=customers;cities estRows=1 rowsBuild=1 rowsProbe=4 rowsOut=2
```

`estRows` divided by `rowsOut` is the estimate error that week 12's report
analyses.

## 9. GROUP BY (optional extension)

```sql
SELECT c_city, max(p_price), count(*)
FROM orders, customers, products
WHERE o_custname = c_name AND o_prodname = p_name
GROUP BY c_city;
```

**Syntax.** DuckDB syntax, with `min`, `max`, `sum`, `count(col)` and
`count(*)`. A query may have:

- one or more `GROUP BY` columns;
- several aggregates, mixed with plain columns in any order in the `SELECT`
  list;
- aggregates without `GROUP BY`, which return exactly one row.

Function names are case-insensitive and are not reserved words, so a column
may still be called `count`. Only `GROUP` and `BY` are new keywords.

**Rules (the binder, as in DuckDB).**

- A plain column in the `SELECT` list must also be a `GROUP BY` column.
- `SELECT *` cannot be combined with `GROUP BY`.
- `sum` needs a `LONG` or `DOUBLE` column.
- `*` is only allowed in `count(*)`.

**Result types.** `count` returns `LONG`. `sum` returns the column's type.
`min` and `max` work on every type, because `LONG`, `DOUBLE` and `STRING` are
all `Comparable` (lecture 11, slide 45).

**Empty input.** Without `GROUP BY`, an empty input still gives one row:
`count` is 0 and `min`, `max` and `sum` have no value. The engine has no NULL,
so a missing value is printed as an empty CSV field. That is what DuckDB
writes for NULL when it exports CSV (`COPY ... TO`). With `GROUP BY`, an
empty input gives no rows.

**Execution.** Hash aggregation (lecture 11, slides 43–45):

- `HashAggregateOperator` sits on top of the joins (slide 44). It keeps one
  accumulator per aggregate per group, in a `LinkedHashMap` keyed by the
  grouping columns' values.
- It is blocking: `open()` drains the child; `next()` then emits one row per
  group, the grouping values followed by each accumulator's `finish()`.
- The accumulator contract is init (the constructor), `update` once per row,
  and `finish` once per group.
- A final projection puts the output in `SELECT`-list order.

**Estimate.** The number of groups is estimated as the product of the grouping
columns' NDVs, capped at the estimated input rows. If an NDV is unknown, the
input rows are used. Without `GROUP BY` the estimate is 1. Each executed
aggregation logs its estimate next to the actual group count:

```
HashAggregateOperator - groupBy=c_city aggregates=max(p_price);count(*) estRows=3 rowsIn=7 rowsOut=3
```

The product of NDVs assumes the grouping columns are independent, and
correlated columns make it an overestimate. A city implies its country, so
`GROUP BY ct_country, c_city` has as many groups as `GROUP BY c_city` alone.

**Oracle.** `src/test/resources/groupby` holds lecture 11's tables, eight
queries, and DuckDB's output for each query. The tests run every query under
both join modes and compare the sorted rows.

## 10. Assumptions to revisit in the report

The estimates depend on these assumptions. When week 12 finds the engine's worst
estimate, they are the first places to look.

| Assumption | Where it can fail |
|---|---|
| Uniformity (every value equally frequent) | skewed columns, as `o_quantity` on slide 12 |
| Independence (filters multiply) | correlated predicates, "Copenhageners ride bikes" (slide 23) |
| Containment (the smaller key domain is contained in the larger) | joins where many keys find no partner |
| Max-of-partitions NDV | values spread over many partitions; the error compounds per join (1.5× becomes 2.26×, slide 21) |
| Intermediate results inherit base-table NDVs | after selective joins, the true NDV is much smaller |
| Fallback 1/10 and the foreign-key assumption | tables copied before v0.11 |
