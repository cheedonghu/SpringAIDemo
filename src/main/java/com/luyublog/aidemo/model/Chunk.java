package com.luyublog.aidemo.model;

import java.util.Map;

public record Chunk(String text, Map<String, Object> metadata) {
}
