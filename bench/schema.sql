CREATE TABLE customers (c_name STRING, c_city STRING);                          -- dimension table
CREATE TABLE orders    (o_orderkey LONG, o_custname STRING, o_quantity LONG);   -- fact table
CREATE TABLE shipments (s_shipkey LONG, s_custname STRING, s_weight DOUBLE);    -- fact table
