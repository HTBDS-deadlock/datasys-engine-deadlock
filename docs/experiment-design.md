# Experiment Design

## Question and x axis

The question is "How does `maxRowsPerPartition` affect the execution time of a
`SELECT ... WHERE ...` query?"
Where the independent variable is `maxRowsPerPartition`.

On the x axis we will have the `maxRowsPerPartition`and the following values will be tested:

- 1,000
- 5,000
- 10,000
- 25,000
- 65,536

The value 65,536 is included because it was the first default value in
the storage engine. The smaller values are included to investigate whether
reducing the maximum partition size affects query performance.

The x-axis will use a logarithmic scale because the tested values span
multiple orders of magnitude.

## Metric and y axis

The metric is query execution time, measured using `durationMs` from the
engine log.

For each value of `maxRowsPerPartition`, the same query will be executed
6 times.

The first execution will be treated as a cold run and excluded because
initial execution may include additional JVM, class-loading, and caching
overhead.

The median `durationMs` of the remaining 5 executions will be used as
the measurement for that partition size. Where lower values indicate faster query execution.

As mentioned we will have the `maxRowsPerPartition` as rows on a  logarithmic scale on the x axis. On the y axis we will have the median query execution time in milliseconds.

This means that the results will be shown as one curve. Where each point on the curve represents the median execution time of the same SELECT query for one value of `maxRowsPerPartition`.

## Procedure

A fixed dataset will be generated using a script with a fixed random
seed so that every group member can regenerate the same data and
reproduce the experiment.

The exact same generated dataset will be used for every partition-size
configuration.

The same table schema, dataset, SELECT query, and WHERE predicate will
be used for every configuration. Therefore, the variable intentionally
changed between configurations is `maxRowsPerPartition`.


For each value of `maxRowsPerPartition`:

For each value of `maxRowsPerPartition`:
 
1. Create a fresh data directory so that data from a previous
configuration does not affect the next configuration.
2. Configure the storage engine with the selected
`maxRowsPerPartition`.
3. Create the same table with the same schema.
4. Load the same generated CSV dataset into the table.
5. Execute the same `SELECT ... WHERE ...` query 6 times.
6. Treat the first execution as a cold run and exclude it from the
timing results.
7. Record `durationMs` for each execution from the engine log.
8. Calculate the median `durationMs` of the remaining 5 executions.
9. Use this median as the measurement for that value of
`maxRowsPerPartition`.

The experiment will be run on the same machine for all configurations.

Before taking measurements, we will record:

- Machine
- Operating system
- JVM version
- JVM heap size
- Git commit

## Hypothesis

We expect smaller values of `maxRowsPerPartition` to result in lower
query execution times than the current default of 65,536 rows per
partition.

Smaller partitions contain fewer rows, which may allow the engine to
avoid reading as much unnecessary data when executing a selective
`SELECT ... WHERE ...` query. However, very small partitions also create
more partitions for the engine to process.

We therefore expect one of the intermediate values, particularly 10,000
or 25,000 rows per partition, to have a lower median `durationMs` than
both 1,000 and 65,536 rows per partition.
