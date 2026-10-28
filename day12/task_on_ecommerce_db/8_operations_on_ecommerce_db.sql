SELECT * FROM products WHERE price > 10000;

SELECT o.id, c.name, o.order_date
FROM orders o
JOIN customers c ON o.customer_id = c.id;

SELECT p.name, SUM(oi.quantity * p.price) AS revenue
FROM order_items oi
JOIN products p ON oi.product_id = p.id
GROUP BY p.id, p.name;

SELECT c.name, COUNT(o.id) AS total_orders
FROM customers c
JOIN orders o ON c.id = o.customer_id
GROUP BY c.id, c.name
HAVING COUNT(o.id) > 1;

SELECT * FROM products
WHERE price > (SELECT AVG(price) FROM products);

SELECT name, category, price,
RANK() OVER (PARTITION BY category ORDER BY price DESC) AS price_rank
FROM products;

WITH spending AS (
  SELECT o.customer_id, SUM(oi.quantity * p.price) AS total
  FROM orders o
  JOIN order_items oi ON o.id = oi.order_id
  JOIN products p ON oi.product_id = p.id
  GROUP BY o.customer_id
)
SELECT c.name, s.total
FROM spending s JOIN customers c ON s.customer_id = c.id;

SELECT name, price,
CASE
  WHEN price >= 20000 THEN 'Expensive'
  WHEN price >= 5000 THEN 'Moderate'
  ELSE 'Budget'
END AS price_category
FROM products;
