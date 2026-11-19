SELECT sum(o_quantity), o_prodname FROM orders GROUP BY o_prodname, o_custname;
