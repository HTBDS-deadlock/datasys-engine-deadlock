SELECT count(*), max(p_price), sum(p_price), min(p_name) FROM products WHERE p_price > 99999.0;
