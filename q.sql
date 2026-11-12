--CREATE TABLE trips (city STRING, distance LONG, price DOUBLE);
--COPY trips
--FROM 'src/test/resources/tripz.csv';
-- CREATE TABLE tripz_city (city STRING, distance LONG, price DOUBLE);
-- COPY tripz_city
-- FROM 'src/test/resources/tripz_sorted_city.csv';
CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_prodname STRING, o_quantity LONG);
COPY orders
FROM 'orders.csv';

CREATE TABLE products (p_name STRING, p_price DOUBLE);
COPY products
FROM 'products.csv';
CREATE TABLE customers (c_name STRING, c_city STRING);
COPY customers
FROM 'customers.csv';
CREATE TABLE cities (ct_city STRING, ct_country STRING, ct_population LONG);
COPY cities
FROM 'cities.csv';

SELECT * FROM orders, customers WHERE o_custname = c_name;

