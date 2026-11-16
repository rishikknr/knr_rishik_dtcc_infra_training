package com.trace.backend.service;

import com.trace.backend.dto.response.*;
import com.trace.backend.mapper.RegistryMapper;
import com.trace.backend.repository.*;
import com.trace.backend.tenancy.TenantTransactions;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AssetService {
  private final TenantTransactions tenants;
  private final AssetRepository assets;
  private final AssetColumnRepository columns;

  public AssetService(
      TenantTransactions tenants, AssetRepository assets, AssetColumnRepository columns) {
    this.tenants = tenants;
    this.assets = assets;
    this.columns = columns;
  }

  public List<AssetResponse> list(String q, int limit, int offset) {
    if (q.length() > 255 || limit < 1 || limit > 100 || offset < 0)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid catalog pagination or search");
    return tenants.execute(
        t -> assets.list(t, q, limit, offset).stream().map(RegistryMapper::asset).toList());
  }

  public AssetResponse get(UUID id) {
    return tenants.execute(t -> RegistryMapper.asset(assets.get(t, id)));
  }

  public List<ColumnResponse> columns(UUID id) {
    return tenants.execute(
        t -> {
          assets.get(t, id);
          return columns.list(t, id).stream().map(RegistryMapper::column).toList();
        });
  }
}
