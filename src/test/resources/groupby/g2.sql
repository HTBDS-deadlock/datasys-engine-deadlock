SELECT o_custname, count(*), sum(o_quantity), min(o_orderkey), max(o_prodname) FROM orders
GROUP BY o_custname;
