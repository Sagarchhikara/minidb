package com.minidb;

import com.minidb.index.BPlusTree;
import com.minidb.index.Rid;
import com.minidb.plan.IndexSeek;
import com.minidb.plan.Operator;
import com.minidb.plan.Planner;
import com.minidb.plan.SeqScan;
import com.minidb.record.Row;
import com.minidb.record.RowPage;
import com.minidb.record.RowSerializer;
import com.minidb.storage.BufferPool;
import com.minidb.storage.DiskManager;
import com.minidb.storage.FifoPolicy;
import com.minidb.storage.LruPolicy;
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
        System.out.println();
        stage7BufferPool();
        System.out.println();
        stage8QueryPlanner();
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

        table.flush();
        disk.close();

        // The real proof: none of this lives in memory any more.
        DiskManager reopened = new DiskManager();
        reopened.open(dbPath);
        Table reloaded = new Table(reopened);
        List<Row> afterReopen = reloaded.scan();
        int pagesAfter = reopened.getNumPages();
        reloaded.flush();
        reopened.close();

        // expectedAdults is asserted non-zero first: comparing two counts that could both
        // be 0 would pass without the predicate ever having matched anything.
        boolean okDisk = sameRows(expected, afterReopen)
                && pagesAfter == pages
                && expectedAdults > 0
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

        table.flush();
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

        // Duplicate primary key rejection test
        boolean duplicateRejected = false;
        try {
            table.insert(new Row(1, "Duplicate", 99));
        } catch (IllegalArgumentException e) {
            duplicateRejected = true;
        }

        // Non-existent ID lookup
        List<Row> missingIndex = executor.execute(Parser.parse("SELECT * FROM users WHERE id = 999"));
        List<Row> missingScan = table.scanWhere(r -> r.getId() == 999);
        boolean missingOk = missingIndex.isEmpty() && missingScan.isEmpty();

        // Close and reopen database: verifies derived index reconstructs cleanly from heap
        table.flush();
        disk.close();

        DiskManager reopenedDisk = new DiskManager();
        reopenedDisk.open(dbPath);
        Table reopenedTable = new Table(reopenedDisk);
        Executor reopenedExecutor = new Executor(reopenedTable);

        // Duplicate primary key rejection on reopened table
        boolean reopenDuplicateRejected = false;
        try {
            reopenedTable.insert(new Row(42, "Duplicate", 99));
        } catch (IllegalArgumentException e) {
            reopenDuplicateRejected = true;
        }

        boolean reopenLookupsOk = true;
        for (int id : List.of(1, 42, 100, 199, 200)) {
            List<Row> viaIndex = reopenedExecutor.execute(Parser.parse("SELECT * FROM users WHERE id = " + id));
            List<Row> viaScan = reopenedTable.scanWhere(r -> r.getId() == id);
            if (viaIndex.size() != 1 || viaScan.size() != 1 || !matches(viaScan.get(0), viaIndex.get(0))) {
                reopenLookupsOk = false;
                System.out.println("  reopen point lookup mismatch for id=" + id);
            }
        }

        reopenedTable.flush();
        reopenedDisk.close();

        boolean ok = pointLookupsOk && duplicateRejected && missingOk && reopenDuplicateRejected && reopenLookupsOk;
        System.out.println(ok
                ? "STAGE 6B PASSED: index-accelerated point lookups agree with scans, duplicates rejected, and survives reopen."
                : "STAGE 6B FAILED: index/scan discrepancy, duplicate not rejected, or reopen rebuild failure.");
    }

    // Stage 7: Buffer pool with LRU and FIFO eviction policies.
    private static void stage7BufferPool() throws IOException {
        boolean ok = true;
        ok &= stage7aHitMissAccounting();
        ok &= stage7bDurabilityThroughEviction();
        ok &= stage7cLruVsFifoEviction();
        ok &= stage7dPinnedProtection();
        ok &= stage7eTableThroughBufferPool();
        System.out.println(ok
                ? "STAGE 7 PASSED: Buffer pool, LRU/FIFO eviction, and pin/dirty protocol fully verified."
                : "STAGE 7 FAILED: see sub-step output above.");
    }

    // Step 7.2: Hit/miss accounting
    private static boolean stage7aHitMissAccounting() throws IOException {
        String dbPath = "stage7_hits.db";
        new File(dbPath).delete();

        DiskManager disk = new DiskManager();
        disk.open(dbPath);
        for (int i = 0; i < 5; i++) {
            disk.allocatePage();
        }

        BufferPool pool = new BufferPool(disk, 3);
        // Fetch 0 -> Miss (1 miss, 0 hits)
        Page p0 = pool.fetchPage(0);
        pool.unpin(0, false);

        // Fetch 0 again -> Hit (1 miss, 1 hit)
        p0 = pool.fetchPage(0);
        pool.unpin(0, false);

        // Fetch 1 -> Miss (2 misses, 1 hit)
        Page p1 = pool.fetchPage(1);
        pool.unpin(1, false);

        // Fetch 2 -> Miss (3 misses, 1 hit) — pool now full [0, 1, 2]
        Page p2 = pool.fetchPage(2);
        pool.unpin(2, false);

        // Fetch 3 -> Miss (4 misses, 1 hit) — evicts 0
        Page p3 = pool.fetchPage(3);
        pool.unpin(3, false);

        // Re-fetch 0 -> Miss (5 misses, 1 hit) — was evicted
        p0 = pool.fetchPage(0);
        pool.unpin(0, false);

        pool.assertNoPinnedFrames();
        disk.close();

        boolean ok = (pool.getHits() == 1) && (pool.getMisses() == 5);
        System.out.println(ok
                ? "STAGE 7a PASSED: hit/miss counters and eviction accounting work accurately (1 hit, 5 misses)."
                : "STAGE 7a FAILED: expected 1 hit, 5 misses but got " + pool.getHits() + " hits, " + pool.getMisses() + " misses.");
        return ok;
    }

    // Step 7.3: Durability through eviction (dirty pages must be flushed before eviction)
    private static boolean stage7bDurabilityThroughEviction() throws IOException {
        String dbPath = "stage7_durability.db";
        new File(dbPath).delete();

        DiskManager disk = new DiskManager();
        disk.open(dbPath);
        for (int i = 0; i < 6; i++) {
            disk.allocatePage();
        }

        // Capacity 4: write to page 0 (dirty), then fetch 4 other pages to force page 0's eviction
        BufferPool pool = new BufferPool(disk, 4);
        Page p0 = pool.fetchPage(0);
        p0.putInt(0, 987654);
        pool.unpin(0, true); // marked dirty!

        for (int i = 1; i <= 4; i++) {
            Page p = pool.fetchPage(i);
            pool.unpin(i, false);
        }

        boolean evictedFromPool = !pool.isCached(0);
        pool.assertNoPinnedFrames();

        // Close the pool without calling flushAll — disk file must ALREADY have page 0 flushed via eviction!
        disk.close();

        // Reopen disk directly to verify page 0 persisted on disk
        DiskManager reopened = new DiskManager();
        reopened.open(dbPath);
        Page readBack = reopened.readPage(0);
        int val = readBack.getInt(0);
        reopened.close();

        boolean ok = evictedFromPool && (val == 987654);
        System.out.println(ok
                ? "STAGE 7b PASSED: dirty page flushed to disk upon eviction and survived reopen (value=" + val + ")."
                : "STAGE 7b FAILED: dirty page data lost during eviction (read back " + val + ").");
        return ok;
    }

    // Step 7.4: LRU vs FIFO eviction behavior
    private static boolean stage7cLruVsFifoEviction() throws IOException {
        String dbPath = "stage7_lru_fifo.db";
        new File(dbPath).delete();

        DiskManager disk = new DiskManager();
        disk.open(dbPath);
        for (int i = 0; i < 6; i++) {
            disk.allocatePage();
        }

        // Give every page a distinguishable marker. Without this the test proves only
        // WHICH page the policy dropped, not that the pages it kept still hold the right
        // bytes — a policy that evicted correctly but corrupted survivors would pass.
        for (int i = 0; i < 6; i++) {
            Page marked = new Page(i);
            marked.putInt(0, 7000 + i);
            disk.writePage(marked);
        }

        // Access sequence: 1, 2, 3, 1, 4 with capacity 3
        // LRU: touch on 1 moves 1 to MRU, so accessing 4 evicts 2 (least recently used)
        BufferPool lruPool = new BufferPool(disk, 3, new LruPolicy());
        poolAccess(lruPool, 1);
        poolAccess(lruPool, 2);
        poolAccess(lruPool, 3);
        poolAccess(lruPool, 1); // touch 1
        poolAccess(lruPool, 4); // triggers eviction of 2

        boolean lruOk = !lruPool.isCached(2)
                && lruPool.isCached(1)
                && lruPool.isCached(3)
                && lruPool.isCached(4);

        // FIFO: ignores touch on 1, so accessing 4 evicts 1 (oldest insertion)
        BufferPool fifoPool = new BufferPool(disk, 3, new FifoPolicy());
        poolAccess(fifoPool, 1);
        poolAccess(fifoPool, 2);
        poolAccess(fifoPool, 3);
        poolAccess(fifoPool, 1); // touch ignored by FIFO
        poolAccess(fifoPool, 4); // triggers eviction of 1

        boolean fifoOk = !fifoPool.isCached(1)
                && fifoPool.isCached(2)
                && fifoPool.isCached(3)
                && fifoPool.isCached(4);

        // Contents, not just residency: every surviving frame must still carry its marker,
        // and the evicted page must re-read correctly from disk.
        boolean contentsOk = markerOk(lruPool, 1) && markerOk(lruPool, 3) && markerOk(lruPool, 4)
                && markerOk(lruPool, 2)   // evicted by LRU - must come back off disk intact
                && markerOk(fifoPool, 2) && markerOk(fifoPool, 3) && markerOk(fifoPool, 4)
                && markerOk(fifoPool, 1); // evicted by FIFO

        lruPool.assertNoPinnedFrames();
        fifoPool.assertNoPinnedFrames();
        disk.close();

        boolean ok = lruOk && fifoOk && contentsOk;
        System.out.println(ok
                ? "STAGE 7c PASSED: LRU evicted page 2 while FIFO evicted page 1 on pattern 1,2,3,1,4; all page contents intact."
                : "STAGE 7c FAILED: lruOk=" + lruOk + " fifoOk=" + fifoOk + " contentsOk=" + contentsOk);
        return ok;
    }

    /** Fetches a page through the pool and checks it still carries its 7000+n marker. */
    private static boolean markerOk(BufferPool pool, int pageNum) throws IOException {
        Page p = pool.fetchPage(pageNum);
        try {
            return p.getInt(0) == 7000 + pageNum;
        } finally {
            pool.unpin(pageNum, false);
        }
    }

    private static void poolAccess(BufferPool pool, int pageNum) throws IOException {
        Page p = pool.fetchPage(pageNum);
        pool.unpin(pageNum, false);
    }

    // Pinned frames protection during eviction
    private static boolean stage7dPinnedProtection() throws IOException {
        String dbPath = "stage7_pinned.db";
        new File(dbPath).delete();

        DiskManager disk = new DiskManager();
        disk.open(dbPath);
        for (int i = 0; i < 5; i++) {
            disk.allocatePage();
        }

        BufferPool pool = new BufferPool(disk, 2);
        Page p0 = pool.fetchPage(0); // pinCount = 1 (pinned)
        Page p1 = pool.fetchPage(1); // pinCount = 1
        pool.unpin(1, false);        // pinCount = 0 (unpinned)

        // Fetching page 2 must evict page 1 (since page 0 is pinned)
        Page p2 = pool.fetchPage(2);
        boolean p0Protected = pool.isCached(0) && !pool.isCached(1) && pool.isCached(2);

        // Both page 0 and page 2 are now pinned; fetching page 3 must throw
        boolean threwWhenAllPinned = false;
        try {
            pool.fetchPage(3);
        } catch (IllegalStateException e) {
            threwWhenAllPinned = true;
        }

        pool.unpin(0, false);
        pool.unpin(2, false);
        disk.close();

        boolean ok = p0Protected && threwWhenAllPinned;
        System.out.println(ok
                ? "STAGE 7d PASSED: pinned frames are protected from eviction; all-pinned throws as expected."
                : "STAGE 7d FAILED: pinned frame protection failed.");
        return ok;
    }

    // Step 7.1: Table routed through BufferPool with small cache (capacity 3)
    private static boolean stage7eTableThroughBufferPool() throws IOException {
        String dbPath = "stage7_table.db";
        new File(dbPath).delete();

        DiskManager disk = new DiskManager();
        disk.open(dbPath);
        BufferPool pool = new BufferPool(disk, 3); // small pool forces constant eviction
        Table table = new Table(pool);
        Executor executor = new Executor(table);

        List<Row> expected = new ArrayList<>();
        // 150 rows fit in a single page, so the pool would never actually evict; the row
        // count has to span well past capacity for "constrained pool" to mean anything.
        final int count = 2000;
        for (int id = 1; id <= count; id++) {
            String name = (id % 2 == 1) ? "Sagar" : "a-really-long-name-here";
            int age = 20 + (id % 50);
            Row row = new Row(id, name, age);
            expected.add(row);
            executor.execute(Parser.parse(String.format("INSERT INTO users VALUES (%d, '%s', %d)", id, name, age)));
        }

        List<Row> scanned = table.scan();
        boolean scanOk = sameRows(expected, scanned);

        // Point queries via index through buffer pool
        boolean indexLookupsOk = true;
        for (int id : List.of(1, 25, 75, 120, 150, 999, 2000)) {
            List<Row> rows = executor.execute(Parser.parse("SELECT * FROM users WHERE id = " + id));
            if (rows.size() != 1 || rows.get(0).getId() != id) {
                indexLookupsOk = false;
                break;
            }
        }

        pool.assertNoPinnedFrames();
        int pageCount = disk.getNumPages();
        boolean evictionHappened = pageCount > pool.getCapacity();
        table.flush();
        disk.close();

        // Reopen table with buffer pool and verify data survived
        DiskManager reopenedDisk = new DiskManager();
        reopenedDisk.open(dbPath);
        BufferPool reopenedPool = new BufferPool(reopenedDisk, 4);
        Table reopenedTable = new Table(reopenedPool);
        List<Row> afterReopen = reopenedTable.scan();
        reopenedTable.flush();
        reopenedDisk.close();

        reopenedPool.assertNoPinnedFrames();
        boolean reopenOk = sameRows(expected, afterReopen);

        boolean ok = scanOk && indexLookupsOk && reopenOk && evictionHappened;
        System.out.println(ok
                ? "STAGE 7e PASSED: Table survives a pool that evicted repeatedly ("
                        + count + " rows over " + pageCount + " pages, capacity 3)."
                : "STAGE 7e FAILED: scanOk=" + scanOk + " indexOk=" + indexLookupsOk
                        + " reopenOk=" + reopenOk + " evictionHappened=" + evictionHappened);
        return ok;
    }

    // ---------------------------------------------------------------------
    // Stage 8: query planner (AST -> logical shape -> physical iterator tree)
    // ---------------------------------------------------------------------

    private static void stage8QueryPlanner() throws IOException {
        boolean ok = true;
        ok &= stage8aRegression();
        ok &= stage8bDifferential();
        ok &= stage8cPlanShape();
        ok &= stage8dEarlyTermination();
        ok &= stage8ePredicateSemantics();
        System.out.println(ok
                ? "STAGE 8 PASSED: planner produces correct answers, forced plans agree, and LIMIT terminates early."
                : "STAGE 8 FAILED: see sub-step output above.");
    }

    /** Builds a table big enough to span many pages, so eviction and early exit are real. */
    private static Table buildPlannerFixture(String dbPath, int rows, int poolCapacity) throws IOException {
        new File(dbPath).delete();
        DiskManager disk = new DiskManager();
        disk.open(dbPath);
        Table table = new Table(new BufferPool(disk, poolCapacity));
        for (int id = 1; id <= rows; id++) {
            table.insert(new Row(id, "user-" + id + "-padding-to-widen-the-row", 20 + (id % 60)));
        }
        table.flush();
        return table;
    }

    // (a) Regression: prior stage queries, now routed through the planner.
    private static boolean stage8aRegression() throws IOException {
        Table table = buildPlannerFixture("stage8_regression.db", 800, 64);
        Executor executor = new Executor(table);

        List<Row> all = executor.execute(Parser.parse("SELECT * FROM users"));
        List<Row> byAge = executor.execute(Parser.parse("SELECT * FROM users WHERE age > 60"));
        List<Row> byId = executor.execute(Parser.parse("SELECT * FROM users WHERE id = 500"));
        List<Row> byName = executor.execute(Parser.parse("SELECT * FROM users WHERE name = 'nobody'"));
        List<Row> missing = executor.execute(Parser.parse("SELECT * FROM users WHERE id = 99999"));

        long expectedByAge = table.scan().stream().filter(r -> r.getAge() > 60).count();

        boolean ok = all.size() == 800
                && expectedByAge > 0          // precondition: the predicate must actually match
                && byAge.size() == expectedByAge
                && byId.size() == 1 && byId.get(0).getId() == 500
                && byName.isEmpty()
                && missing.isEmpty();

        table.getBufferPool().assertNoPinnedFrames();
        table.close();

        System.out.println(ok
                ? "STAGE 8a PASSED: all prior-stage queries return unchanged answers through the planner."
                : "STAGE 8a FAILED: all=" + all.size() + " byAge=" + byAge.size() + "/" + expectedByAge
                        + " byId=" + byId.size() + " byName=" + byName.size() + " missing=" + missing.size());
        return ok;
    }

    // (b) Differential: useIndexes=true and useIndexes=false must agree, compared as sets.
    private static boolean stage8bDifferential() throws IOException {
        Table table = buildPlannerFixture("stage8_differential.db", 600, 16);
        Executor executor = new Executor(table);

        boolean ok = true;
        StringBuilder detail = new StringBuilder();

        // 600 rows exist, so ids 1..600 must return exactly one row and 4242 exactly none.
        // Without asserting the expected size, two plans that both return empty would
        // "agree" and the differential test would pass having compared nothing.
        final int rowsInFixture = 600;
        int nonEmptyComparisons = 0;

        for (int id : List.of(1, 7, 250, 599, 600, 4242)) {
            SelectStatement stmt = (SelectStatement) Parser.parse("SELECT * FROM users WHERE id = " + id);

            List<Row> indexed = Executor.drain(executor.getPlanner().useIndexes(true).plan(stmt));
            List<Row> scanned = Executor.drain(executor.getPlanner().useIndexes(false).plan(stmt));

            // Oracle: the Stage 4 path, kept deliberately rather than deleted.
            final int target = id;
            List<Row> oracle = table.scanWhere(r -> r.getId() == target);

            int expectedSize = (id >= 1 && id <= rowsInFixture) ? 1 : 0;
            if (expectedSize > 0) {
                nonEmptyComparisons++;
            }

            // Compared as sets: IndexSeek yields key order, SeqScan yields heap order.
            boolean sizesOk = indexed.size() == expectedSize;
            if (!sizesOk || !sameRowSet(indexed, scanned) || !sameRowSet(indexed, oracle)) {
                ok = false;
                detail.append(" id=").append(id)
                        .append(" expected=").append(expectedSize)
                        .append(" indexed=").append(indexed.size())
                        .append(" scanned=").append(scanned.size())
                        .append(" oracle=").append(oracle.size());
            }
            table.getBufferPool().assertNoPinnedFrames();
        }

        if (nonEmptyComparisons < 5) {
            ok = false;
            detail.append(" only ").append(nonEmptyComparisons).append(" non-empty comparisons");
        }
        executor.getPlanner().useIndexes(true);
        table.close();

        System.out.println(ok
                ? "STAGE 8b PASSED: indexed and forced-scan plans return identical row sets, matching the scan oracle."
                : "STAGE 8b FAILED: plan divergence ->" + detail);
        return ok;
    }

    // (c) Plan shape: the rewrite rule fires exactly when it should.
    private static boolean stage8cPlanShape() throws IOException {
        Table table = buildPlannerFixture("stage8_shape.db", 200, 16);
        Planner planner = new Planner(table);

        Operator pointLookup = planner.useIndexes(true)
                .plan((SelectStatement) Parser.parse("SELECT * FROM users WHERE id = 5"));
        boolean seekOk = Planner.containsNode(pointLookup, IndexSeek.class)
                && !Planner.containsNode(pointLookup, SeqScan.class);

        Operator rangeQuery = planner.useIndexes(true)
                .plan((SelectStatement) Parser.parse("SELECT * FROM users WHERE age > 60"));
        boolean filterOk = Planner.containsNode(rangeQuery, com.minidb.plan.Filter.class)
                && Planner.containsNode(rangeQuery, SeqScan.class)
                && !Planner.containsNode(rangeQuery, IndexSeek.class);

        // The forced-bad plan must really be a scan, or Stage 10's comparison is vacuous.
        Operator forcedScan = planner.useIndexes(false)
                .plan((SelectStatement) Parser.parse("SELECT * FROM users WHERE id = 5"));
        boolean forcedOk = Planner.containsNode(forcedScan, SeqScan.class)
                && !Planner.containsNode(forcedScan, IndexSeek.class);

        boolean ok = seekOk && filterOk && forcedOk;
        System.out.println(ok
                ? "STAGE 8c PASSED: id = 5 plans to IndexSeek with no SeqScan; age > 60 plans to Filter over SeqScan."
                : "STAGE 8c FAILED: seekOk=" + seekOk + " filterOk=" + filterOk + " forcedOk=" + forcedOk);
        if (ok) {
            System.out.print(Planner.explain(rangeQuery));
        }
        table.close();
        return ok;
    }

    // (d) Early termination: LIMIT 3 must read far fewer pages than a full scan.
    private static boolean stage8dEarlyTermination() throws IOException {
        Table table = buildPlannerFixture("stage8_limit.db", 1500, 4);
        BufferPool pool = table.getBufferPool();
        Executor executor = new Executor(table);

        int totalPages = pool.getDisk().getNumPages();
        int limitedMisses;
        int fullMisses;
        boolean rowsOk;
        boolean earlyOk;
        boolean noLeak = true;

        try {
            int before = pool.getMisses();
            List<Row> limited = executor.execute(Parser.parse("SELECT * FROM users LIMIT 3"));
            limitedMisses = pool.getMisses() - before;
            // The tripwire: LIMIT abandons SeqScan mid-page, so this is exactly where a
            // missing unpin in close() shows up.
            pool.assertNoPinnedFrames();

            before = pool.getMisses();
            List<Row> full = executor.execute(Parser.parse("SELECT * FROM users"));
            fullMisses = pool.getMisses() - before;
            pool.assertNoPinnedFrames();

            rowsOk = limited.size() == 3 && full.size() == 1500;
            // Exact, not "fewer": 3 rows all live on page 0, so a correctly pipelined
            // scan reads exactly one page. "< fullMisses" would still pass if LIMIT
            // leaked into reading half the table.
            earlyOk = limitedMisses == 1 && fullMisses > 1;
        } catch (IllegalStateException e) {
            System.out.println("STAGE 8d FAILED: " + e.getMessage());
            table.close();
            return false;
        }

        // The emergent symptom of a pin leak is eviction starvation, but it only appears
        // once `capacity` DISTINCT pages are stuck. Repeated LIMIT queries all abandon
        // page 0, so they pile pins onto one frame and never starve the pool — which is
        // exactly why assertNoPinnedFrames above is the real detector, not this. Land
        // scans on different pages so the starvation path is genuinely exercised.
        boolean evictionOk;
        try {
            for (int k = 0; k < pool.getCapacity(); k++) {
                SeqScan scan = new SeqScan(pool);
                scan.open();
                for (int r = 0; r <= k * 95; r++) {
                    scan.next();
                }
                scan.close();
            }
            pool.assertNoPinnedFrames();
            List<Row> sweep = executor.execute(Parser.parse("SELECT * FROM users WHERE age > 70"));
            evictionOk = !sweep.isEmpty();
            pool.assertNoPinnedFrames();
        } catch (IllegalStateException e) {
            evictionOk = false;
            System.out.println("  staggered scans starved the pool: " + e.getMessage());
        }

        boolean ok = rowsOk && earlyOk && evictionOk;
        System.out.println(ok
                ? "STAGE 8d PASSED: LIMIT 3 touched " + limitedMisses + " page(s) vs " + fullMisses
                        + " for a full scan of " + totalPages + "; no frames left pinned."
                : "STAGE 8d FAILED: rowsOk=" + rowsOk + " earlyOk=" + earlyOk + " (limit="
                        + limitedMisses + " full=" + fullMisses + ") evictionOk=" + evictionOk);
        table.close();
        return ok;
    }

    /**
     * (e) Predicate semantics, asserted directly.
     *
     * Planner and oracle share Predicates, which is right for 8b (that test is about
     * access path, and both plans run the same Filter) but it means predicate meaning
     * is outside differential coverage: a wrong operator would agree with itself. These
     * assertions pin the semantics down directly instead.
     */
    private static boolean stage8ePredicateSemantics() throws IOException {
        String dbPath = "stage8_predicates.db";
        new File(dbPath).delete();
        DiskManager disk = new DiskManager();
        disk.open(dbPath);
        Table table = new Table(disk);

        // Ages chosen to sit exactly on the boundary: 59, 60, 61.
        table.insert(new Row(1, "Sagar", 59));
        table.insert(new Row(2, "Ada", 60));
        table.insert(new Row(3, "Grace", 61));
        table.flush();
        Executor executor = new Executor(table);

        // Strict > must exclude the boundary value itself; >= must include it.
        List<Row> gt = executor.execute(Parser.parse("SELECT * FROM users WHERE age > 60"));
        List<Row> gte = executor.execute(Parser.parse("SELECT * FROM users WHERE age >= 60"));
        List<Row> lt = executor.execute(Parser.parse("SELECT * FROM users WHERE age < 60"));
        List<Row> lte = executor.execute(Parser.parse("SELECT * FROM users WHERE age <= 60"));
        boolean boundaryOk = gt.size() == 1 && gt.get(0).getAge() == 61
                && gte.size() == 2
                && lt.size() == 1 && lt.get(0).getAge() == 59
                && lte.size() == 2;

        // != on both a numeric and a text column.
        List<Row> neqInt = executor.execute(Parser.parse("SELECT * FROM users WHERE age != 60"));
        List<Row> neqStr = executor.execute(Parser.parse("SELECT * FROM users WHERE name != 'Ada'"));
        List<Row> eqStr = executor.execute(Parser.parse("SELECT * FROM users WHERE name = 'Ada'"));
        boolean neqOk = neqInt.size() == 2 && neqStr.size() == 2
                && eqStr.size() == 1 && eqStr.get(0).getName().equals("Ada");

        // Ordering comparisons are not defined for a text column.
        boolean stringOrderRejected = rejects(executor, "SELECT * FROM users WHERE name > 'Ada'")
                && rejects(executor, "SELECT * FROM users WHERE name < 'Ada'")
                && rejects(executor, "SELECT * FROM users WHERE name >= 'Ada'");

        // Cross-type comparisons must throw rather than coerce.
        boolean typeMismatchRejected = rejects(executor, "SELECT * FROM users WHERE age > 'Sagar'")
                && rejects(executor, "SELECT * FROM users WHERE name = 42")
                && rejects(executor, "SELECT * FROM users WHERE id = 'Sagar'");

        // Unknown columns are rejected, not silently treated as no-match.
        boolean unknownColumnRejected = rejects(executor, "SELECT * FROM users WHERE nosuch = 1");

        table.getBufferPool().assertNoPinnedFrames();
        table.close();

        boolean ok = boundaryOk && neqOk && stringOrderRejected && typeMismatchRejected && unknownColumnRejected;
        System.out.println(ok
                ? "STAGE 8e PASSED: boundary/!=/type-mismatch predicate semantics pinned down directly."
                : "STAGE 8e FAILED: boundary=" + boundaryOk + " neq=" + neqOk
                        + " stringOrder=" + stringOrderRejected + " typeMismatch=" + typeMismatchRejected
                        + " unknownColumn=" + unknownColumnRejected);
        return ok;
    }

    /** True if executing the SQL throws IllegalArgumentException rather than returning rows. */
    private static boolean rejects(Executor executor, String sql) {
        try {
            executor.execute(Parser.parse(sql));
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Order-insensitive row comparison — IndexSeek and SeqScan return different orders. */
    private static boolean sameRowSet(List<Row> a, List<Row> b) {
        if (a.size() != b.size()) {
            return false;
        }
        List<String> left = new ArrayList<>();
        List<String> right = new ArrayList<>();
        for (Row r : a) {
            left.add(r.toString());
        }
        for (Row r : b) {
            right.add(r.toString());
        }
        Collections.sort(left);
        Collections.sort(right);
        return left.equals(right);
    }
}
