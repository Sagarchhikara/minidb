package com.minidb;

import com.minidb.index.BPlusTree;
import com.minidb.index.Rid;
import com.minidb.record.Row;
import com.minidb.record.RowPage;
import com.minidb.record.RowSerializer;
import com.minidb.storage.DiskManager;
import com.minidb.sql.Condition;
import com.minidb.sql.Executor;
import com.minidb.sql.InsertStatement;
import com.minidb.sql.Lexer;
import com.minidb.sql.ParseException;
import com.minidb.sql.Parser;
import com.minidb.sql.SelectStatement;
import com.minidb.sql.Statement;
import com.minidb.sql.Token;
import com.minidb.sql.TokenType;
import com.minidb.table.Table;
import com.minidb.storage.Page;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class Main {
    public static void main(String[] args) throws IOException {
        stage1PageRoundTrip();
        System.out.println();
        stage2SerializerRoundTrip();
        System.out.println();
        stage2RowThroughDisk();
        System.out.println();
        stage3MultipleRowsPerPage();
        System.out.println();
        stage4MultiPageTable();
        System.out.println();
        stage5SqlParser();
        System.out.println();
        stage6BTreeIsolation();
        System.out.println();
        stage6BTreeTableIntegration();
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
            if (rowPage.insertRow(RowSerializer.serialize(row)) < 0) {
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

    // Stage 4: rows spilling across many pages, and surviving a reopen.
    private static void stage4MultiPageTable() throws IOException {
        String dbPath = "stage4.db";
        new File(dbPath).delete();

        // CREATE TABLE (no parser yet - Stage 5 feeds strings into these same calls)
        DiskManager disk = new DiskManager();
        disk.open(dbPath);
        Table table = new Table(disk);

        // Stage 3 fit 157 alternating rows in one page; 600 forces several spills.
        final int rowCount = 600;
        List<Row> expected = new ArrayList<>();
        for (int id = 1; id <= rowCount; id++) {
            String name = (id % 2 == 1) ? "Sagar" : "a-really-long-name-here";
            Row row = new Row(id, name, 20 + (id % 50));
            expected.add(row);
            table.insert(row); // INSERT
        }

        List<Row> scanned = table.scan(); // SELECT *
        int pages = disk.getNumPages();
        System.out.println("Inserted " + rowCount + " rows across " + pages + " pages.");
        System.out.println("First row: " + scanned.get(0) + ", last row: " + scanned.get(scanned.size() - 1));

        boolean okMemory = sameRows(expected, scanned) && pages > 1;
        System.out.println(okMemory
                ? "STAGE 4a PASSED: " + scanned.size() + " rows scanned back in order, spilled over " + pages + " pages."
                : "STAGE 4a FAILED: scanned " + scanned.size() + " rows from " + pages + " page(s).");

        // A taste of the Stage 5 executor: WHERE age > 60, evaluated in Java.
        List<Row> adults = table.scanWhere(r -> r.getAge() > 60);
        long expectedAdults = expected.stream().filter(r -> r.getAge() > 60).count();
        System.out.println("scanWhere(age > 60) returned " + adults.size() + " rows (expected " + expectedAdults + ").");

        disk.close();

        // The real proof: none of this lives in memory any more.
        DiskManager reopened = new DiskManager();
        reopened.open(dbPath);
        Table reloaded = new Table(reopened);
        List<Row> afterReopen = reloaded.scan();
        int pagesAfter = reopened.getNumPages();
        reopened.close();

        boolean okDisk = sameRows(expected, afterReopen)
                && pagesAfter == pages
                && adults.size() == expectedAdults;

        System.out.println(okDisk
                ? "STAGE 4b PASSED: all " + afterReopen.size() + " rows still there after close/reopen."
                : "STAGE 4b FAILED: got " + afterReopen.size() + " rows from " + pagesAfter + " page(s) after reopen.");
    }


    // Stage 5: string -> tokens -> AST -> Table, via the Executor.
    private static void stage5SqlParser() throws IOException {
        boolean ok = true;
        ok &= stage5aLexer();
        ok &= stage5bParser();
        ok &= stage5cErrorCases();
        ok &= stage5dEndToEnd();
        System.out.println(ok
                ? "STAGE 5 PASSED: SQL text now drives insert/scan end to end."
                : "STAGE 5 FAILED: see sub-step output above.");
    }

    private static boolean stage5aLexer() {
        List<TokenType> insertTypes = List.of(
                TokenType.INSERT, TokenType.INTO, TokenType.IDENTIFIER, TokenType.VALUES,
                TokenType.LPAREN, TokenType.NUMBER, TokenType.COMMA, TokenType.STRING,
                TokenType.COMMA, TokenType.NUMBER, TokenType.RPAREN, TokenType.EOF);
        List<Token> insertTokens = Lexer.tokenize("INSERT INTO users VALUES (1, 'Sagar', 21)");
        boolean insertOk = typesMatch(insertTokens, insertTypes)
                && insertTokens.get(2).text().equals("users")
                && insertTokens.get(7).text().equals("Sagar"); // quotes stripped

        List<TokenType> selectTypes = List.of(
                TokenType.SELECT, TokenType.IDENTIFIER, TokenType.FROM, TokenType.IDENTIFIER,
                TokenType.WHERE, TokenType.IDENTIFIER, TokenType.GT, TokenType.NUMBER, TokenType.EOF);
        List<Token> selectTokens = Lexer.tokenize("SELECT name FROM users WHERE age > 18");
        boolean selectOk = typesMatch(selectTokens, selectTypes);

        // Keywords are case-insensitive; string contents are not.
        List<Token> lowerKeywords = Lexer.tokenize("select * from users");
        boolean caseOk = lowerKeywords.get(0).type() == TokenType.SELECT
                && lowerKeywords.get(1).type() == TokenType.STAR;

        boolean ok = insertOk && selectOk && caseOk;
        System.out.println(ok
                ? "STAGE 5a PASSED: lexer produced the exact expected token streams."
                : "STAGE 5a FAILED: token stream mismatch.");
        return ok;
    }

    private static boolean typesMatch(List<Token> tokens, List<TokenType> expected) {
        if (tokens.size() != expected.size()) {
            System.out.println("  token count mismatch: got " + tokens.size() + ", expected " + expected.size());
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (tokens.get(i).type() != expected.get(i)) {
                System.out.println("  token " + i + " mismatch: got " + tokens.get(i).type()
                        + ", expected " + expected.get(i));
                return false;
            }
        }
        return true;
    }

    private static boolean stage5bParser() {
        Statement insert = Parser.parse("INSERT INTO users VALUES (1, 'Sagar', 21)");
        boolean insertOk = insert instanceof InsertStatement ins
                && ins.table().equals("users")
                && ins.values().equals(List.of(1, "Sagar", 21));

        Statement select = Parser.parse("SELECT name FROM users WHERE age > 18");
        boolean selectOk = select instanceof SelectStatement sel
                && sel.table().equals("users")
                && sel.columns().equals(List.of("name"))
                && sel.where() != null
                && sel.where().equals(new Condition("age", TokenType.GT, 18));

        Statement selectStar = Parser.parse("SELECT * FROM users");
        boolean starOk = selectStar instanceof SelectStatement sel2
                && sel2.columns().equals(List.of("*"))
                && sel2.where() == null;

        boolean ok = insertOk && selectOk && starOk;
        System.out.println(ok
                ? "STAGE 5b PASSED: parsed AST fields match for INSERT and SELECT...WHERE."
                : "STAGE 5b FAILED: AST field mismatch.");
        return ok;
    }

    private static boolean stage5cErrorCases() {
        boolean missingColumns = throwsParseException(() -> Parser.parse("SELECT FROM users"));
        boolean missingValues = throwsParseException(() -> Parser.parse("INSERT INTO users (1,2,3)"));
        boolean unterminatedString = throwsParseException(
                () -> Parser.parse("SELECT * FROM users WHERE name = 'Sagar"));
        boolean trailingGarbage = throwsParseException(
                () -> Parser.parse("SELECT * FROM users hello"));

        boolean ok = missingColumns && missingValues && unterminatedString && trailingGarbage;
        System.out.println(ok
                ? "STAGE 5c PASSED: malformed SQL is rejected with ParseException, not silently mangled."
                : "STAGE 5c FAILED: at least one malformed statement was accepted.");
        return ok;
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }

    private static boolean throwsParseException(ThrowingRunnable action) {
        try {
            action.run();
            System.out.println("  expected a ParseException but none was thrown");
            return false;
        } catch (ParseException e) {
            System.out.println("  caught as expected: " + e.getMessage());
            return true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean stage5dEndToEnd() throws IOException {
        String dbPath = "stage5.db";
        new File(dbPath).delete();

        DiskManager disk = new DiskManager();
        disk.open(dbPath);
        Table table = new Table(disk);
        Executor executor = new Executor(table);

        List<Row> expected = new ArrayList<>();
        for (int id = 1; id <= 80; id++) {
            String name = (id % 2 == 1) ? "Sagar" : "a-really-long-name-here";
            int age = 20 + (id % 50);
            expected.add(new Row(id, name, age));
            String sql = String.format("INSERT INTO users VALUES (%d, '%s', %d)", id, name, age);
            executor.execute(Parser.parse(sql));
        }

        List<Row> viaSqlSelectStar = executor.execute(Parser.parse("SELECT * FROM users"));
        boolean scanOk = sameRows(expected, viaSqlSelectStar);

        // Same predicate, two ways: through the parser/executor, and directly via
        // the Stage 4 Table.scanWhere it compiles down to. They must agree.
        List<Row> viaSql = executor.execute(Parser.parse("SELECT * FROM users WHERE age > 60"));
        List<Row> viaJava = table.scanWhere(r -> r.getAge() > 60);
        boolean whereOk = sameRows(viaJava, viaSql) && !viaSql.isEmpty();

        // Case sensitivity: keywords fold, string contents don't.
        List<Row> lowerKeywordScan = executor.execute(Parser.parse("select * from users where name = 'Sagar'"));
        List<Row> wrongCaseScan = executor.execute(Parser.parse("SELECT * FROM users WHERE name = 'sagar'"));
        boolean caseOk = !lowerKeywordScan.isEmpty() && wrongCaseScan.isEmpty();

        System.out.println("Inserted 80 rows via SQL, scanned back " + viaSqlSelectStar.size()
                + ", WHERE age > 60 matched " + viaSql.size() + " both ways.");
        System.out.println(Executor.project(viaSqlSelectStar.get(0), List.of("name")));

        disk.close();

        boolean ok = scanOk && whereOk && caseOk;
        System.out.println(ok
                ? "STAGE 5d PASSED: parsed SQL and direct Table calls agree on the same data."
                : "STAGE 5d FAILED: SQL-driven results diverged from direct Table calls.");
        return ok;
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

    // Stage 6A: in-memory B+Tree in isolation (hand-built search, copy-up/push-up splits,
    // recursive insert, invariant validation after every insert, randomized stress test).
    private static void stage6BTreeIsolation() {
        boolean ok = true;
        ok &= stage6aHandBuiltTreeSearch();
        ok &= stage6aRandomizedStressTest();
        System.out.println(ok
                ? "STAGE 6A PASSED: B+Tree search, splits, and invariants fully verified."
                : "STAGE 6A FAILED: see sub-step output above.");
    }

    private static boolean stage6aHandBuiltTreeSearch() {
        // Step 1-2: Hand-built 2-level tree
        // Internal: keys = [20], children = [leftLeaf, rightLeaf]
        // Left leaf: keys = [5, 10, 15], rids = [(0,5), (0,10), (0,15)]
        // Right leaf: keys = [20, 25, 30], rids = [(0,20), (0,25), (0,30)]
        BPlusTree tree = new BPlusTree(3);
        BPlusTree.Internal root = new BPlusTree.Internal();
        root.keys.add(20);

        BPlusTree.Leaf leftLeaf = new BPlusTree.Leaf();
        leftLeaf.keys.addAll(List.of(5, 10, 15));
        leftLeaf.rids.addAll(List.of(new Rid(0, 5), new Rid(0, 10), new Rid(0, 15)));

        BPlusTree.Leaf rightLeaf = new BPlusTree.Leaf();
        rightLeaf.keys.addAll(List.of(20, 25, 30));
        rightLeaf.rids.addAll(List.of(new Rid(0, 20), new Rid(0, 25), new Rid(0, 30)));

        leftLeaf.next = rightLeaf;
        root.children.add(leftLeaf);
        root.children.add(rightLeaf);
        tree.setRoot(root);

        boolean searchOk = true;
        searchOk &= new Rid(0, 5).equals(tree.search(5));
        searchOk &= new Rid(0, 10).equals(tree.search(10));
        searchOk &= new Rid(0, 15).equals(tree.search(15));
        searchOk &= new Rid(0, 20).equals(tree.search(20));
        searchOk &= new Rid(0, 25).equals(tree.search(25));
        searchOk &= new Rid(0, 30).equals(tree.search(30));
        searchOk &= tree.search(0) == null;
        searchOk &= tree.search(12) == null;
        searchOk &= tree.search(100) == null;

        int h = tree.validate();
        boolean validOk = (h == 2);

        boolean ok = searchOk && validOk;
        System.out.println(ok
                ? "STAGE 6a.1 PASSED: search on hand-built 2-level tree works accurately."
                : "STAGE 6a.1 FAILED: hand-built tree search mismatch.");
        return ok;
    }

    private static boolean stage6aRandomizedStressTest() {
        final int n = 500;
        final int maxKeys = 3; // small order forces splits at every level
        BPlusTree tree = new BPlusTree(maxKeys);

        List<Integer> keys = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            keys.add(i);
        }
        Collections.shuffle(keys, new Random(42));

        // Insert and validate after EVERY insert
        for (int k : keys) {
            tree.insert(k, new Rid(k / 10, k % 10));
            tree.validate();
        }

        // Verify search for all inserted keys
        boolean allFound = true;
        for (int k = 1; k <= n; k++) {
            Rid rid = tree.search(k);
            if (rid == null || !rid.equals(new Rid(k / 10, k % 10))) {
                allFound = false;
                break;
            }
        }

        // Verify missing keys
        boolean missingOk = (tree.search(0) == null) && (tree.search(n + 1) == null) && (tree.search(-5) == null);

        // Verify leaf-chain traversal equals sorted 1..n
        List<Integer> chainKeys = tree.getAllKeysFromLeafChain();
        List<Integer> sortedExpected = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            sortedExpected.add(i);
        }
        boolean chainOk = chainKeys.equals(sortedExpected);

        int height = tree.validate();
        boolean heightOk = height > 1 && height < 15; // balanced tree

        System.out.println("Inserted " + n + " shuffled keys into tree (order " + maxKeys + "). Tree height: " + height);
        boolean ok = allFound && missingOk && chainOk && heightOk;
        System.out.println(ok
                ? "STAGE 6a.2 PASSED: 500-key stress test with invariant validation after every insert."
                : "STAGE 6a.2 FAILED: stress test invariant violation or key mismatch.");
        return ok;
    }

    // Stage 6B: wiring B+Tree into Table, RowPage, and Executor.
    // - RowPage returns offset on insert
    // - Table automatically updates index on insert
    // - Table rebuilds index on open from heap
    // - Executor uses index for WHERE id = X
    // - Differential test (index path vs scan path) & close/reopen test
    private static void stage6BTreeTableIntegration() throws IOException {
        String dbPath = "stage6.db";
        new File(dbPath).delete();

        DiskManager disk = new DiskManager();
        disk.open(dbPath);
        Table table = new Table(disk);
        Executor executor = new Executor(table);

        List<Row> expected = new ArrayList<>();
        final int rowCount = 200;
        for (int id = 1; id <= rowCount; id++) {
            String name = (id % 2 == 1) ? "Sagar" : "a-really-long-name-here";
            int age = 20 + (id % 50);
            Row row = new Row(id, name, age);
            expected.add(row);
            executor.execute(Parser.parse(String.format("INSERT INTO users VALUES (%d, '%s', %d)", id, name, age)));
        }

        // Differential test: point lookups via SQL (index path) vs scanWhere (scan path)
        boolean pointLookupsOk = true;
        for (int id : List.of(1, 2, 50, 100, 150, 200)) {
            List<Row> viaIndex = executor.execute(Parser.parse("SELECT * FROM users WHERE id = " + id));
            List<Row> viaScan = table.scanWhere(r -> r.getId() == id);
            if (viaIndex.size() != 1 || viaScan.size() != 1 || !matches(viaScan.get(0), viaIndex.get(0))) {
                pointLookupsOk = false;
                System.out.println("  point lookup mismatch for id=" + id);
            }
        }

        // Non-existent ID lookup
        List<Row> missingIndex = executor.execute(Parser.parse("SELECT * FROM users WHERE id = 999"));
        List<Row> missingScan = table.scanWhere(r -> r.getId() == 999);
        boolean missingOk = missingIndex.isEmpty() && missingScan.isEmpty();

        // Close and reopen database: verifies derived index reconstructs cleanly from heap
        disk.close();

        DiskManager reopenedDisk = new DiskManager();
        reopenedDisk.open(dbPath);
        Table reopenedTable = new Table(reopenedDisk);
        Executor reopenedExecutor = new Executor(reopenedTable);

        boolean reopenLookupsOk = true;
        for (int id : List.of(1, 42, 100, 199, 200)) {
            List<Row> viaIndex = reopenedExecutor.execute(Parser.parse("SELECT * FROM users WHERE id = " + id));
            List<Row> viaScan = reopenedTable.scanWhere(r -> r.getId() == id);
            if (viaIndex.size() != 1 || viaScan.size() != 1 || !matches(viaScan.get(0), viaIndex.get(0))) {
                reopenLookupsOk = false;
                System.out.println("  reopen point lookup mismatch for id=" + id);
            }
        }

        reopenedDisk.close();

        boolean ok = pointLookupsOk && missingOk && reopenLookupsOk;
        System.out.println(ok
                ? "STAGE 6B PASSED: index-accelerated point lookups agree with scans and survive reopen."
                : "STAGE 6B FAILED: index/scan discrepancy or reopen rebuild failure.");
    }
}
