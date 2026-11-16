"""Parse only. Never execute submitted SQL. Dependency candidates are not saved edges."""

import sqlglot
from sqlglot import exp
from sqlglot.optimizer.scope import traverse_scope

DIALECTS = {
    "snowflake",
    "postgres",
    "tsql",
    "bigquery",
    "mysql",
    "sqlite",
    "spark",
    "duckdb",
}


def validate_sql(source, dialect="snowflake"):
    if (
        dialect not in DIALECTS
        or not isinstance(source, str)
        or not source.strip()
        or len(source) > 100000
    ):
        return {
            "status": "INVALID",
            "warnings": ["Unsupported dialect or expression size"],
            "inputs": [],
            "outputs": [],
        }
    if any(x in source for x in ("{{", "{%", "${")):
        return {
            "status": "UNRESOLVED",
            "warnings": ["Macros require manual resolution"],
            "inputs": [],
            "outputs": [],
        }
    try:
        trees = sqlglot.parse(
            source, read=dialect, error_level=sqlglot.ErrorLevel.RAISE
        )
        if len(trees) > 20 or sum(1 for t in trees if t for _ in t.walk()) > 10000:
            raise ValueError()
        tables = set()
        outputs = set()
        columns = set()
        warnings = []
        for tree in trees:
            if tree is None:
                continue
            if any(isinstance(n, exp.Command) for n in tree.walk()):
                return {
                    "status": "UNSUPPORTED",
                    "warnings": [
                        "Dynamic SQL and procedural commands are not supported"
                    ],
                    "inputs": [],
                    "outputs": [],
                }
            if not isinstance(tree, (exp.Query, exp.Create, exp.Insert)):
                return {
                    "status": "UNSUPPORTED",
                    "warnings": [
                        "Only SELECT, CREATE AS and INSERT SELECT dependencies are supported"
                    ],
                    "inputs": [],
                    "outputs": [],
                }
            target = tree.this if isinstance(tree, (exp.Create, exp.Insert)) else None
            if isinstance(target, exp.Schema):
                target = target.this
            if isinstance(target, exp.Table):
                outputs.add(target.sql(dialect=dialect))
            for scope in traverse_scope(tree) or []:
                for _, (_, origin) in scope.selected_sources.items():
                    if isinstance(origin, exp.Table):
                        physical = origin.copy()
                        physical.set("alias", None)
                        tables.add(physical.sql(dialect=dialect))
                for col in scope.columns:
                    columns.add(col.sql(dialect=dialect))
        if not outputs:
            warnings.append(
                "No output target declared; select a registered target explicitly"
            )
        warnings.append(
            "Candidates only. Column ambiguity and registry identities require explicit confirmation; SQL was not executed."
        )
        return {
            "status": "PARSED",
            "inputs": sorted(tables),
            "outputs": sorted(outputs),
            "columns": sorted(columns)[:1000],
            "warnings": warnings,
        }
    except Exception:
        return {
            "status": "INVALID",
            "inputs": [],
            "outputs": [],
            "warnings": ["SQL syntax could not be parsed in the selected dialect"],
        }


def analyze_sql(source, dialect="snowflake", assets=None, target=None):
    """Resolve candidates only inside a caller-supplied registry snapshot. Never infer ingestion."""
    import hashlib
    from sqlglot.optimizer.normalize_identifiers import normalize_identifiers
    from sqlglot.lineage import lineage
    from sqlglot.schema import MappingSchema

    result = validate_sql(source, dialect)
    result["definitionHash"] = hashlib.sha256(
        (source if isinstance(source, str) else "").encode()
    ).hexdigest()
    result["parserVersion"] = sqlglot.__version__
    result["analysisVersion"] = 1
    result["references"] = []
    result["columnMappings"] = []
    result["rules"] = []
    if result["status"] != "PARSED":
        return result
    assets = assets or []
    if len(assets) > 5000:
        result["status"] = "UNRESOLVED"
        result["warnings"].append(
            "Registry scope exceeds 5,000 datasets; narrow the source scope"
        )
        return result

    def parts(key):
        table = normalize_identifiers(
            sqlglot.parse_one(key, into=exp.Table, read=dialect), dialect=dialect
        )
        return tuple(x.name for x in table.parts)

    identities = []
    for asset in assets:
        try:
            identities.append((parts(asset["external_key"]), asset))
        except Exception:
            continue
    context = ()
    if target:
        try:
            context = parts(target["external_key"])[:-1]
        except Exception:
            pass

    def qualify(p):
        return (
            p
            if len(p) == 3
            else ((*context[:1], *p) if len(p) == 2 and context else (*context, *p))
        )

    references = {}
    for key in result["inputs"]:
        p = parts(key)
        qualified = qualify(p)
        matches = [a for identity, a in identities if identity == qualified]
        # A fully qualified duplicate on separate connections is ambiguous, never first-match.
        ref = {
            "identifier": key,
            "status": "RESOLVED"
            if len(matches) == 1
            else "AMBIGUOUS"
            if matches
            else "UNRESOLVED",
            "matches": [{"id": a["id"], "name": a["external_key"]} for a in matches],
        }
        result["references"].append(ref)
        if len(matches) == 1:
            references[p] = matches[0]
    try:
        trees = sqlglot.parse(source, read=dialect)
        if len(trees) != 1:
            result["status"] = "UNSUPPORTED"
            result["warnings"].append(
                "Analyze one SELECT or view definition at a time; combined statements cannot be registered against one target"
            )
            return result
        tree = normalize_identifiers(trees[0], dialect=dialect)
        declared_target = (
            tree.this if isinstance(tree, (exp.Create, exp.Insert)) else None
        )
        if isinstance(declared_target, exp.Schema):
            declared_target = declared_target.this
        if (
            isinstance(declared_target, exp.Table)
            and target
            and qualify(parts(declared_target.sql(dialect=dialect)))
            != parts(target["external_key"])
        ):
            result["status"] = "TARGET_MISMATCH"
            result["warnings"].append(
                "Declared SQL output differs from the chosen target identity"
            )
            return result
        query = tree.expression if isinstance(tree, (exp.Create, exp.Insert)) else tree
        if not isinstance(query, exp.Query):
            return result
        result["rules"] = [
            {"kind": type(n).__name__, "expression": n.sql(dialect=dialect)}
            for n in query.walk()
            if isinstance(
                n, (exp.Where, exp.Having, exp.Join, exp.Group, exp.Case, exp.AggFunc)
            )
        ][:100]
        schema = MappingSchema(dialect=dialect)
        # Use each exact SQL spelling so CTE aliases and quoted identifiers remain parser-owned.
        for p, asset in references.items():
            key = next(
                r["identifier"]
                for r in result["references"]
                if parts(r["identifier"]) == p
            )
            schema.add_table(
                key,
                {
                    c["name"]: c.get("data_type") or "UNKNOWN"
                    for c in asset.get("columns", [])
                },
                dialect=dialect,
            )
        output_names = [selection.alias_or_name for selection in query.selects]
        for selection in query.selects:
            output = selection.alias_or_name
            if output_names.count(output) > 1:
                result["warnings"].append(
                    f"Output {output} is declared more than once; explicit column mapping required"
                )
                continue
            if not output or output == "*":
                result["warnings"].append(
                    "Wildcard output requires explicit column confirmation"
                )
                continue
            try:
                node = lineage(output, query, schema=schema, dialect=dialect)
                leaves = [n for n in node.walk() if not n.downstream]
                for leaf in leaves:
                    if not isinstance(leaf.expression, exp.Table):
                        continue
                    table = leaf.expression.copy()
                    table.set("alias", None)
                    asset = references.get(parts(table.sql(dialect=dialect)))
                    column_name = sqlglot.parse_one(
                        leaf.name, into=exp.Column, read=dialect
                    ).name
                    found = [
                        c
                        for c in (asset or {}).get("columns", [])
                        if c["name"] == column_name
                    ]
                    targets = [
                        c
                        for c in (target or {}).get("columns", [])
                        if c["name"] == output
                    ]
                    if len(found) == 1 and len(targets) == 1:
                        mapping = {
                            "sourceAssetId": asset["id"],
                            "sourceColumnId": found[0]["id"],
                            "targetColumnId": targets[0]["id"],
                            "sourceColumn": column_name,
                            "targetColumn": output,
                            "expression": selection.sql(dialect=dialect),
                            "status": "CANDIDATE",
                        }
                        if mapping not in result["columnMappings"]:
                            result["columnMappings"].append(mapping)
            except Exception:
                result["warnings"].append(
                    f"Column {output} could not be resolved uniquely; explicit mapping required"
                )
        result["columnMappings"] = result["columnMappings"][:2000]
    except Exception:
        result["warnings"].append(
            "Column analysis unavailable for this syntax; dataset candidates retained"
        )
    result["warnings"].append(
        "Column candidates describe value expressions. Filtering and join predicates are shown as SQL operations; complete row-control lineage is not inferred."
    )
    result["warnings"] = list(dict.fromkeys(result["warnings"]))
    return result
