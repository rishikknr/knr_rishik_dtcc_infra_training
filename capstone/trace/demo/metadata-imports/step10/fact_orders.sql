SELECT order_id, gross_amount, discount_amount,
       gross_amount - discount_amount AS net_amount
FROM raw.orders;
