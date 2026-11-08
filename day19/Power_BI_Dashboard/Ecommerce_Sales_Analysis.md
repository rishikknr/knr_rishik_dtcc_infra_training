# E-Commerce Sales Analysis Dashboard

## Objective
Analyze customer, product, and order data from Snowflake in Power BI to monitor sales, profitability, order trends, and customer distribution.

## Data Source
Connected Power BI Desktop to Snowflake and loaded three tables:
- **CUSTOMERS:** Customer ID, name, age, segment, location, and signup date.
- **ORDERS:** Order ID, customer/product IDs, order date, quantity, sales, discounts, costs, profit, status, payment method, and channel.
- **PRODUCTS:** Product ID, name, category, brand, pricing, and related details.

## 1. Data Preparation
Opened **Home → Transform data** in Power BI. Checked that IDs and descriptive fields were text, dates were Date type, quantities/ages were whole numbers, and financial fields were numeric.

### Calculated Columns
Created the following custom columns in Power Query (**Add Column → Custom Column**):

| Table | Column | Power Query formula | Purpose |
|---|---|---|---|
| ORDERS | `ORDER_YEAR` | `Date.Year([ORDER_DATE])` | Extract order year |
| ORDERS | `ORDER_MONTH` | `Date.StartOfMonth([ORDER_DATE])` | Group sales by month |
| ORDERS | `SALES_CATEGORY` | `if [NET_SALES] = null then "Unknown" else if [NET_SALES] < 1000 then "Low" else if [NET_SALES] < 5000 then "Medium" else "High"` | Classify sales value |
| ORDERS | `PROFIT_STATUS` | `if [PROFIT] = null then "Unknown" else if [PROFIT] > 0 then "Profit" else if [PROFIT] < 0 then "Loss" else "Break-even"` | Identify profit/loss |
| CUSTOMERS | `AGE_GROUP` | `if [AGE] = null then "Unknown" else if [AGE] < 25 then "Below 25" else if [AGE] < 40 then "25-39" else if [AGE] < 60 then "40-59" else "60+"` | Segment customers by age |

After verifying the new columns, used **Close & Apply**.

> If columns are created using **Modeling → New column** instead, DAX syntax is required (for example, `ORDER_YEAR = YEAR(ORDERS[ORDER_DATE])`). Power Query expressions and DAX formulas are not interchangeable.

## 2. Data Modeling
In **Model view → Manage relationships**, configured two active one-to-many relationships with single-direction filtering:

| One side | Many side | Key |
|---|---|---|
| CUSTOMERS | ORDERS | `CUSTOMER_ID` |
| PRODUCTS | ORDERS | `PRODUCT_ID` |

These relationships support analysis of orders by customer location and product category. Checked that the lookup-table IDs were unique and the join columns used compatible data types.

## 3. DAX Measures
Created measures using **Right-click ORDERS → New measure**:

```dax
Total Customers = DISTINCTCOUNT(CUSTOMERS[CUSTOMER_ID])
Total Orders = DISTINCTCOUNT(ORDERS[ORDER_ID])
Total Sales = SUM(ORDERS[NET_SALES])
Gross Sales = SUM(ORDERS[GROSS_AMOUNT])
Total Profit = SUM(ORDERS[PROFIT])
Total Discount = SUM(ORDERS[DISCOUNT_AMOUNT])
Profit Margin = DIVIDE([Total Profit], [Total Sales], 0)
Average Order Value = DIVIDE([Total Sales], [Total Orders], 0)
Total Quantity Sold = SUM(ORDERS[QUANTITY])
Total Products = DISTINCTCOUNT(PRODUCTS[PRODUCT_ID])
Customers With Orders = DISTINCTCOUNT(ORDERS[CUSTOMER_ID])
Discount Percentage = DIVIDE([Total Discount], [Gross Sales], 0)
```

*Each line above was entered as a separate measure.* Formatted `Profit Margin` and `Discount Percentage` as percentages and monetary measures as currency.

## 4. KPI Cards
Added six **Card** visuals to the report:

1. Total Customers
2. Total Orders
3. Total Sales
4. Total Profit
5. Profit Margin
6. Average Order Value

Aligned the cards at the top of the dashboard and applied consistent labels and formatting.

## 5. Charts

| Chart | Visual type | Fields |
|---|---|---|
| Net Sales by Product Category | Clustered column | X: `PRODUCTS[CATEGORY]`; Y: `[Total Sales]` |
| Orders by Region and Status | Stacked bar | Y: `CUSTOMERS[REGION]`; X: `[Total Orders]`; Legend: `ORDERS[ORDER_STATUS]` |
| Monthly Sales Trend | Line | X: `ORDERS[ORDER_MONTH]`; Y: `[Total Sales]` |
| Sales Distribution by Channel | Donut | Legend: `ORDERS[SALES_CHANNEL]`; Values: `[Total Sales]` |
| Top 10 Products by Sales | Clustered bar | Y: `PRODUCTS[PRODUCT_NAME]`; X: `[Total Sales]` |

For the product chart, applied a **Top N = 10** visual filter based on `[Total Sales]` and sorted descending. Used the date column rather than the date hierarchy for the monthly trend.

## 6. Slicers and Interactions
Added two slicers:
- **Order Date:** `ORDERS[ORDER_DATE]`, using the **Between** option.
- **Region:** `CUSTOMERS[REGION]`, using a dropdown.

Tested filtering by date and region and checked visual interactions using **Format → Edit interactions**. The registered customer total does not necessarily change with an order-date slicer; `Customers With Orders` is more suitable for date-filtered purchasing activity.

## 7. Formatting and Performance
Added a dashboard title, arranged KPI cards and charts, and adjusted chart titles, labels, and page layout. Opened **Optimize → Performance Analyzer**, selected **Start recording → Refresh visuals**, and inspected visual timings.

