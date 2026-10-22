# Experiment Design
## Question and X Axis
The question is:

"How does the maximum number of rows per partition (DEFAULT_MAX_ROWS_PER_PARTITION) affect the execution time of a selective SELECT query?"

Which lead us to the hypothesis of: "Having 10,000 maximum rows per partition is faster than both larger and smaller partition sizes"

The independent variable is therefore the maximum number of rows per partition.

The following partition sizes will be tested:

7,500 10,000 12,500 15,000 65,000

The value 65,000 is included because it is close to the original default configuration of the storage engine, which was 65,536. The smaller values were selected to investigate the expected optimum range and to determine whether smaller partitions improve partition pruning.

The x-axis will show the tested partition sizes.

# Metric and Y Axis
The metric is query execution time, measured using the durationMs value reported by the engine log for the SELECT statement.

For each partition-size configuration, the same query will be executed six times.

The first execution will be discarded as a precaution, leaving five measured runs per configuration.

The median durationMs of the remaining five runs will be used as the primary measurement. Lower values indicate better performance. The mean, minimum and maximum execution times will also be recorded.

The x-axis will show rows per partition and the y-axis will show median query execution time in milliseconds.

Each point on the graph therefore represents the median execution time of the same query for a particular partition size with whiskers indicating min and max.

# Dataset
A dataset will be generated using generate_trips.py with a fixed random seed of 42.

The dataset contains:

2,000,000 rows distances between 1 and 1000 km rows sorted by distance

The same generated dataset will be used for every partition-size configuration, ensuring that any observed differences are caused by partition size rather than differences in the data.

# Procedure
The experiment was automated using run_experiment.sh. The script can be executed from the project's root directory using: bash run_experiment.sh

The same table schema, dataset, query and predicate are used throughout the experiment. The only variable intentionally changed between configurations is DEFAULT_MAX_ROWS_PER_PARTITION.

For each tested partition size:

Modify DEFAULT_MAX_ROWS_PER_PARTITION in StorageEngine.java.
Rebuild the project using mvn -DskipTests package.
Delete the existing data directory.
Recreate the database using ./engine -f q.sql.
Load the generated dataset into the table.
Execute
SELECT * FROM trips WHERE distance < 100;
six times.

Discard the first execution.

Record durationMs for the remaining executions from the engine log.

Compute the median, mean, minimum and maximum execution times.

The order of the tested partition sizes is randomized by the experiment script to reduce systematic bias.

The results of all executions were collected in a results*.csv file stored in the generated directory. The script also produced a summary*.csv file containing the aggregated statistics for each partition size.

In addition, metadata describing the experimental environment, including the Git commit, JVM version and dataset checksum, was collected and written to a meta_.txt file in the generated directory.

# Experimental Environment
All measurements are collected on the same machine and software environment.

The following information is recorded:

- Machine: Apple M2
- Memory: 8 GB RAM
- Operating System: macOS Tahoe 26.6.2
- JVM: OpenJDK 25.0.4.1
- JVM heap: Default settings
- Git commit: 760b207

## Hypothesis
Partition pruning uses per-partition minimum and maximum values to decide whether partitions can contain rows satisfying the query predicate.

Smaller partitions generally improve pruning precision because fewer irrelevant rows are contained within each partition. However, smaller partitions also increase the number of partitions that must be managed and stored in the catalog.

We therefore expect an intermediate partition size to provide the best performance. In particular, we hypothesize that a partition size around 10,000 rows will achieve a lower median execution time than both the original default value of about 65,000 rows and the neighbouring partition sizes tested in the experiment.

Hypothesis: "Having 10,000 maximum rows per partition is faster than both larger and smaller partition sizes"
