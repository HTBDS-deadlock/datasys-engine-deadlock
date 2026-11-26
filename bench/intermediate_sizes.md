| Configuration | Join (build side first) | estRows | rowsOut | est / actual |
|---|---|---:|---:|---:|
| min | customers join shipments | 1,000 | 1,000 | 1.00 |
| min | customers join shipments join orders | 100,000 | 100,000 | 1.00 |
| max | orders join shipments | 10,000,000 | 10,000,000 | 1.00 |
| max | orders join shipments join customers | 100,000 | 100,000 | 1.00 |
| opt_min_from | customers join shipments | 1,000 | 1,000 | 1.00 |
| opt_min_from | customers join shipments join orders | 100,000 | 100,000 | 1.00 |
| opt_max_from | customers join shipments | 1,000 | 1,000 | 1.00 |
| opt_max_from | customers join shipments join orders | 100,000 | 100,000 | 1.00 |
