-- CREATE TABLE tripz (city STRING, distance LONG, price DOUBLE);
-- COPY tripz
-- FROM 'src/test/resources/tripz.csv';
CREATE TABLE tripz_city (city STRING, distance LONG, price DOUBLE);
COPY tripz_city
FROM 'src/test/resources/tripz_sorted_city.csv';