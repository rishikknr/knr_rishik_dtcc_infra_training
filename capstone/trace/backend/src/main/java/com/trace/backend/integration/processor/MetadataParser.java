package com.trace.backend.integration.processor;

import com.trace.backend.dto.response.MetadataPreview;

public interface MetadataParser {
  MetadataPreview parse(byte[] csv);
}
