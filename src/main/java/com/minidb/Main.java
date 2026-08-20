package com.minidb;

import com.minidb.record.Row;
import com.minidb.record.RowPage;
import com.minidb.record.RowSerializer;
import com.minidb.storage.DiskManager;
import com.minidb.storage.Page;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) throws IOException {
        stage1PageRoundTrip();
        System.out.println();
        stage2SerializerRoundTrip();
        System.out.println();
        stage2RowThroughDisk();
        System.out.println();
        stage3MultipleRowsPerPage();
    }

    // Stage 1: raw page data survives a close/reopen cycle.
    private static void stage1PageRoundTrip() throws IOException {
        String dbPath = "test.db";

        // Start clean each run so the test is repeatable
        new File(dbPath).delete();

        DiskManager diskManager = new DiskManager();
        diskManager.open(dbPath);

        int pageNum = diskManager.allocatePage();
        Page page = new Page(pageNum);
        page.putInt(0, 42);
        page.putBytes(4, "hello minidb".getBytes());
        diskManager.writePage(page);
        diskManager.close();

        System.out.println("Wrote page " + pageNum + " and closed the file.");

        // Reopen to prove the data actually persisted to disk
        DiskManager reopened = new DiskManager();
        reopened.open(dbPath);
        Page reread = reopened.readPage(pageNum);

        int value = reread.getInt(0);
        String text = new String(reread.getBytes(4, "hello minidb".getBytes().length));

        System.out.println("Read back int: " + value);
        System.out.println("Read back text: " + text);

        if (value == 42 && text.equals("hello minidb")) {
            System.out.println("STAGE 1 PASSED: page data survived a close/reopen cycle.");
        } else {
            System.out.println("STAGE 1 FAILED: data mismatch.");
        }

        reopened.close();
    }

    // Stage 2a: serialize -> deserialize entirely in memory.
    private static void stage2SerializerRoundTrip() {
        Row original = new Row(1, "Sagar", 21);
        byte[] bytes = RowSerializer.serialize(original);
        Row restored = RowSerializer.deserialize(bytes, 0);

        System.out.println("Original:   " + original);
        System.out.println("Serialized: " + bytes.length + " bytes (sizeOf said " + RowSerializer.sizeOf(original) + ")");
        System.out.println("Restored:   " + restored);

        boolean ok = matches(original, restored)
                && bytes.length == RowSerializer.sizeOf(original)
                // recordSize walks the stored bytes; it must agree with sizeOf, or
                // getAllRows() will mis-walk every record after the first.
                && RowSerializer.recordSize(bytes, 0) == bytes.length;

        // Multi-byte UTF-8: nameLength is a byte count, so this name is longer on
        // disk than it is in characters.
        Row unicode = new Row(7, "Sagar ç张🚀", 30);
        byte[] unicodeBytes = RowSerializer.serialize(unicode);
        Row unicodeRestored = RowSerializer.deserialize(unicodeBytes, 0);

        System.out.println("Unicode name: " + unicode.getName().length() + " chars, "
                + (unicodeBytes.length - 12) + " bytes on disk");
        System.out.println("Unicode restored: " + unicodeRestored);

        ok &= matches(unicode, unicodeRestored)
                && unicodeBytes.length == RowSerializer.sizeOf(unicode);

        // deserialize() takes an offset, so it must work from somewhere other than 0.
        byte[] padded = new byte[16 + bytes.length];
        System.arraycopy(bytes, 0, padded, 16, bytes.length);
        Row atOffset = RowSerializer.deserialize(padded, 16);
        System.out.println("Restored at offset 16: " + atOffset);
        ok &= matches(original, atOffset);

        System.out.println(ok
                ? "STAGE 2a PASSED: serialize/deserialize round trip is lossless."
                : "STAGE 2a FAILED: field mismatch.");
    }

    // Stage 2b: the same row, but routed through Page + DiskManager.
    private static void stage2RowThroughDisk() throws IOException {
        String dbPath = "rows.db";
        new File(dbPath).delete();

        Row original = new Row(1, "Sagar", 21);
        byte[] serialized = RowSerializer.serialize(original);
        int size = serialized.length;

        DiskManager diskManager = new DiskManager();
        diskManager.open(dbPath);

        int pageNum = diskManager.allocatePage();
        Page page = new Page(pageNum);
        page.putBytes(0, serialized); // one row at offset 0 is enough for now
        diskManager.writePage(page);
        diskManager.close();

        System.out.println("Wrote " + original + " as " + size + " bytes to page " + pageNum + ", closed the file.");

        DiskManager reopened = new DiskManager();
        reopened.open(dbPath);
        Page reread = reopened.readPage(pageNum);
        Row restored = RowSerializer.deserialize(reread.getBytes(0, size), 0);
        reopened.close();

        System.out.println("Read back:  " + restored);

        if (matches(original, restored)) {
            System.out.println("STAGE 2b PASSED: the row survived a real trip through disk storage.");
        } else {
            System.out.println("STAGE 2b FAILED: row mismatch after disk round trip.");
        }
    }

    // Stage 3: many variable-width rows in one page, then the same page through disk.
    private static void stage3MultipleRowsPerPage() throws IOException {
        String dbPath = "stage3.db";
        new File(dbPath).delete();

        DiskManager diskManager = new DiskManager();
        diskManager.open(dbPath);
        int pageNum = diskManager.allocatePage();

        Page page = new Page(pageNum);
        RowPage rowPage = RowPage.init(page);

        // Alternating name lengths on purpose: uniform rows would hide a fixed-stride
        // recordSize bug, because every wrong stride would still land on a boundary.
        List<Row> expected = new ArrayList<>();
        int id = 1;
        while (true) {
            String name = (id % 2 == 1) ? "Sagar" : "a-really-long-name-here";
            Row row = new Row(id, name, 20 + (id % 50));
            if (!rowPage.insertRow(RowSerializer.serialize(row))) {
                break; // page is full
            }
            expected.add(row);
            id++;
        }

        System.out.println("Filled the page with " + expected.size() + " rows, "
                + rowPage.getFreeSpace() + " bytes left over (smallest row is "
                + RowSerializer.sizeOf(new Row(0, "Sagar", 0)) + " bytes).");

        List<Row> inMemory = rowPage.getAllRows();
        boolean okMemory = sameRows(expected, inMemory) && rowPage.getRowCount() == expected.size();
        System.out.println(okMemory
                ? "STAGE 3a PASSED: " + inMemory.size() + " rows read back in order, in memory."
                : "STAGE 3a FAILED: read back " + inMemory.size() + " rows, expected " + expected.size() + ".");

        // Step 6: the header itself has to survive the disk trip, not just the records.
        diskManager.writePage(page);
        diskManager.close();

        DiskManager reopened = new DiskManager();
        reopened.open(dbPath);
        RowPage reloaded = RowPage.load(reopened.readPage(pageNum));
        List<Row> fromDisk = reloaded.getAllRows();
        reopened.close();

        boolean okDisk = sameRows(expected, fromDisk)
                && reloaded.getRowCount() == expected.size()
                && reloaded.getFreeSpace() == rowPage.getFreeSpace();

        System.out.println(okDisk
                ? "STAGE 3b PASSED: all " + fromDisk.size() + " rows and the page header survived disk."
                : "STAGE 3b FAILED: got " + fromDisk.size() + " rows back from disk.");
    }

    private static boolean sameRows(List<Row> expected, List<Row> actual) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!matches(expected.get(i), actual.get(i))) {
                System.out.println("  mismatch at row " + i + ": expected "
                        + expected.get(i) + " but got " + actual.get(i));
                return false;
            }
        }
        return true;
    }

    private static boolean matches(Row expected, Row actual) {
        return actual != null
                && actual.getId() == expected.getId()
                && actual.getName().equals(expected.getName())
                && actual.getAge() == expected.getAge();
    }
}
