-- NOVAMART / STEP 4: Load 22 baseline CSVs from S3 to Snowflake RAW.
-- Generated from the actual NovaMart_Datasets.zip. Expected rows: 143900.
-- Preconditions:
-- 1. NOVAMART_DB.RAW tables exist, with columns in CSV header order.
-- 2. NOVAMART_DB.RAW.NOVAMART_CSV_FORMAT exists and has SKIP_HEADER=1,
--    FIELD_OPTIONALLY_ENCLOSED_BY='"', NULL_IF=('', 'NULL').
-- 3. The credential-backed stage @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
--    lists exactly the baseline files. Execute with a warehouse selected.
-- 4. The tables are empty for a fresh baseline import. This script never
--    TRUNCATEs or overwrites data. For reruns inspect existing rows and
--    Snowflake load history before proceeding to avoid duplicates.
-- Each COPY targets only its matching CSV, never the entire S3 prefix.

USE DATABASE NOVAMART_DB;
USE SCHEMA RAW;

-- OPTIONAL: validate timestamp/date parsing before running all 22 loads.
-- Run the following statement separately first. Zero returned error rows
-- indicates that the orders file can be parsed into its target table.
-- COPY INTO NOVAMART_DB.RAW.FACT_ORDERS
--   FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
--   FILES = ('fact_orders.csv')
--   VALIDATION_MODE = RETURN_ERRORS;

-- 22 DATA LOAD COMMANDS
-- dim_calendar.csv: expected 669 rows / 8 CSV columns.
COPY INTO NOVAMART_DB.RAW.DIM_CALENDAR
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('dim_calendar.csv')
  ON_ERROR = ABORT_STATEMENT;

-- dim_campaigns.csv: expected 48 rows / 7 CSV columns.
COPY INTO NOVAMART_DB.RAW.DIM_CAMPAIGNS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('dim_campaigns.csv')
  ON_ERROR = ABORT_STATEMENT;

-- dim_categories.csv: expected 12 rows / 4 CSV columns.
COPY INTO NOVAMART_DB.RAW.DIM_CATEGORIES
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('dim_categories.csv')
  ON_ERROR = ABORT_STATEMENT;

-- dim_customers.csv: expected 2,500 rows / 10 CSV columns.
COPY INTO NOVAMART_DB.RAW.DIM_CUSTOMERS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('dim_customers.csv')
  ON_ERROR = ABORT_STATEMENT;

-- dim_products.csv: expected 600 rows / 13 CSV columns.
COPY INTO NOVAMART_DB.RAW.DIM_PRODUCTS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('dim_products.csv')
  ON_ERROR = ABORT_STATEMENT;

-- dim_stores.csv: expected 22 rows / 8 CSV columns.
COPY INTO NOVAMART_DB.RAW.DIM_STORES
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('dim_stores.csv')
  ON_ERROR = ABORT_STATEMENT;

-- dim_suppliers.csv: expected 75 rows / 8 CSV columns.
COPY INTO NOVAMART_DB.RAW.DIM_SUPPLIERS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('dim_suppliers.csv')
  ON_ERROR = ABORT_STATEMENT;

-- dim_warehouses.csv: expected 6 rows / 7 CSV columns.
COPY INTO NOVAMART_DB.RAW.DIM_WAREHOUSES
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('dim_warehouses.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_campaign_spend.csv: expected 1,584 rows / 5 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_CAMPAIGN_SPEND
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_campaign_spend.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_delivery_events.csv: expected 32,607 rows / 5 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_DELIVERY_EVENTS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_delivery_events.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_inventory_movements.csv: expected 18,600 rows / 8 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_INVENTORY_MOVEMENTS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_inventory_movements.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_inventory_snapshot.csv: expected 3,600 rows / 6 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_INVENTORY_SNAPSHOT
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_inventory_snapshot.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_order_attribution.csv: expected 1,999 rows / 6 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_ORDER_ATTRIBUTION
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_order_attribution.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_order_items.csv: expected 24,304 rows / 10 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_ORDER_ITEMS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_order_items.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_orders.csv: expected 10,000 rows / 14 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_ORDERS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_orders.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_payments.csv: expected 11,264 rows / 9 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_PAYMENTS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_payments.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_purchase_order_items.csv: expected 3,748 rows / 7 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_PURCHASE_ORDER_ITEMS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_purchase_order_items.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_purchase_orders.csv: expected 1,500 rows / 8 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_PURCHASE_ORDERS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_purchase_orders.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_refunds.csv: expected 1,073 rows / 9 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_REFUNDS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_refunds.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_returns.csv: expected 1,285 rows / 10 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_RETURNS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_returns.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_shipments.csv: expected 8,404 rows / 8 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_SHIPMENTS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_shipments.csv')
  ON_ERROR = ABORT_STATEMENT;

-- fact_web_events.csv: expected 20,000 rows / 8 CSV columns.
COPY INTO NOVAMART_DB.RAW.FACT_WEB_EVENTS
  FROM @NOVAMART_DB.RAW.NOVAMART_S3_KEY_STAGE
  FILES = ('fact_web_events.csv')
  ON_ERROR = ABORT_STATEMENT;

-- ================================================================
-- VERIFICATION: expects every row to report PASS; total = 143900.
-- Counts are computed from the actual dataset, not assumptions.
-- ================================================================
WITH counts AS (
  SELECT 'DIM_CALENDAR' AS table_name, 669 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.DIM_CALENDAR
  UNION ALL SELECT 'DIM_CAMPAIGNS' AS table_name, 48 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.DIM_CAMPAIGNS
  UNION ALL SELECT 'DIM_CATEGORIES' AS table_name, 12 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.DIM_CATEGORIES
  UNION ALL SELECT 'DIM_CUSTOMERS' AS table_name, 2500 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.DIM_CUSTOMERS
  UNION ALL SELECT 'DIM_PRODUCTS' AS table_name, 600 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.DIM_PRODUCTS
  UNION ALL SELECT 'DIM_STORES' AS table_name, 22 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.DIM_STORES
  UNION ALL SELECT 'DIM_SUPPLIERS' AS table_name, 75 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.DIM_SUPPLIERS
  UNION ALL SELECT 'DIM_WAREHOUSES' AS table_name, 6 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.DIM_WAREHOUSES
  UNION ALL SELECT 'FACT_CAMPAIGN_SPEND' AS table_name, 1584 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_CAMPAIGN_SPEND
  UNION ALL SELECT 'FACT_DELIVERY_EVENTS' AS table_name, 32607 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_DELIVERY_EVENTS
  UNION ALL SELECT 'FACT_INVENTORY_MOVEMENTS' AS table_name, 18600 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_INVENTORY_MOVEMENTS
  UNION ALL SELECT 'FACT_INVENTORY_SNAPSHOT' AS table_name, 3600 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_INVENTORY_SNAPSHOT
  UNION ALL SELECT 'FACT_ORDER_ATTRIBUTION' AS table_name, 1999 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_ORDER_ATTRIBUTION
  UNION ALL SELECT 'FACT_ORDER_ITEMS' AS table_name, 24304 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_ORDER_ITEMS
  UNION ALL SELECT 'FACT_ORDERS' AS table_name, 10000 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_ORDERS
  UNION ALL SELECT 'FACT_PAYMENTS' AS table_name, 11264 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_PAYMENTS
  UNION ALL SELECT 'FACT_PURCHASE_ORDER_ITEMS' AS table_name, 3748 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_PURCHASE_ORDER_ITEMS
  UNION ALL SELECT 'FACT_PURCHASE_ORDERS' AS table_name, 1500 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_PURCHASE_ORDERS
  UNION ALL SELECT 'FACT_REFUNDS' AS table_name, 1073 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_REFUNDS
  UNION ALL SELECT 'FACT_RETURNS' AS table_name, 1285 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_RETURNS
  UNION ALL SELECT 'FACT_SHIPMENTS' AS table_name, 8404 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_SHIPMENTS
  UNION ALL SELECT 'FACT_WEB_EVENTS' AS table_name, 20000 AS expected_rows, COUNT(*) AS actual_rows FROM NOVAMART_DB.RAW.FACT_WEB_EVENTS
)
SELECT table_name, expected_rows, actual_rows,
       IFF(expected_rows = actual_rows, 'PASS', 'CHECK') AS load_status
FROM counts
ORDER BY table_name;

-- Verify summed baseline row count separately (result should be 143900):
SELECT
  (SELECT COUNT(*) FROM NOVAMART_DB.RAW.DIM_CALENDAR)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.DIM_CAMPAIGNS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.DIM_CATEGORIES)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.DIM_CUSTOMERS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.DIM_PRODUCTS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.DIM_STORES)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.DIM_SUPPLIERS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.DIM_WAREHOUSES)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_CAMPAIGN_SPEND)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_DELIVERY_EVENTS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_INVENTORY_MOVEMENTS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_INVENTORY_SNAPSHOT)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_ORDER_ATTRIBUTION)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_ORDER_ITEMS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_ORDERS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_PAYMENTS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_PURCHASE_ORDER_ITEMS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_PURCHASE_ORDERS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_REFUNDS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_RETURNS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_SHIPMENTS)
  + (SELECT COUNT(*) FROM NOVAMART_DB.RAW.FACT_WEB_EVENTS)
  AS total_rows_loaded;
