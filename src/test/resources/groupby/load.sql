CREATE TABLE orders (o_orderkey LONG, o_custname STRING, o_prodname STRING, o_quantity LONG);
CREATE TABLE products (p_name STRING, p_price DOUBLE);
CREATE TABLE customers (c_name STRING, c_city STRING);
CREATE TABLE cities (ct_city STRING, ct_country STRING, ct_population LONG);
COPY orders FROM 'src/test/resources/groupby/orders.csv';
COPY products FROM 'src/test/resources/groupby/products.csv';
COPY customers FROM 'src/test/resources/groupby/customers.csv';
COPY cities FROM 'src/test/resources/groupby/cities.csv';
