-- Join-order benchmark (exercise 12): loads the data written by
-- bench/generate_joinbench.py into an empty data directory.
CREATE TABLE customers (c_name STRING, c_city STRING);
COPY customers FROM 'generated/joinbench/customers.csv';

CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_quantity LONG);
COPY orders FROM 'generated/joinbench/orders.csv';

CREATE TABLE shipments (s_shipkey LONG, s_custname STRING, s_weight DOUBLE);
COPY shipments FROM 'generated/joinbench/shipments.csv';
