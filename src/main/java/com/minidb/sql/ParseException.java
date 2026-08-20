package com.minidb.sql;

/** Covers both lex and parse failures - both are "the SQL text is malformed". */
public class ParseException extends RuntimeException {
    private final int pos;

    public ParseException(String message, int pos) {
        super(message + " at position " + pos);
        this.pos = pos;
    }

    public int pos() {
        return pos;
    }
}
