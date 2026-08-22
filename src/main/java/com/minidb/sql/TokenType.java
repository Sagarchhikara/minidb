package com.minidb.sql;

public enum TokenType {
    // keywords
    SELECT, INSERT, INTO, VALUES, FROM, WHERE, LIMIT,
    // literals & names
    IDENTIFIER, NUMBER, STRING,
    // punctuation / operators
    STAR, COMMA, LPAREN, RPAREN, EQ, LT, GT, LTE, GTE, NEQ,
    EOF
}
