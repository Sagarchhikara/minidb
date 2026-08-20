package com.minidb.sql;

/** A single comparison: column op literal, e.g. age > 18. No AND/OR, no nesting. */
public record Condition(String column, TokenType op, Object literal) {
}
