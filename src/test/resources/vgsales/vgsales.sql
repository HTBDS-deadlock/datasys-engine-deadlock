CREATE TABLE vgsales (
    rank LONG,
    name STRING,
    platform STRING,
    year LONG,
    genre STRING,
    publisher STRING,
    na_sales DOUBLE,
    eu_sales DOUBLE,
    jp_sales DOUBLE,
    other_sales DOUBLE,
    global_sales DOUBLE
);
COPY flights
FROM 'src\test\resources\vgsales\vgsales.csv';