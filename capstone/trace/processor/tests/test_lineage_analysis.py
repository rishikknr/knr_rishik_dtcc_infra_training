import unittest
from parsers.sql import analyze_sql


class LineageAnalysisTests(unittest.TestCase):
    def setUp(self):
        self.assets = [
            {
                "id": "orders",
                "external_key": "DB.RAW.ORDERS",
                "columns": [
                    {"id": "oid", "name": "ORDER_ID", "data_type": "NUMBER"},
                    {"id": "net", "name": "NET_AMOUNT", "data_type": "NUMBER"},
                ],
            }
        ]
        self.target = {
            "external_key": "DB.ANALYTICS.REVENUE",
            "columns": [
                {"id": "revenue", "name": "REVENUE"},
                {"id": "targetid", "name": "ORDER_ID"},
            ],
        }

    def test_cte_aggregation_and_expression_column_mapping(self):
        r = analyze_sql(
            "WITH orders AS (SELECT ORDER_ID, NET_AMOUNT FROM DB.RAW.ORDERS) SELECT ORDER_ID, SUM(NET_AMOUNT) AS REVENUE FROM orders GROUP BY ORDER_ID",
            assets=self.assets,
            target=self.target,
        )
        self.assertEqual(["DB.RAW.ORDERS"], r["inputs"])
        self.assertEqual("RESOLVED", r["references"][0]["status"])
        self.assertTrue(
            any(
                m["sourceColumnId"] == "net" and m["targetColumnId"] == "revenue"
                for m in r["columnMappings"]
            )
        )
        self.assertIn("Sum", [rule["kind"] for rule in r["rules"]])

    def test_duplicate_registration_is_ambiguous(self):
        r = analyze_sql(
            "SELECT NET_AMOUNT AS REVENUE FROM DB.RAW.ORDERS",
            assets=self.assets + [{**self.assets[0], "id": "other"}],
            target=self.target,
        )
        self.assertEqual("AMBIGUOUS", r["references"][0]["status"])
        self.assertEqual([], r["columnMappings"])

    def test_context_schema_resolution_is_not_suffix_matching(self):
        r = analyze_sql(
            "SELECT NET_AMOUNT AS REVENUE FROM RAW.ORDERS",
            assets=self.assets,
            target=self.target,
        )
        self.assertEqual("RESOLVED", r["references"][0]["status"])
        r = analyze_sql(
            "SELECT NET_AMOUNT AS REVENUE FROM ORDERS",
            assets=self.assets,
            target=self.target,
        )
        self.assertEqual("UNRESOLVED", r["references"][0]["status"])

    def test_quoted_identity_is_case_sensitive(self):
        assets = [{**self.assets[0], "external_key": 'DB.RAW."Orders"'}]
        self.assertEqual(
            "UNRESOLVED",
            analyze_sql(
                "SELECT * FROM DB.RAW.ORDERS", assets=assets, target=self.target
            )["references"][0]["status"],
        )
        self.assertEqual(
            "RESOLVED",
            analyze_sql(
                'SELECT * FROM DB.RAW."Orders"', assets=assets, target=self.target
            )["references"][0]["status"],
        )

    def test_macro_and_unsupported_never_generate_edges(self):
        for sql in ['SELECT * FROM {{ref("orders")}}', "CALL run_dynamic_sql()"]:
            r = analyze_sql(sql, assets=self.assets, target=self.target)
            self.assertEqual([], r["references"])
            self.assertEqual([], r["columnMappings"])

    def test_ambiguous_join_columns_not_guessed(self):
        assets = self.assets + [
            {**self.assets[0], "id": "other", "external_key": "DB.RAW.OTHER"}
        ]
        r = analyze_sql(
            "SELECT NET_AMOUNT AS REVENUE FROM DB.RAW.ORDERS o JOIN DB.RAW.OTHER x ON o.ORDER_ID=x.ORDER_ID",
            assets=assets,
            target=self.target,
        )
        self.assertEqual([], r["columnMappings"])

    def test_hash_stable_and_definition_specific(self):
        a = analyze_sql("SELECT 1")
        b = analyze_sql("SELECT 1")
        c = analyze_sql("SELECT 2")
        self.assertEqual(a["definitionHash"], b["definitionHash"])
        self.assertNotEqual(a["definitionHash"], c["definitionHash"])

    def test_multiple_statements_cannot_be_registered_as_one_transformation(self):
        result = analyze_sql(
            "SELECT NET_AMOUNT AS REVENUE FROM DB.RAW.ORDERS; SELECT 1",
            assets=self.assets,
            target=self.target,
        )
        self.assertEqual("UNSUPPORTED", result["status"])
        self.assertEqual([], result["columnMappings"])

    def test_duplicate_output_alias_does_not_guess_column_mapping(self):
        result = analyze_sql(
            "SELECT NET_AMOUNT AS REVENUE, ORDER_ID AS REVENUE FROM DB.RAW.ORDERS",
            assets=self.assets,
            target=self.target,
        )
        self.assertEqual([], result["columnMappings"])
        self.assertTrue(any("more than once" in x for x in result["warnings"]))

    def test_local_view_target_resolves_against_its_registered_schema(self):
        result = analyze_sql(
            "CREATE VIEW REVENUE AS SELECT NET_AMOUNT AS REVENUE FROM DB.RAW.ORDERS",
            assets=self.assets,
            target=self.target,
        )
        self.assertEqual("PARSED", result["status"])

    def test_non_text_expression_is_invalid_without_crashing(self):
        self.assertEqual("INVALID", analyze_sql(None)["status"])
