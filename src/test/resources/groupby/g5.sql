SELECT ct_country, c_city, o_prodname, count(o_orderkey), sum(p_price) FROM orders, customers, products, cities
WHERE o_custname = c_name AND o_prodname = p_name AND c_city = ct_city AND o_quantity < 3
GROUP BY ct_country, c_city, o_prodname;
