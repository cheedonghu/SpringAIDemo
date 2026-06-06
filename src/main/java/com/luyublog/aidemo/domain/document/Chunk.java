package com.luyublog.aidemo.domain.document;

import java.util.Map;

public record Chunk(String text, Map<String, Object> metadata) {
}
