package com.trace.backend.controller;

import com.trace.backend.tenancy.TenantTransactions;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceSummaryController {
  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;

  public WorkspaceSummaryController(TenantTransactions tenants, JdbcTemplate jdbc) {
    this.tenants = tenants;
    this.jdbc = jdbc;
  }

  @GetMapping("/summary")
  public Map<String, Object> summary() {
    return tenants.executeSnapshot(
        t -> {
          jdbc.execute("SET LOCAL statement_timeout='5s'");
          var counts = new LinkedHashMap<String, Object>();
          for (var entry :
              Map.of(
                      "datasets",
                      "SELECT count(*) FROM assets WHERE organization_id=? AND asset_type IN"
                          + " ('DATASET','TABLE','VIEW')",
                      "columns",
                      "SELECT count(*) FROM asset_columns WHERE organization_id=?",
                      "sqlDefinitions",
                      "SELECT count(*) FROM assets WHERE organization_id=? AND"
                          + " asset_type='SQL_TRANSFORMATION'",
                      "formulaVersions",
                      "SELECT count(*) FROM formula_versions WHERE organization_id=?",
                      "reportingAssets",
                      "SELECT count(*) FROM assets WHERE organization_id=? AND asset_type IN"
                          + " ('REPORT','MEASURE','VISUAL','SEMANTIC_MODEL')")
                  .entrySet())
            counts.put(
                entry.getKey(),
                jdbc.queryForObject(entry.getValue(), Long.class, t.organizationId()));
          var attention = new ArrayList<Map<String, Object>>();
          add(
              attention,
              jdbc.queryForObject(
                  "SELECT count(*) FROM assets WHERE organization_id=? AND asset_type IN"
                      + " ('DATASET','TABLE','VIEW') AND nullif(trim(owner),'') IS NULL",
                  Long.class,
                  t.organizationId()),
              "Datasets without technical owners",
              "Ownership is not recorded. Assign someone who can answer questions and review"
                  + " changes.",
              "catalog?owner=missing");
          add(
              attention,
              jdbc.queryForObject(
                  "SELECT count(*) FROM metrics WHERE organization_id=? AND"
                      + " nullif(trim(business_owner),'') IS NULL",
                  Long.class,
                  t.organizationId()),
              "Metrics without business owners",
              "The calculation exists, but responsibility for its business meaning is missing.",
              "metrics");
          add(
              attention,
              jdbc.queryForObject(
                  "SELECT count(*) FROM governance_reviews WHERE organization_id=? AND status IN"
                      + " ('SUBMITTED','UNDER_REVIEW','CHANGES_REQUESTED')",
                  Long.class,
                  t.organizationId()),
              "Versions awaiting review",
              "A governance decision or clarification is still pending.",
              "governance");
          add(
              attention,
              jdbc.queryForObject(
                  "SELECT count(*) FROM assets WHERE organization_id=? AND asset_type IN"
                      + " ('DATASET','TABLE','VIEW') AND last_seen_at < now()-interval '7 days'",
                  Long.class,
                  t.organizationId()),
              "Datasets not observed for 7 days",
              "This is a metadata observation threshold, not a business-data refresh check.",
              "catalog?freshness=older-7d");
          add(
              attention,
              jdbc.queryForObject(
                  "SELECT count(*) FROM source_connections WHERE organization_id=? AND"
                      + " status='FAILED'",
                  Long.class,
                  t.organizationId()),
              "Sources with failed connection status",
              "Inspect connection diagnostics before running discovery again.",
              "connections");
          return Map.of("counts", counts, "attention", attention);
        });
  }

  private void add(
      List<Map<String, Object>> list, long count, String title, String reason, String route) {
    if (count > 0)
      list.add(Map.of("count", count, "title", title, "reason", reason, "route", route));
  }
}
