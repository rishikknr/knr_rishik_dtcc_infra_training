# Snowflake E-Commerce Data Pipeline and Query Performance Study

**Practical session:** 4 November 2026  
**Platform:** Snowflake (Snowsight / SQL Worksheets)  
**Project database:** `ECOMMERCE_DB`  
**Objective:** Load a custom e-commerce dataset, transform the raw data, join related tables, and study query execution using Query Profile.

---

## 1. Overview

In this practical, I worked with three related e-commerce CSV files: customers, products, and orders. I used Snowflake to create the required environment, upload the files to an internal stage, load them into raw tables, prepare separate cleaned tables, and run analytical queries. The last part of the exercise was query optimization: comparing alternative SQL statements and checking their execution plans in Query Profile.

I kept the original source tables in a `RAW` schema and created transformed copies in a `CLEAN` schema. This makes it easier to validate changes without modifying the imported data.

### Datasets used

| CSV file | Data rows | Columns | Main purpose |
|---|---:|---:|---|
| `customers.csv` | 1,000 | 10 | Customer details, location, segment, signup date |
| `products.csv` | 1,000 | 10 | Product details, pricing, cost, category |
| `orders.csv` | 1,000,000 | 16 | Transactions, sales, discounts, costs, profit |

The counts exclude the CSV header row. All three files are related by `customer_id` and `product_id`.

### Project layout

```text
ECOMMERCE_DB
├── RAW
│   ├── CUSTOMERS             (original imported data)
│   ├── PRODUCTS              (original imported data)
│   ├── ORDERS                (original imported data)
│   ├── ECOMMERCE_CSV_FORMAT  (file format)
│   └── ECOMMERCE_STAGE       (internal stage)
└── CLEAN
    ├── CUSTOMERS             (transformed copy)
    ├── PRODUCTS              (transformed copy)
    └── ORDERS                (transformed copy)

Compute: ECOMMERCE_WH (X-Small warehouse)
```

The work was done in the following order: environment setup → table definitions → CSV stage → file upload → data loading → validation → transformations → joins → query tuning and profiling.

## 2. Creating the Snowflake environment

I first checked which role, database, schema, and warehouse were active in the worksheet:

```sql
SELECT
    CURRENT_USER() AS USER_NAME,
    CURRENT_ROLE() AS ACTIVE_ROLE,
    CURRENT_WAREHOUSE() AS ACTIVE_WAREHOUSE,
    CURRENT_DATABASE() AS ACTIVE_DATABASE,
    CURRENT_SCHEMA() AS ACTIVE_SCHEMA;
```

I then created the database and two schemas. The `RAW` schema is for the source CSV data; the `CLEAN` schema is for processed data.

```sql
CREATE DATABASE IF NOT EXISTS ECOMMERCE_DB;

CREATE SCHEMA IF NOT EXISTS ECOMMERCE_DB.RAW;
CREATE SCHEMA IF NOT EXISTS ECOMMERCE_DB.CLEAN;

USE DATABASE ECOMMERCE_DB;
USE SCHEMA RAW;
```

Next, I created an X-Small virtual warehouse to run the actual data processing queries:

```sql
CREATE WAREHOUSE IF NOT EXISTS ECOMMERCE_WH
    WAREHOUSE_SIZE = 'XSMALL'
    AUTO_SUSPEND = 60
    AUTO_RESUME = TRUE
    INITIALLY_SUSPENDED = TRUE;

USE WAREHOUSE ECOMMERCE_WH;

SHOW DATABASES LIKE 'ECOMMERCE_DB';
SHOW WAREHOUSES LIKE 'ECOMMERCE_WH';
SHOW SCHEMAS IN DATABASE ECOMMERCE_DB;
```

**Why these settings?** `AUTO_SUSPEND = 60` lets the warehouse suspend after roughly one minute of inactivity, while `AUTO_RESUME = TRUE` lets Snowflake restart it when a query needs compute. An X-Small warehouse is a sensible starting point for this practical, although the best size depends on workload and performance requirements.

### Session screenshots — environment setup (chronological)

The screenshots in this document are placed in filename timestamp order. Since they are linked by filename rather than individually inspected here, the time groups act as a chronological record of the session, not as a claim that each image proves a particular SQL statement.


![Work session — capture 01 (5.38.33 PM)](Screenshot%202026-11-04%20at%205.38.33%E2%80%AFPM.png)
*Capture 01 · 5.38.33 PM*

![Work session — capture 02 (5.39.09 PM)](Screenshot%202026-11-04%20at%205.39.09%E2%80%AFPM.png)
*Capture 02 · 5.39.09 PM*

![Work session — capture 03 (5.39.54 PM)](Screenshot%202026-11-04%20at%205.39.54%E2%80%AFPM.png)
*Capture 03 · 5.39.54 PM*

![Work session — capture 04 (5.40.22 PM)](Screenshot%202026-11-04%20at%205.40.22%E2%80%AFPM.png)
*Capture 04 · 5.40.22 PM*

![Work session — capture 05 (5.40.43 PM)](Screenshot%202026-11-04%20at%205.40.43%E2%80%AFPM.png)
*Capture 05 · 5.40.43 PM*

![Work session — capture 06 (5.41.04 PM)](Screenshot%202026-11-04%20at%205.41.04%E2%80%AFPM.png)
*Capture 06 · 5.41.04 PM*

![Work session — capture 07 (5.41.45 PM)](Screenshot%202026-11-04%20at%205.41.45%E2%80%AFPM.png)
*Capture 07 · 5.41.45 PM*

![Work session — capture 08 (5.42.31 PM)](Screenshot%202026-11-04%20at%205.42.31%E2%80%AFPM.png)
*Capture 08 · 5.42.31 PM*


## 3. Defining the three raw tables

One important detail is that the destination table must match the CSV structure. My `orders.csv` has **16 columns**, not 19. During loading, Snowflake reported a column-count mismatch when the table had the wrong definition. I corrected the schema instead of telling Snowflake to ignore the mismatch.

### 3.1 Customers table (10 columns)

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

### 3.2 Products table (10 columns)

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

### 3.3 Orders table (16 columns)

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

These table definitions are the **fresh-setup versions** of the SQL. If a table already exists, I check its structure rather than running `CREATE TABLE` again:

```sql
SHOW TABLES IN SCHEMA ECOMMERCE_DB.RAW;
DESCRIBE TABLE ECOMMERCE_DB.RAW.CUSTOMERS;
DESCRIBE TABLE ECOMMERCE_DB.RAW.PRODUCTS;
DESCRIBE TABLE ECOMMERCE_DB.RAW.ORDERS;
```

### 3.4 Error I encountered: 16-column CSV vs. 19-column table

At one point the loader returned this message:

> Number of columns in file (16) does not match that of the corresponding table (19).

The cause was the wrong `ORDERS` table structure, not a corrupt CSV file. The fix was to recreate the training table using the actual 16 CSV columns. I did **not** set `ERROR_ON_COLUMN_COUNT_MISMATCH = FALSE`, because that could hide problems in the imported dataset. Dropping a table removes its existing rows; this correction should only be used when those rows are not needed.

For the clean rebuild, I removed the old project database and recreated it before making the corrected tables. This was specific to the disposable training setup; it would not be a safe way to fix a production database.

### Session screenshots — table definitions and staging preparation


![Work session — capture 09 (5.42.56 PM)](Screenshot%202026-11-04%20at%205.42.56%E2%80%AFPM.png)
*Capture 09 · 5.42.56 PM*

![Work session — capture 10 (5.44.41 PM)](Screenshot%202026-11-04%20at%205.44.41%E2%80%AFPM.png)
*Capture 10 · 5.44.41 PM*

![Work session — capture 11 (5.45.28 PM)](Screenshot%202026-11-04%20at%205.45.28%E2%80%AFPM.png)
*Capture 11 · 5.45.28 PM*

![Work session — capture 12 (5.49.48 PM)](Screenshot%202026-11-04%20at%205.49.48%E2%80%AFPM.png)
*Capture 12 · 5.49.48 PM*

![Work session — capture 13 (5.50.49 PM)](Screenshot%202026-11-04%20at%205.50.49%E2%80%AFPM.png)
*Capture 13 · 5.50.49 PM*

![Work session — capture 14 (5.52.30 PM)](Screenshot%202026-11-04%20at%205.52.30%E2%80%AFPM.png)
*Capture 14 · 5.52.30 PM*


## 4. Creating a CSV file format and stage

The raw CSV files need a predictable parsing format. I created one reusable CSV file format:

```sql
CREATE FILE FORMAT ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT
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

The settings I needed most were:

| Setting | Purpose |
|---|---|
| `TYPE = CSV` | Treat files as comma-separated values |
| `SKIP_HEADER = 1` | Skip the header row |
| `FIELD_OPTIONALLY_ENCLOSED_BY = '"'` | Read quoted fields correctly, including commas inside quotes |
| `EMPTY_FIELD_AS_NULL = TRUE` | Interpret empty fields as `NULL` |
| `DATE_FORMAT = 'YYYY-MM-DD'` | Read dates in the expected format |
| `ERROR_ON_COLUMN_COUNT_MISMATCH = TRUE` | Reject unexpected column counts rather than silently accepting them |

I created a named internal stage to hold the uploaded files:

```sql
CREATE STAGE ECOMMERCE_DB.RAW.ECOMMERCE_STAGE
    FILE_FORMAT = ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT;

SHOW STAGES IN SCHEMA ECOMMERCE_DB.RAW;
```

**Why a stage?** Uploading a file to a Snowflake stage makes it available for loading. The file is not yet part of the target table: `COPY INTO` performs that step. I could use one stage for all three datasets because they share the same CSV parsing settings. The specific file is selected during each load.

## 5. Uploading and loading the datasets

In Snowsight, I opened the stage upload interface and selected `customers.csv`, `products.csv`, and `orders.csv`. I verified the files were visible in the stage:

```sql
LIST @ECOMMERCE_DB.RAW.ECOMMERCE_STAGE;
```

### 5.1 Validate the Orders file before loading

```sql
COPY INTO ECOMMERCE_DB.RAW.ORDERS
FROM @ECOMMERCE_DB.RAW.ECOMMERCE_STAGE
FILES = ('orders.csv')
FILE_FORMAT = (
    FORMAT_NAME = 'ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT'
)
VALIDATION_MODE = 'RETURN_ERRORS';
```

The validation command is useful because it checks for parsing or conversion problems **without loading records**. Any errors need to be resolved before running the actual import.

### 5.2 Load Customers

```sql
COPY INTO ECOMMERCE_DB.RAW.CUSTOMERS
FROM @ECOMMERCE_DB.RAW.ECOMMERCE_STAGE
FILES = ('customers.csv')
FILE_FORMAT = (
    FORMAT_NAME = 'ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT'
)
ON_ERROR = 'ABORT_STATEMENT';
```

### 5.3 Load Products

```sql
COPY INTO ECOMMERCE_DB.RAW.PRODUCTS
FROM @ECOMMERCE_DB.RAW.ECOMMERCE_STAGE
FILES = ('products.csv')
FILE_FORMAT = (
    FORMAT_NAME = 'ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT'
)
ON_ERROR = 'ABORT_STATEMENT';
```

### 5.4 Load Orders

```sql
COPY INTO ECOMMERCE_DB.RAW.ORDERS
FROM @ECOMMERCE_DB.RAW.ECOMMERCE_STAGE
FILES = ('orders.csv')
FILE_FORMAT = (
    FORMAT_NAME = 'ECOMMERCE_DB.RAW.ECOMMERCE_CSV_FORMAT'
)
ON_ERROR = 'ABORT_STATEMENT';
```

Using `FILES = (...)` ensures that each table receives the intended file instead of attempting to load all files from the stage. `ON_ERROR = 'ABORT_STATEMENT'` avoids accepting a partially successful load when a file contains unexpected errors.

### 5.5 Check imported row counts

```sql
SELECT 'CUSTOMERS' AS TABLE_NAME, COUNT(*) AS RECORDS
FROM ECOMMERCE_DB.RAW.CUSTOMERS
UNION ALL
SELECT 'PRODUCTS', COUNT(*)
FROM ECOMMERCE_DB.RAW.PRODUCTS
UNION ALL
SELECT 'ORDERS', COUNT(*)
FROM ECOMMERCE_DB.RAW.ORDERS;
```

For these source files, the expected counts are **1,000**, **1,000**, and **1,000,000**, respectively. These are expected values based on the CSVs; the SQL output is the actual confirmation after loading.

I also previewed the imported tables:

```sql
SELECT * FROM ECOMMERCE_DB.RAW.CUSTOMERS LIMIT 10;
SELECT * FROM ECOMMERCE_DB.RAW.PRODUCTS LIMIT 10;
SELECT * FROM ECOMMERCE_DB.RAW.ORDERS LIMIT 10;
```

### Session screenshots — file upload, loading, and validation


![Work session — capture 15 (5.53.14 PM)](Screenshot%202026-11-04%20at%205.53.14%E2%80%AFPM.png)
*Capture 15 · 5.53.14 PM*

![Work session — capture 16 (5.53.45 PM)](Screenshot%202026-11-04%20at%205.53.45%E2%80%AFPM.png)
*Capture 16 · 5.53.45 PM*

![Work session — capture 17 (5.53.52 PM)](Screenshot%202026-11-04%20at%205.53.52%E2%80%AFPM.png)
*Capture 17 · 5.53.52 PM*

![Work session — capture 18 (5.54.33 PM)](Screenshot%202026-11-04%20at%205.54.33%E2%80%AFPM.png)
*Capture 18 · 5.54.33 PM*

![Work session — capture 19 (5.55.07 PM)](Screenshot%202026-11-04%20at%205.55.07%E2%80%AFPM.png)
*Capture 19 · 5.55.07 PM*

![Work session — capture 20 (5.56.15 PM)](Screenshot%202026-11-04%20at%205.56.15%E2%80%AFPM.png)
*Capture 20 · 5.56.15 PM*

![Work session — capture 21 (5.56.39 PM)](Screenshot%202026-11-04%20at%205.56.39%E2%80%AFPM.png)
*Capture 21 · 5.56.39 PM*


## 6. Checking the data before transformation

After loading, I checked missing values and made sure the relationship keys were present. This helped distinguish source-data issues from SQL or table-definition issues.

```sql
SELECT
    COUNT(*) AS TOTAL_ORDERS,
    COUNT_IF(ORDER_ID IS NULL) AS MISSING_ORDER_IDS,
    COUNT_IF(CUSTOMER_ID IS NULL) AS MISSING_CUSTOMER_IDS,
    COUNT_IF(PRODUCT_ID IS NULL) AS MISSING_PRODUCT_IDS,
    COUNT_IF(ORDER_DATE IS NULL) AS MISSING_ORDER_DATES
FROM ECOMMERCE_DB.RAW.ORDERS;
```

I also checked the monetary fields. The source table has both revenue and cost, so the profit calculation can be checked directly:

```sql
SELECT
    ORDER_ID,
    GROSS_AMOUNT,
    DISCOUNT_AMOUNT,
    NET_SALES,
    TOTAL_COST,
    PROFIT,
    ROUND(GROSS_AMOUNT - DISCOUNT_AMOUNT, 2) AS EXPECTED_NET_SALES,
    ROUND(NET_SALES - TOTAL_COST, 2) AS EXPECTED_PROFIT
FROM ECOMMERCE_DB.RAW.ORDERS
LIMIT 20;
```

If small differences appear, rounding rules and the original data-generation logic need to be checked rather than assuming the row is incorrect.

## 7. Transforming data with CTAS

I used **CTAS (Create Table As Select)** to create transformed tables in `ECOMMERCE_DB.CLEAN`. This preserves the source data in the `RAW` schema.

### 7.1 Clean Customers

```sql
CREATE OR REPLACE TABLE ECOMMERCE_DB.CLEAN.CUSTOMERS AS
SELECT
    CUSTOMER_ID,
    COALESCE(NULLIF(TRIM(CUSTOMER_NAME), ''), 'Unknown') AS CUSTOMER_NAME,
    LOWER(TRIM(EMAIL)) AS EMAIL,
    GENDER,
    AGE,
    UPPER(TRIM(CITY)) AS CITY,
    UPPER(TRIM(STATE)) AS STATE,
    UPPER(TRIM(REGION)) AS REGION,
    SIGNUP_DATE,
    COALESCE(
        UPPER(NULLIF(TRIM(CUSTOMER_SEGMENT), '')),
        'UNKNOWN'
    ) AS CUSTOMER_SEGMENT
FROM ECOMMERCE_DB.RAW.CUSTOMERS;
```

I used `TRIM` to remove extra spaces, `LOWER` for consistent email formatting, `UPPER` for location/segment labels, and `COALESCE` with `NULLIF` to replace missing or blank names. These are simple transformations, but they show how several cleaning operations can be combined in one statement.

### 7.2 Clean Products and calculate unit margin

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

`UNIT_MARGIN` is a new derived column. It represents the difference between a product's unit selling price and unit cost, before considering order-specific discounts or other expenses.

### 7.3 Clean Orders and add useful time fields

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
    COALESCE(DISCOUNT_AMOUNT, 0) AS DISCOUNT_AMOUNT,
    NET_SALES,
    UNIT_COST,
    TOTAL_COST,
    PROFIT,
    UPPER(TRIM(PAYMENT_METHOD)) AS PAYMENT_METHOD,
    UPPER(TRIM(SALES_CHANNEL)) AS SALES_CHANNEL,
    UPPER(TRIM(ORDER_STATUS)) AS ORDER_STATUS,
    YEAR(ORDER_DATE) AS ORDER_YEAR,
    MONTH(ORDER_DATE) AS ORDER_MONTH,
    ROUND(
        QUANTITY * UNIT_PRICE *
        (1 - COALESCE(DISCOUNT_PCT, 0) / 100),
        2
    ) AS EXPECTED_SALES
FROM ECOMMERCE_DB.RAW.ORDERS;
```

The new `ORDER_YEAR` and `ORDER_MONTH` fields make it easier to group sales by time. `EXPECTED_SALES` provides a second way to check recorded revenue. The clean table is materialized at the moment of creation; it is not an automatically updating view.

### 7.4 Verify the clean tables

```sql
SELECT 'CUSTOMERS' AS TABLE_NAME, COUNT(*) AS RECORDS
FROM ECOMMERCE_DB.CLEAN.CUSTOMERS
UNION ALL
SELECT 'PRODUCTS', COUNT(*)
FROM ECOMMERCE_DB.CLEAN.PRODUCTS
UNION ALL
SELECT 'ORDERS', COUNT(*)
FROM ECOMMERCE_DB.CLEAN.ORDERS;
```

The expected result is the same number of rows as the raw tables. I can also compare an individual order in `RAW.ORDERS` and `CLEAN.ORDERS` by `ORDER_ID` if a computed value looks unusual.

### Session screenshots — transformation work


![Work session — capture 22 (5.57.08 PM)](Screenshot%202026-11-04%20at%205.57.08%E2%80%AFPM.png)
*Capture 22 · 5.57.08 PM*

![Work session — capture 23 (5.57.24 PM)](Screenshot%202026-11-04%20at%205.57.24%E2%80%AFPM.png)
*Capture 23 · 5.57.24 PM*

![Work session — capture 24 (5.57.42 PM)](Screenshot%202026-11-04%20at%205.57.42%E2%80%AFPM.png)
*Capture 24 · 5.57.42 PM*

![Work session — capture 25 (5.57.55 PM)](Screenshot%202026-11-04%20at%205.57.55%E2%80%AFPM.png)
*Capture 25 · 5.57.55 PM*

![Work session — capture 26 (5.58.19 PM)](Screenshot%202026-11-04%20at%205.58.19%E2%80%AFPM.png)
*Capture 26 · 5.58.19 PM*

![Work session — capture 27 (5.58.36 PM)](Screenshot%202026-11-04%20at%205.58.36%E2%80%AFPM.png)
*Capture 27 · 5.58.36 PM*

![Work session — capture 28 (5.58.51 PM)](Screenshot%202026-11-04%20at%205.58.51%E2%80%AFPM.png)
*Capture 28 · 5.58.51 PM*


## 8. Combining the tables using joins

The Orders table contains the transaction keys, while Customers and Products contain descriptive information. I joined them on the corresponding IDs:

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

An inner join returns orders whose customer and product IDs match records in the two reference tables. For data quality, unmatched keys can be checked separately:

```sql
SELECT COUNT(*) AS ORDERS_WITHOUT_MATCHING_CUSTOMER
FROM ECOMMERCE_DB.CLEAN.ORDERS O
LEFT JOIN ECOMMERCE_DB.CLEAN.CUSTOMERS C
    ON O.CUSTOMER_ID = C.CUSTOMER_ID
WHERE C.CUSTOMER_ID IS NULL;

SELECT COUNT(*) AS ORDERS_WITHOUT_MATCHING_PRODUCT
FROM ECOMMERCE_DB.CLEAN.ORDERS O
LEFT JOIN ECOMMERCE_DB.CLEAN.PRODUCTS P
    ON O.PRODUCT_ID = P.PRODUCT_ID
WHERE P.PRODUCT_ID IS NULL;
```

### Category-wise revenue and profit

```sql
SELECT
    P.CATEGORY,
    COUNT(*) AS ORDER_ROWS,
    SUM(O.QUANTITY) AS UNITS_SOLD,
    ROUND(SUM(O.NET_SALES), 2) AS TOTAL_REVENUE,
    ROUND(SUM(O.PROFIT), 2) AS TOTAL_PROFIT
FROM ECOMMERCE_DB.CLEAN.ORDERS O
JOIN ECOMMERCE_DB.CLEAN.PRODUCTS P
    ON O.PRODUCT_ID = P.PRODUCT_ID
GROUP BY P.CATEGORY
ORDER BY TOTAL_REVENUE DESC;
```

This aggregation is more practical than reading one million individual rows: it returns one summarized row per product category.

## 9. Testing SQL optimization techniques

For the performance exercise, I compared SQL statements and inspected the profiles instead of assuming that a shorter query would automatically be faster.

To reduce interference from Snowflake's **persisted query-result cache**, I disabled it for the worksheet session:

```sql
ALTER SESSION SET USE_CACHED_RESULT = FALSE;
```

This does **not** disable all caching: warehouse data caching and other factors can still affect execution time.

### Experiment 1 — Selecting required columns

**More data returned:**

```sql
SELECT *
FROM ECOMMERCE_DB.CLEAN.ORDERS
WHERE ORDER_STATUS = 'DELIVERED';
```

**Only the columns needed:**

```sql
SELECT ORDER_ID, ORDER_DATE, NET_SALES, PROFIT
FROM ECOMMERCE_DB.CLEAN.ORDERS
WHERE ORDER_STATUS = 'DELIVERED';
```

The second version has a smaller projection. These queries intentionally return different sets of columns, so this experiment demonstrates the effect of requesting less data rather than proving two identical queries have different execution costs.

### Experiment 2 — Filtering dates without applying a function to the column

**Function on the date column:**

```sql
SELECT ORDER_ID, ORDER_DATE, NET_SALES
FROM ECOMMERCE_DB.CLEAN.ORDERS
WHERE YEAR(ORDER_DATE) = 2025;
```

**Direct date range:**

```sql
SELECT ORDER_ID, ORDER_DATE, NET_SALES
FROM ECOMMERCE_DB.CLEAN.ORDERS
WHERE ORDER_DATE >= DATE '2025-01-01'
  AND ORDER_DATE < DATE '2026-01-01';
```

These two statements describe the same date range. The second is generally more suitable for checking whether Snowflake can skip irrelevant micro-partitions using the underlying date values. Actual pruning depends on the table's physical organization and the optimizer.

### Experiment 3 — Filtering before aggregation

**All order statuses:**

```sql
SELECT PRODUCT_ID, SUM(NET_SALES) AS TOTAL_SALES
FROM ECOMMERCE_DB.CLEAN.ORDERS
GROUP BY PRODUCT_ID;
```

**Delivered orders only:**

```sql
SELECT PRODUCT_ID, SUM(NET_SALES) AS TOTAL_SALES
FROM ECOMMERCE_DB.CLEAN.ORDERS
WHERE ORDER_STATUS = 'DELIVERED'
GROUP BY PRODUCT_ID;
```

The filter means fewer source rows may reach the aggregate, but these statements answer different business questions. I treated them as a filtering demonstration, not as a strictly equivalent before/after speed benchmark.

### What micro-partition pruning actually means

Snowflake automatically stores table data in micro-partitions with metadata such as ranges of values. If a filter rules out a partition, Snowflake may avoid scanning it. **This is different from caching**: pruning means unnecessary partitions can be skipped, while caching means previously accessed data or results may be reused.

## 10. Opening and reading Query Profile

For each experiment, I ran the SQL in a worksheet and opened the corresponding entry from **Monitoring → Query History**. Inside **Query Profile**, I looked at the operator tree and the available performance statistics.

The main items to record are:

| Query Profile item | What I checked |
|---|---|
| Total execution time | Time spent executing the query |
| Bytes scanned | Data read by scan operators |
| Partitions scanned / total | Whether Snowflake skipped some micro-partitions |
| TableScan | The amount of table data accessed |
| Filter | Where filtering occurred in the execution plan |
| Join / Aggregate | Work done to combine or summarize records |
| Cache indicators | Whether cached data influenced the execution |

### Performance comparison worksheet

Fill this table with the actual values shown in Snowflake. I have left the result cells blank rather than supplying figures that were not measured.

| Experiment | Query | Execution time (ms) | Bytes scanned | Partitions scanned / total |
|---|---|---:|---:|---:|
| 1 | All columns | — | — | — |
| 1 | Selected columns | — | — | — |
| 2 | `YEAR(ORDER_DATE)` | — | — | — |
| 2 | Direct date range | — | — | — |
| 3 | All statuses | — | — | — |
| 3 | Delivered only | — | — | — |

For a more reliable comparison, I should run each query more than once, use the same warehouse size and session settings, and consider warehouse warm-up and caching. Execution times are not guaranteed to improve for every rewrite. A million rows is large enough for meaningful SQL practice, but still relatively small for Snowflake, so some performance differences may be modest.

Once the testing was over, I restored normal persisted-result caching:

```sql
ALTER SESSION SET USE_CACHED_RESULT = TRUE;
```

### Session screenshots — analytical queries and Query Profile


![Work session — capture 29 (5.59.11 PM)](Screenshot%202026-11-04%20at%205.59.11%E2%80%AFPM.png)
*Capture 29 · 5.59.11 PM*

![Work session — capture 30 (5.59.41 PM)](Screenshot%202026-11-04%20at%205.59.41%E2%80%AFPM.png)
*Capture 30 · 5.59.41 PM*

![Work session — capture 31 (5.59.57 PM)](Screenshot%202026-11-04%20at%205.59.57%E2%80%AFPM.png)
*Capture 31 · 5.59.57 PM*

![Work session — capture 32 (6.00.28 PM)](Screenshot%202026-11-04%20at%206.00.28%E2%80%AFPM.png)
*Capture 32 · 6.00.28 PM*

![Work session — capture 33 (6.00.49 PM)](Screenshot%202026-11-04%20at%206.00.49%E2%80%AFPM.png)
*Capture 33 · 6.00.49 PM*

![Work session — capture 34 (6.01.09 PM)](Screenshot%202026-11-04%20at%206.01.09%E2%80%AFPM.png)
*Capture 34 · 6.01.09 PM*

![Work session — capture 35 (6.01.48 PM)](Screenshot%202026-11-04%20at%206.01.48%E2%80%AFPM.png)
*Capture 35 · 6.01.48 PM*

![Work session — capture 36 (6.02.20 PM)](Screenshot%202026-11-04%20at%206.02.20%E2%80%AFPM.png)
*Capture 36 · 6.02.20 PM*

![Work session — capture 37 (6.02.26 PM)](Screenshot%202026-11-04%20at%206.02.26%E2%80%AFPM.png)
*Capture 37 · 6.02.26 PM*

![Work session — capture 38 (6.02.37 PM)](Screenshot%202026-11-04%20at%206.02.37%E2%80%AFPM.png)
*Capture 38 · 6.02.37 PM*

![Work session — capture 39 (6.03.03 PM)](Screenshot%202026-11-04%20at%206.03.03%E2%80%AFPM.png)
*Capture 39 · 6.03.03 PM*

![Work session — capture 40 (6.03.16 PM)](Screenshot%202026-11-04%20at%206.03.16%E2%80%AFPM.png)
*Capture 40 · 6.03.16 PM*


## 11. Final observations

This practical covered the complete path from local CSV files to SQL analysis in Snowflake. The most useful points for me were:

1. **Check the CSV schema before creating a table.** The 16-versus-19-column issue showed why a matching destination schema matters.
2. **Keep raw and processed data separate.** CTAS let me build cleaned datasets without directly changing the original tables.
3. **Use the stage and `COPY INTO` deliberately.** The stage holds uploaded files; the target tables are populated only when the load command runs.
4. **Join on the correct IDs.** Orders contains the transactions, while Customers and Products supply additional details.
5. **Use Query Profile to verify optimizations.** Selecting fewer columns and filtering on suitable fields are good habits, but their real effect must be measured rather than assumed.

### Skills practiced

`CREATE DATABASE` · `CREATE SCHEMA` · `CREATE WAREHOUSE` · `CREATE TABLE` · `CREATE FILE FORMAT` · `CREATE STAGE` · `LIST` · `COPY INTO` · `VALIDATION_MODE` · `TRIM` · `UPPER` · `LOWER` · `COALESCE` · `NULLIF` · `CREATE TABLE AS SELECT` · `JOIN` · `GROUP BY` · `SUM` · `COUNT` · date filters · Query History · Query Profile

## 12. Screenshot and file organization

This README is meant to sit inside the same `SnowFlake` folder as the 40 screenshot files. Their links are **relative**, so they work locally and in a Git repository as long as the names remain unchanged.

```text
SnowFlake/
├── README.md
├── Screenshot 2026-11-04 at 5.38.33 PM.png
├── Screenshot 2026-11-04 at 5.39.09 PM.png
├── ...
└── Screenshot 2026-11-04 at 6.03.16 PM.png
```

**Note on the image captions:** The screenshots are embedded in chronological timestamp order. The written steps explain the SQL workflow; the screenshots serve as the visual record of the session. I have not inferred detailed on-screen results from filenames alone.
