package com.minidb.index;

/**
 * A Record Identifier (RID) representing the physical location of a row in the heap:
 * the page number and byte offset within that page.
 */
public record Rid(int pageNum, int offset) {
}
