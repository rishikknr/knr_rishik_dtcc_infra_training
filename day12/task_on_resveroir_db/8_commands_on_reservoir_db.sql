SELECT * FROM reservoirs;

SELECT * FROM weather WHERE rainfall > 100;

SELECT r.name, i.record_date, i.inflow_value
FROM reservoirs r
JOIN inflow i ON r.id = i.reservoir_id;

SELECT reservoir_id, AVG(inflow_value) AS avg_inflow
FROM inflow GROUP BY reservoir_id;

SELECT reservoir_id, AVG(inflow_value) AS avg_inflow
FROM inflow
GROUP BY reservoir_id
HAVING AVG(inflow_value) > 400;

SELECT * FROM inflow
WHERE inflow_value > (SELECT AVG(inflow_value) FROM inflow);

WITH avg_data AS (
  SELECT reservoir_id, AVG(inflow_value) AS avg_inflow
  FROM inflow GROUP BY reservoir_id
)
SELECT * FROM avg_data WHERE avg_inflow > 400;

SELECT reservoir_id, inflow_value,
CASE
  WHEN inflow_value >= 500 THEN 'High'
  WHEN inflow_value >= 300 THEN 'Medium'
  ELSE 'Low'
END AS inflow_status
FROM inflow;

