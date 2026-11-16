import unittest
from unittest.mock import patch
from fastapi import HTTPException
from app import parse_csv

HEADER = b"DATABASE_NAME,SCHEMA_NAME,TABLE_NAME,COLUMN_NAME,DATA_TYPE\n"
H = b"database,schema,table,column,data_type\n"
ROW = b"NOVAMART,RAW,ORDERS,ID,NUMBER\n"


class ParserTests(unittest.TestCase):
    def assert_invalid(self, content, code=None):
        with self.assertRaises(HTTPException) as error:
            parse_csv(content)
        if code:
            self.assertEqual(error.exception.detail["errors"][0]["code"], code)
        self.assertNotIn("private-source", str(error.exception.detail))
        return error.exception

    def test_independent_operations_have_no_shared_tenant_state(self):
        content = HEADER + ROW + b"NOVAMART,RAW,ORDERS,TOTAL,NUMBER\n"
        first = parse_csv(content)
        first["datasets"][0]["columns"].clear()
        second = parse_csv(content)
        self.assertEqual(second["datasets"][0]["name"], "RAW.ORDERS")
        self.assertEqual(len(second["datasets"][0]["columns"]), 2)

    def test_quoted_identifiers_do_not_collide_in_legacy_literal_format(self):
        data = parse_csv(HEADER + b'DB,RAW,ORDERS,ID,NUMBER\nDB,RAW.ORDERS,X,ID,NUMBER\n')
        self.assertEqual(data["datasets"][1]["externalKey"], 'DB."RAW.ORDERS".X')

    def test_invalid_input_has_no_source_data_in_error(self):
        for content in [b"private-source-error", HEADER, HEADER + b"DB,RAW,ORDERS,ID,\xff\n"]:
            self.assert_invalid(content)

    def test_valid_fixture(self):
        from pathlib import Path
        result = parse_csv((Path(__file__).parents[2] / "demo/metadata-imports/snowflake_columns.csv").read_bytes())
        self.assertEqual(len(result["datasets"]), 2)
        self.assertEqual(sum(len(d["columns"]) for d in result["datasets"]), 5)

    def test_empty_file(self):
        self.assert_invalid(b"", "EMPTY_FILE")

    def test_missing_header(self):
        self.assert_invalid(b"database,schema,table,column\nDB,RAW,X,ID\n", "MISSING_HEADERS")

    def test_empty_required_field_has_row(self):
        e = self.assert_invalid(H + b"DB,RAW,X,ID,\n", "EMPTY_VALUE")
        self.assertEqual(e.detail["errors"][0]["row"], 2)
        self.assertEqual(e.detail["errors"][0]["field"], "data_type")

    def test_exact_duplicates_warn_and_deduplicate(self):
        result = parse_csv(H + ROW + ROW)
        self.assertEqual(len(result["datasets"][0]["columns"]), 1)
        self.assertIn("1 duplicate", result["warnings"][0])

    def test_conflicting_duplicates(self):
        self.assert_invalid(H + ROW + ROW.replace(b"NUMBER", b"VARCHAR"), "CONFLICT")

    def test_bom(self):
        self.assertEqual(parse_csv(b"\xef\xbb\xbf" + H + ROW), parse_csv(H + ROW))

    def test_csv_quoting_and_optional_description(self):
        result = parse_csv(H.rstrip() + b',description\nDB,RAW,X,ID,"NUMBER(10, 2)","A comma, and ""quote"""\n')
        c = result["datasets"][0]["columns"][0]
        self.assertEqual(c["dataType"], "NUMBER(10, 2)")
        self.assertEqual(c["description"], 'A comma, and "quote"')

    def test_multiple_datasets_and_schemas(self):
        result = parse_csv(H + ROW + b"NOVAMART,CURATED,FACT_ORDERS,TOTAL,NUMBER\nNOVAMART,RAW,CUSTOMERS,ID,NUMBER\n")
        self.assertEqual([d["externalKey"] for d in result["datasets"]], ["NOVAMART.RAW.ORDERS", "NOVAMART.CURATED.FACT_ORDERS", "NOVAMART.RAW.CUSTOMERS"])

    def test_unsupported_case_sensitive_and_invalid_identifiers(self):
        for row in [b'DB,RAW,"""Mixed""",ID,NUMBER\n', b"DB,RAW,A.B,ID,NUMBER\n", b"DB,RAW,1INVALID,ID,NUMBER\n"]:
            self.assert_invalid(H + row, "IDENTIFIER")

    def test_large_file(self):
        self.assertEqual(self.assert_invalid(b"x" * (5*1024*1024+1), "LIMIT").status_code, 413)

    def test_row_limit_counts_duplicates_too(self):
        self.assertEqual(len(parse_csv(H + ROW * 20000)["datasets"]), 1)
        self.assert_invalid(H + ROW * 20001, "LIMIT")

    def test_normalization_consistency(self):
        self.assertEqual(parse_csv(H + b" novamart , raw , orders , id , number \n"), parse_csv(H + ROW))

    def test_malformed_csv(self):
        self.assert_invalid(H + b'DB,RAW,X,ID,"NUMBER\n', "MALFORMED_CSV")

    def test_extra_or_duplicate_headers(self):
        self.assert_invalid(b"database,database,schema,table,column,data_type\n", "MISSING_HEADERS")
        self.assert_invalid(H.rstrip()+b",extra\n", "MISSING_HEADERS")

    def test_column_and_dataset_limits(self):
        with patch("importers.snowflake_csv_importer.MAX_COLUMNS", 1):
            self.assert_invalid(H + ROW + ROW.replace(b",ID,", b",OTHER,"), "LIMIT")
        with patch("importers.snowflake_csv_importer.MAX_DATASETS", 1):
            self.assert_invalid(H + ROW + ROW.replace(b",ORDERS,", b",OTHER,"), "LIMIT")

    def test_description_limits_and_conflicts(self):
        h = H.rstrip()+b",description\n"
        self.assert_invalid(h + ROW.rstrip()+b","+b"x"*4001+b"\n", "DESCRIPTION")
        self.assert_invalid(h + ROW.rstrip()+b",first\n" + ROW.rstrip()+b",second\n", "CONFLICT")


if __name__ == "__main__":
    unittest.main()
