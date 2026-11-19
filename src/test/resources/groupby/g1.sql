SELECT c_city, max(p_price), count(*) FROM orders, customers, products
WHERE o_custname = c_name AND o_prodname = p_name
GROUP BY c_city;
