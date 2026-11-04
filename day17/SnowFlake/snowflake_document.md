# Snowflake E-Commerce Project — Setup, Data Loading, Cleaning and Query Optimization

## Project overview

I used an e-commerce dataset to practice setting up a Snowflake environment, loading CSV files, transforming data with SQL, and investigating query performance through Query Profile.

The main challenge was getting the source files and Snowflake table definitions to match. I initially followed a structure intended for a smaller dataset, but then switched back to my original, larger CSV files. That caused a column-count mismatch during validation. I also encountered an invalid date in a CSV during an earlier load attempt. These notes cover the setup, what went wrong, how I addressed the issues, and the SQL workflow for the remaining analysis.

### Dataset used

| File | Expected rows | Columns | Purpose |
|---|---:|---:|---|
| `customers.csv` | 1,000 | 10 | Customer information and segmentation |
| `products.csv` | 1,000 | 10 | Product details and pricing |
| `orders.csv` | 1,000,000 | 16 | Transactions, sales, costs and profits |

The row counts above are the dataset sizes I was working with, not a record of a successful final Snowflake load. The final counts should be confirmed with SQL after loading.

## 1. Starting with a clean workspace

My previous Snowflake workspace had been deleted, so I had to start the project from the beginning. I created a new SQL worksheet and checked the active account settings before creating any database objects.

```sql
SELECT
    CURRENT_USER() AS USER_NAME,
    CURRENT_ROLE() AS ACTIVE_ROLE,
    CURRENT_WAREHOUSE() AS ACTIVE_WAREHOUSE,
    CURRENT_DATABASE() AS ACTIVE_DATABASE,
    CURRENT_SCHEMA() AS ACTIVE_SCHEMA;
```

This check is useful because commands can run against the wrong database or schema when the worksheet context is not set explicitly.

### Creating the database and schemas

I separated the original imported data from the transformed data. The `RAW` schema is for the CSV imports, while `CLEAN` is for SQL transformation outputs.

```sql
CREATE DATABASE IF NOT EXISTS ECOMMERCE_DB;
CREATE SCHEMA IF NOT EXISTS ECOMMERCE_DB.RAW;
CREATE SCHEMA IF NOT EXISTS ECOMMERCE_DB.CLEAN;

USE DATABASE ECOMMERCE_DB;
USE SCHEMA ECOMMERCE_DB.RAW;

SHOW SCHEMAS IN DATABASE ECOMMERCE_DB;
```

### Creating a compute warehouse

```sql
CREATE WAREHOUSE IF NOT EXISTS ECOMMERCE_WH
    WAREHOUSE_SIZE = 'XSMALL'
    AUTO_SUSPEND = 60
    AUTO_RESUME = TRUE
    INITIALLY_SUSPENDED = TRUE;

USE WAREHOUSE ECOMMERCE_WH;
SELECT CURRENT_WAREHOUSE();
```

I chose an X-Small warehouse for the exercise, with automatic suspend and resume enabled. This avoids leaving compute running unnecessarily when I am not executing queries.

## 2. Creating tables based on the actual CSV files

I initially had an Orders table with **19 columns**. However, the `orders.csv` I eventually used had **16 columns**. The table structure needed to follow the actual source file, not the earlier example dataset.

### Orders — 16 columns

```sql
CREATE TABLE ECOMMERCE_DB.RAW.ORDERS (
    ORDER_ID VARCHAR(30),
    ORDER_DATE DATE,
    CUSTOMER_ID VARCHAR(30),
    PRODUCT_ID VARCHAR(30),
    QUANTITY NUMBER(12,0),
    UNIT_PRICE NUMBER(12,2),
    GROSS_AMOUNT NUMBER(14,2),
    DISCOUNT_PCT NUMBER(5,2),
    DISCOUNT_AMOUNT NUMBER(14,2),
    NET_SALES NUMBER(14,2),
    UNIT_COST NUMBER(12,2),
    TOTAL_COST NUMBER(14,2),
    PROFIT NUMBER(14,2),
    PAYMENT_METHOD VARCHAR(100),
    SALES_CHANNEL VARCHAR(100),
    ORDER_STATUS VARCHAR(100)
);
```

### Customers — 10 columns

```sql
CREATE TABLE ECOMMERCE_DB.RAW.CUSTOMERS (
    CUSTOMER_ID VARCHAR(30),
    CUSTOMER_NAME VARCHAR(255),
    EMAIL VARCHAR(255),
    GENDER VARCHAR(20),
    AGE NUMBER(3,0),
    CITY VARCHAR(100),
    STATE VARCHAR(100),
    REGION VARCHAR(100),
    SIGNUP_DATE DATE,
    CUSTOMER_SEGMENT VARCHAR(100)
);
```

### Products — 10 columns

```sql
CREATE TABLE ECOMMERCE_DB.RAW.PRODUCTS (
    PRODUCT_ID VARCHAR(30),
    PRODUCT_NAME VARCHAR(255),
    CATEGORY VARCHAR(100),
    SUBCATEGORY VARCHAR(100),
    BRAND VARCHAR(100),
    UNIT_PRICE NUMBER(12,2),
    UNIT_COST NUMBER(12,2),
    SUPPLIER VARCHAR(255),
    LAUNCH_DATE DATE,
    IS_ACTIVE VARCHAR(10)
);
```

The above definitions follow the column layouts recorded for the larger dataset. Before recreating a table, I need to check whether it contains data worth preserving: `DROP TABLE` removes the table and its contents.

```sql
SHOW TABLES IN SCHEMA ECOMMERCE_DB.RAW;
DESCRIBE TABLE ECOMMERCE_DB.RAW.ORDERS;
DESCRIBE TABLE ECOMMERCE_DB.RAW.CUSTOMERS;
DESCRIBE TABLE ECOMMERCE_DB.RAW.PRODUCTS;
```

## 3. Configuring CSV ingestion

I set up a CSV file format so Snowflake would interpret headers, quoted fields, empty values and dates consistently.

```sql
CREATE FILE FORMAT IF NOT EXISTS ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT
    TYPE = CSV
    FIELD_DELIMITER = ','
    SKIP_HEADER = 1
    FIELD_OPTIONALLY_ENCLOSED_BY = '"'
    EMPTY_FIELD_AS_NULL = TRUE
    NULL_IF = ('NULL', 'null', '')
    DATE_FORMAT = 'YYYY-MM-DD'
    TRIM_SPACE = TRUE
    ERROR_ON_COLUMN_COUNT_MISMATCH = TRUE;
```

I intentionally kept `ERROR_ON_COLUMN_COUNT_MISMATCH = TRUE`. Turning it off to get past an import failure can silently put data into the wrong columns or introduce missing values.

I also created an internal stage to hold the files before importing them.

```sql
CREATE STAGE IF NOT EXISTS ECOMMERCE_DB.RAW.ECOMMERCE_STAGE
    FILE_FORMAT = ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT;

SHOW STAGES IN SCHEMA ECOMMERCE_DB.RAW;
LIST @ECOMMERCE_DB.RAW.ECOMMERCE_STAGE;
```

The intended flow was to upload `customers.csv`, `products.csv` and `orders.csv` through Snowsight and then check that the staged files appeared in the `LIST` output. Exact staged filenames matter because `COPY INTO ... FILES = (...)` uses the filenames present in the stage.

## 4. Problems encountered during loading

### Error 1 — Orders CSV column count did not match the table

**Where it happened:** While validating `orders.csv` using `COPY INTO` with `VALIDATION_MODE = 'RETURN_ERRORS'`.

```sql
COPY INTO ECOMMERCE_DB.RAW.ORDERS
FROM @ECOMMERCE_DB.RAW.ECOMMERCE_STAGE
FILES = ('orders.csv')
FILE_FORMAT = (
    FORMAT_NAME = 'ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT'
)
VALIDATION_MODE = 'RETURN_ERRORS';
```

**What was wrong:** The table had 19 columns, but the CSV contained only 16. I had carried over a table definition from a different version of the dataset. Snowflake could not map the file fields correctly.

**How I addressed it:** I compared the CSV header with the table definition and changed the Orders schema to the 16-column structure shown earlier. For a disposable, empty training table, the replacement sequence was:

```sql
-- Only use this if the old ORDERS table has no data to preserve.
DROP TABLE IF EXISTS ECOMMERCE_DB.RAW.ORDERS;

-- Recreate ORDERS using the 16-column definition in Section 2.
DESCRIBE TABLE ECOMMERCE_DB.RAW.ORDERS;
```

The `DESCRIBE` command above is a **post-recreation check**; running it directly after the `DROP` will fail until the `CREATE TABLE` command is executed. Once the table matches the source layout, the CSV validation can be retried.

**What I learned:** A CSV's field count and field order should be checked before writing `CREATE TABLE`. `CREATE TABLE IF NOT EXISTS` does not repair an existing table with the wrong columns.

### Error 2 — Invalid date in a CSV file

During the earlier e-commerce load attempt, I encountered a date value of `2026-02-30` in the `order_date` field. February 30 is not a real calendar date, so Snowflake cannot convert it to a `DATE` when the file format expects `YYYY-MM-DD`.

**How I handled the correction workflow:** I used Google Colab/Pandas to inspect the column, identify values that could not be parsed as dates, and prepare a corrected CSV. Unknown dates should be blanked out or corrected only against a trustworthy source; they should not be replaced with arbitrary dates.

```python
import pandas as pd

input_file = 'cleaned_ecommerce.csv'
df = pd.read_csv(input_file, dtype=str)

raw_dates = df['order_date'].copy()
parsed_dates = pd.to_datetime(raw_dates, format='%Y-%m-%d', errors='coerce')
invalid = raw_dates.notna() & raw_dates.str.strip().ne('') & parsed_dates.isna()

print('Invalid date rows:', invalid.sum())
print(df.loc[invalid, ['order_date']].head(20))

# Keep valid dates; leave unverified invalid dates empty.
df['order_date'] = parsed_dates.dt.strftime('%Y-%m-%d')
df.to_csv('cleaned_ecommerce_valid.csv', index=False, encoding='utf-8')
```

This correction workflow relates to the earlier `cleaned_ecommerce.csv` import, not automatically to the later 16-column, million-row `orders.csv`. I would validate the later file separately rather than assume the same error appears there.

**What I learned:** A CSV can have the correct number of columns and still fail to load because of invalid data types. Date validation should happen before the Snowflake import.

### Additional issue — SQL written for the wrong version of the dataset

Some of my initial SQL used `SALES_AMOUNT_INR`, `PROFIT_INR` and `DISCOUNT_PERCENT`. Those fields belonged to the smaller example dataset. My larger Orders file instead used `NET_SALES`, `PROFIT` and `DISCOUNT_PCT`.

I needed to update all later transformations and queries to reference the **actual** column names. Otherwise, Snowflake would return an invalid identifier error.

## 5. Validating and loading the data

After correcting the schema and source data, I can run validation without inserting rows:

```sql
COPY INTO ECOMMERCE_DB.RAW.ORDERS
FROM @ECOMMERCE_DB.RAW.ECOMMERCE_STAGE
FILES = ('orders.csv')
FILE_FORMAT = (
    FORMAT_NAME = 'ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT'
)
VALIDATION_MODE = 'RETURN_ERRORS';
```

When validation returns no errors, the load command is:

```sql
COPY INTO ECOMMERCE_DB.RAW.ORDERS
FROM @ECOMMERCE_DB.RAW.ECOMMERCE_STAGE
FILES = ('orders.csv')
FILE_FORMAT = (
    FORMAT_NAME = 'ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT'
)
ON_ERROR = 'ABORT_STATEMENT';
```

The equivalent commands for the other two datasets are:

```sql
COPY INTO ECOMMERCE_DB.RAW.CUSTOMERS
FROM @ECOMMERCE_DB.RAW.ECOMMERCE_STAGE
FILES = ('customers.csv')
FILE_FORMAT = (
    FORMAT_NAME = 'ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT'
)
ON_ERROR = 'ABORT_STATEMENT';

COPY INTO ECOMMERCE_DB.RAW.PRODUCTS
FROM @ECOMMERCE_DB.RAW.ECOMMERCE_STAGE
FILES = ('products.csv')
FILE_FORMAT = (
    FORMAT_NAME = 'ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT'
)
ON_ERROR = 'ABORT_STATEMENT';
```

After loading, I would check the counts and a sample of each table. This is more reliable than relying on an upload-success message alone.

```sql
SELECT 'CUSTOMERS' AS SOURCE, COUNT(*) AS ROW_COUNT
FROM ECOMMERCE_DB.RAW.CUSTOMERS
UNION ALL
SELECT 'PRODUCTS', COUNT(*) FROM ECOMMERCE_DB.RAW.PRODUCTS
UNION ALL
SELECT 'ORDERS', COUNT(*) FROM ECOMMERCE_DB.RAW.ORDERS;

SELECT * FROM ECOMMERCE_DB.RAW.ORDERS LIMIT 10;

SELECT
    SUM(NET_SALES) AS TOTAL_REVENUE,
    SUM(PROFIT) AS TOTAL_PROFIT
FROM ECOMMERCE_DB.RAW.ORDERS;
```

**Verification status:** The source notes include the expected counts and loading commands, but do not include the final successful load output or actual row-count result. Those need to be recorded from Snowflake before presenting the import as completed.

## 6. Transforming data into the CLEAN schema

The next step is to leave `RAW` untouched and create cleaned tables using `CREATE OR REPLACE TABLE ... AS SELECT` (CTAS). I adapted the transformations to the actual column names of the larger dataset.

### Clean customer records

```sql
CREATE OR REPLACE TABLE ECOMMERCE_DB.CLEAN.CUSTOMERS AS
SELECT
    CUSTOMER_ID,
    COALESCE(NULLIF(TRIM(CUSTOMER_NAME), ''), 'Unknown') AS CUSTOMER_NAME,
    LOWER(TRIM(EMAIL)) AS EMAIL,
    UPPER(TRIM(GENDER)) AS GENDER,
    AGE,
    UPPER(TRIM(CITY)) AS CITY,
    UPPER(TRIM(STATE)) AS STATE,
    UPPER(TRIM(REGION)) AS REGION,
    SIGNUP_DATE,
    UPPER(TRIM(CUSTOMER_SEGMENT)) AS CUSTOMER_SEGMENT
FROM ECOMMERCE_DB.RAW.CUSTOMERS;
```

This standardizes text casing, removes leading/trailing spaces, and gives blank customer names a consistent placeholder.

### Clean product records and calculate margin

```sql
CREATE OR REPLACE TABLE ECOMMERCE_DB.CLEAN.PRODUCTS AS
SELECT
    PRODUCT_ID,
    TRIM(PRODUCT_NAME) AS PRODUCT_NAME,
    UPPER(TRIM(CATEGORY)) AS CATEGORY,
    UPPER(TRIM(SUBCATEGORY)) AS SUBCATEGORY,
    UPPER(TRIM(BRAND)) AS BRAND,
    UNIT_PRICE,
    UNIT_COST,
    TRIM(SUPPLIER) AS SUPPLIER,
    LAUNCH_DATE,
    UPPER(TRIM(IS_ACTIVE)) AS IS_ACTIVE,
    ROUND(UNIT_PRICE - UNIT_COST, 2) AS UNIT_MARGIN
FROM ECOMMERCE_DB.RAW.PRODUCTS;
```

`UNIT_MARGIN` gives the difference between selling price and unit cost. It is useful when comparing products and categories.

### Clean orders and add useful calculated fields

```sql
CREATE OR REPLACE TABLE ECOMMERCE_DB.CLEAN.ORDERS AS
SELECT
    ORDER_ID,
    ORDER_DATE,
    CUSTOMER_ID,
    PRODUCT_ID,
    QUANTITY,
    UNIT_PRICE,
    GROSS_AMOUNT,
    COALESCE(DISCOUNT_PCT, 0) AS DISCOUNT_PCT,
    DISCOUNT_AMOUNT,
    NET_SALES,
    UNIT_COST,
    TOTAL_COST,
    PROFIT,
    UPPER(TRIM(PAYMENT_METHOD)) AS PAYMENT_METHOD,
    UPPER(TRIM(SALES_CHANNEL)) AS SALES_CHANNEL,
    UPPER(TRIM(ORDER_STATUS)) AS ORDER_STATUS,
    YEAR(ORDER_DATE) AS ORDER_YEAR,
    MONTH(ORDER_DATE) AS ORDER_MONTH,
    ROUND(QUANTITY * UNIT_PRICE *
          (1 - COALESCE(DISCOUNT_PCT, 0) / 100), 2) AS EXPECTED_NET_SALES
FROM ECOMMERCE_DB.RAW.ORDERS;
```

I can compare `EXPECTED_NET_SALES` against the source `NET_SALES` to identify potential calculation differences. The formula assumes that `NET_SALES` excludes any extra charges not represented in these fields; that assumption should be checked against the dataset definition.

```sql
SELECT
    ORDER_ID,
    NET_SALES,
    EXPECTED_NET_SALES,
    ROUND(NET_SALES - EXPECTED_NET_SALES, 2) AS SALES_DIFFERENCE
FROM ECOMMERCE_DB.CLEAN.ORDERS
WHERE ABS(NET_SALES - EXPECTED_NET_SALES) > 0.01
LIMIT 20;
```

## 7. Joining the datasets and analyzing sales

To add customer and product details to transactions, I can join the three cleaned tables using their ID fields.

```sql
SELECT
    O.ORDER_ID,
    O.ORDER_DATE,
    C.CUSTOMER_NAME,
    C.CITY,
    P.PRODUCT_NAME,
    P.CATEGORY,
    O.QUANTITY,
    O.NET_SALES,
    O.PROFIT
FROM ECOMMERCE_DB.CLEAN.ORDERS O
JOIN ECOMMERCE_DB.CLEAN.CUSTOMERS C
    ON O.CUSTOMER_ID = C.CUSTOMER_ID
JOIN ECOMMERCE_DB.CLEAN.PRODUCTS P
    ON O.PRODUCT_ID = P.PRODUCT_ID
LIMIT 20;
```

A simple category-level summary is:

```sql
SELECT
    P.CATEGORY,
    COUNT(*) AS ORDER_LINES,
    SUM(O.QUANTITY) AS UNITS_SOLD,
    ROUND(SUM(O.NET_SALES), 2) AS TOTAL_SALES,
    ROUND(SUM(O.PROFIT), 2) AS TOTAL_PROFIT
FROM ECOMMERCE_DB.CLEAN.ORDERS O
JOIN ECOMMERCE_DB.CLEAN.PRODUCTS P
    ON O.PRODUCT_ID = P.PRODUCT_ID
GROUP BY P.CATEGORY
ORDER BY TOTAL_SALES DESC;
```

The joins assume `CUSTOMER_ID` and `PRODUCT_ID` uniquely identify rows in their respective reference tables. I should check duplicate IDs first, because duplicate keys can inflate order and sales totals.

```sql
SELECT CUSTOMER_ID, COUNT(*) AS N
FROM ECOMMERCE_DB.CLEAN.CUSTOMERS
GROUP BY CUSTOMER_ID HAVING COUNT(*) > 1;

SELECT PRODUCT_ID, COUNT(*) AS N
FROM ECOMMERCE_DB.CLEAN.PRODUCTS
GROUP BY PRODUCT_ID HAVING COUNT(*) > 1;
```

## 8. Query optimization and Query Profile

The performance part of the assignment is to compare queries and inspect what Snowflake actually does. I would execute each query pair, open **Monitoring → Query History → Query Profile**, and note execution time, bytes scanned, partition counts and expensive operators.

To avoid returning a saved query result during comparisons:

```sql
ALTER SESSION SET USE_CACHED_RESULT = FALSE;
```

This disables persisted result reuse for the session, but does **not** remove warehouse data caching or other sources of run-to-run variation.

### Experiment A — Read only needed columns

```sql
-- Broad query
SELECT * FROM ECOMMERCE_DB.CLEAN.ORDERS;

-- Narrower query
SELECT ORDER_ID, PRODUCT_ID, QUANTITY, NET_SALES
FROM ECOMMERCE_DB.CLEAN.ORDERS;
```

The second query projects fewer columns. This can reduce scanning and data transfer, although the exact benefit depends on the query plan.

### Experiment B — Filter by order status

```sql
-- All order statuses
SELECT ORDER_ID, ORDER_STATUS, NET_SALES
FROM ECOMMERCE_DB.CLEAN.ORDERS;

-- Only completed orders
SELECT ORDER_ID, ORDER_STATUS, NET_SALES
FROM ECOMMERCE_DB.CLEAN.ORDERS
WHERE ORDER_STATUS = 'COMPLETED';
```

This example is useful only if `COMPLETED` exists in the data. The status values should be checked first with `SELECT DISTINCT ORDER_STATUS`.

### Experiment C — Apply a date range directly

```sql
-- Function applied to the date column
SELECT ORDER_ID, ORDER_DATE, NET_SALES
FROM ECOMMERCE_DB.CLEAN.ORDERS
WHERE YEAR(ORDER_DATE) = 2025;

-- Direct range predicate
SELECT ORDER_ID, ORDER_DATE, NET_SALES
FROM ECOMMERCE_DB.CLEAN.ORDERS
WHERE ORDER_DATE >= '2025-01-01'
  AND ORDER_DATE < '2026-01-01';
```

Both expressions target 2025 orders. The direct date range is a useful pattern to test for micro-partition pruning. I should not assume it will always run faster without checking the profile, especially if the date range covers much of the table.

### Experiment D — Filter before aggregation

```sql
SELECT
    PRODUCT_ID,
    SUM(QUANTITY) AS UNITS_SOLD,
    SUM(NET_SALES) AS TOTAL_SALES
FROM ECOMMERCE_DB.CLEAN.ORDERS
WHERE ORDER_STATUS = 'COMPLETED'
GROUP BY PRODUCT_ID
ORDER BY TOTAL_SALES DESC;
```

This answers a more specific business question than aggregating every order. It should not be compared as if it were semantically identical to an all-status aggregation.

### What to record

| Test | Before execution time | After execution time | Before bytes scanned | After bytes scanned | Partition changes |
|---|---|---|---|---|---|
| Selected columns | Not recorded | Not recorded | Not recorded | Not recorded | Not recorded |
| Status filter | Not recorded | Not recorded | Not recorded | Not recorded | Not recorded |
| Date filter | Not recorded | Not recorded | Not recorded | Not recorded | Not recorded |

I have left measured values blank because the available notes do not contain actual Query Profile statistics. Query execution results and screenshots should be added once tested. On smaller tables or when data is cached, differences may be negligible.

After testing, I can restore the normal session setting:

```sql
ALTER SESSION SET USE_CACHED_RESULT = TRUE;
```

## 9. Final checks and evidence

Before submitting the project, I need the following evidence from the Snowflake workspace:

- Database, `RAW` and `CLEAN` schemas, and `ECOMMERCE_WH` warehouse.
- Actual `DESCRIBE TABLE` results for all three source tables.
- Staged file names from the `LIST` command.
- CSV validation and `COPY INTO` load status for each file.
- Final `COUNT(*)` results from each raw table.
- Samples of transformed records and joined sales reports.
- Query History and Query Profile screenshots for the query comparisons.
- Recorded before/after metrics, without claiming a speedup unless measured.

## 10. Key takeaways

The most important part of this project was getting the data definition right. I initially worked with a table layout that did not match the CSV and received a 19-versus-16-column load error. I also had to account for malformed dates in an earlier data file. Both issues reinforced the value of validating source files before loading them into Snowflake.

Using separate `RAW` and `CLEAN` schemas makes the process easier to verify and repeat. Once the actual data is loaded, the transformations, joins and Query Profile comparisons provide a practical way to study data preparation and SQL performance.

**Project status in these notes:** Setup commands, source schemas, troubleshooting steps and the planned analysis SQL are documented. Final load counts, completed transformations and measured optimization results still require confirmation from Snowflake outputs; they are not presented here as results already obtained.
