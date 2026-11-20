CREATE TABLE flights (city STRING, duration LONG, price DOUBLE);
COPY flights FROM 'src/test/resources/flights/flights_1.csv';
COPY flights FROM 'src/test/resources/flights/flights_2.csv';
COPY flights FROM 'src/test/resources/flights/flights_3.csv';
COPY flights FROM 'src/test/resources/flights/flights_4.csv';
