
INSERT INTO customers VALUES
(1, 'Ravi', 'Hyderabad'),
(2, 'Priya', 'Chennai'),
(3, 'Arjun', 'Bangalore'),
(4, 'Sneha', 'Mumbai'),
(5, 'Kiran', 'Hyderabad');

INSERT INTO products VALUES
(1, 'Laptop', 'Electronics', 55000),
(2, 'Phone', 'Electronics', 25000),
(3, 'Headphones', 'Accessories', 2000),
(4, 'Shoes', 'Fashion', 3000),
(5, 'Watch', 'Accessories', 5000);

INSERT INTO orders VALUES
(101, 1, '2026-10-01'),
(102, 2, '2026-10-02'),
(103, 3, '2026-10-03'),
(104, 1, '2026-10-04'),
(105, 4, '2026-10-05'),
(106, 5, '2026-10-06');

INSERT INTO order_items VALUES
(1, 101, 1, 1),
(2, 101, 3, 2),
(3, 102, 2, 1),
(4, 103, 4, 2),
(5, 104, 5, 1),
(6, 105, 2, 2),
(7, 106, 3, 3),
(8, 106, 4, 1);
