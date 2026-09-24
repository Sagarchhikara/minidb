<div align="center">

# MiniDB

**A disk-backed relational database engine, written from scratch in Java.**

No libraries. No build tool. No framework. Just pages, a buffer pool, a B+Tree,
a SQL parser, and a Volcano-model query planner — plus a benchmark harness that
proves the thing actually works the way the textbook says it should.

<br>

![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)
![Dependencies](https://img.shields.io/badge/dependencies-zero-2ea44f?style=for-the-badge)
![Build tool](https://img.shields.io/badge/build_tool-javac-blue?style=for-the-badge)
![Tests](https://img.shields.io/badge/checks-28%20passing-2ea44f?style=for-the-badge)
![Lines](https://img.shields.io/badge/Java-~4.6k%20LOC-lightgrey?style=for-the-badge)

</div>

---

## The short version

`SELECT * FROM users WHERE id = 500` goes in. It is tokenized, parsed into an AST,
compiled into a tree of pull-based iterators, and answered by descending a B+Tree to a
`Rid(page, offset)`, pinning exactly **one** 4 KB page in the buffer pool, decoding one
record, and unpinning. The same query with the index switched off reads **8,334 pages**
at one million rows.

That gap is the whole point — and it is not asserted, it is **measured**:

| | Index seek | Full scan | |
|---|---:|---:|---|
| **Pages read @ 1M rows** | `1` | `8,334` | flat vs. linear |
| **Median latency @ 1M rows** | `0.64 µs` | `26,945 µs` | ~42,000× |
| **Growth 1K → 1M** | `O(1)` | `O(n)` | as predicted |

Every number in this README comes from a harness in this repository, is reproducible with
one command, and is published next to the caveats that weaken it.

<br>

<div align="center">

### 📑 Contents

</div>

| | |
|---|---|
| [⚡ Quick start](#-quick-start) | Compile and run in two commands |
| [🗣️ What it can do](#️-what-it-can-do) | The SQL surface, with examples |
| [🏗️ Architecture](#️-architecture) | Five layers, bottom to top |
| [🧱 How it was built](#-how-it-was-built) | Stages 1 → 8, each one a working engine |
| [📊 Benchmarks](#-benchmarks) | E1, the SQLite calibration, E5 |
| [🧪 How it is tested](#-how-it-is-tested) | Differential testing, fault injection, the vacuity sweep |
| [✂️ Deliberate scope](#️-deliberate-scope) | What is missing, and why it was declined |
| [⚠️ Honest caveats](#️-honest-caveats) | Where the numbers are flattering |
| [📁 Repo layout](#-repo-layout) | Where everything lives |

---

## ⚡ Quick start

There is no build tool, so there is nothing to install. **Java 17 or newer** is the only
requirement.

```bash
# 1. Compile everything (~35 source files, about two seconds)
javac -d out/classes $(find src -name "*.java")

# 2. Run the full test suite — 28 checks across stages 1 through 8
java -cp out/classes com.minidb.Main
```

<details>
<summary><b>Expected output (click to expand)</b></summary>

```
STAGE 1 PASSED: page data survived a close/reopen cycle.
STAGE 2a PASSED: serialize/deserialize round trip is lossless.
STAGE 2b PASSED: the row survived a real trip through disk storage.
STAGE 3a PASSED: 157 rows read back in order, in memory.
STAGE 3b PASSED: all 157 rows and the page header survived disk.
STAGE 4a PASSED: 600 rows scanned back in order, spilled over 4 pages.
STAGE 4b PASSED: all 600 rows still there after close/reopen.
STAGE 5a PASSED: lexer produced the exact expected token streams.
STAGE 5b PASSED: parsed AST fields match for INSERT and SELECT...WHERE.
STAGE 5c PASSED: malformed SQL is rejected with ParseException, not silently mangled.
STAGE 5d PASSED: parsed SQL and direct Table calls agree on the same data.
STAGE 6A PASSED: B+Tree search, splits, and invariants fully verified.
STAGE 6B PASSED: index-accelerated point lookups agree with scans, duplicates rejected, and survives reopen.
STAGE 7a PASSED: hit/miss counters and eviction accounting work accurately (1 hit, 5 misses).
STAGE 7b PASSED: dirty page flushed to disk upon eviction and survived reopen (value=987654).
STAGE 7c PASSED: LRU evicted page 2 while FIFO evicted page 1 on pattern 1,2,3,1,4; all page contents intact.
STAGE 7d PASSED: pinned frames are protected from eviction; all-pinned throws as expected.
STAGE 7e PASSED: Table survives a pool that evicted repeatedly (2000 rows over 13 pages, capacity 3).
STAGE 8a PASSED: all prior-stage queries return unchanged answers through the planner.
STAGE 8b PASSED: indexed and forced-scan plans return identical row sets, matching the scan oracle.
STAGE 8c PASSED: id = 5 plans to IndexSeek with no SeqScan; age > 60 plans to Filter over SeqScan.
STAGE 8d PASSED: LIMIT 3 touched 1 page(s) vs 16 for a full scan of 17; no frames left pinned.
STAGE 8e PASSED: boundary/!=/type-mismatch predicate semantics pinned down directly.
```

</details>

### Running the benchmarks

The benchmarks generate their own data into `bench-data/` on first run and cache it
afterwards. The 1M-row table takes a minute or two to build once.

```bash
# Generate/verify the benchmark tables and print their shape
java -cp out/classes com.minidb.bench.DataGen

# E1: index vs. scan, 1K → 1M rows          ->  e1_results.csv
java -cp out/classes com.minidb.bench.E1IndexVsScan

# E5: index rebuild cost + fanout sweep     ->  e5_results.csv, e5_fanout.csv
java -cp out/classes com.minidb.bench.E5RebuildCost

# Redraw the chart from the CSV (needs matplotlib)
python3 bench/plot_e1.py
```

### Running the SQLite calibration

A standalone C file, linked directly against the system SQLite:

```bash
gcc -O2 bench/sqlite_calibration.c -lsqlite3 -o /tmp/sqlite_cal && /tmp/sqlite_cal
# -> sqlite_results.csv
```

If your distribution ships SQLite without development headers, link the shared object
directly — the handful of entry points used are declared in the source:

```bash
gcc -O2 bench/sqlite_calibration.c /usr/lib64/libsqlite3.so.0 -o /tmp/sqlite_cal
```

---

## 🗣️ What it can do

MiniDB speaks a deliberately small dialect of SQL over a single hardcoded table,
`users(id INT PRIMARY KEY, name TEXT, age INT)`.

```sql
-- Insert
INSERT INTO users VALUES (1, 'Sagar', 21);

-- Project everything, or named columns
SELECT * FROM users;
SELECT name, age FROM users;

-- One comparison in the WHERE clause
SELECT * FROM users WHERE id = 500;        -- ← rewritten to an index seek
SELECT * FROM users WHERE age > 60;        -- ← filter over a sequential scan
SELECT * FROM users WHERE name != 'Ada';

-- Early termination
SELECT * FROM users WHERE age > 60 LIMIT 3;
```

**Supported operators:** `=` `!=` `<` `>` `<=` `>=` on integer columns; `=` and `!=` only
on text. Anything else is a hard error rather than a quiet coercion — `name > 'Ada'`
throws, and `age = 'hello'` throws, because ordering text and comparing across types are
both places where a database can silently return a wrong answer instead of an error.

### The planner is inspectable

Every plan can be printed, and every plan can be **forced**, which is what makes the
benchmarks possible:

```java
Planner planner = executor.getPlanner();
Operator plan = planner.plan((SelectStatement) Parser.parse("SELECT * FROM users WHERE age > 60"));
System.out.println(Planner.explain(plan));
```

```
-> Filter(age > 60)  (cost=3.0)
  -> SeqScan(users)  (cost=3.0)
```

```java
planner.useIndexes(false);   // force the bad plan on purpose
```

That switch is not a debugging convenience — it is the experimental control. An
indexed-vs-scan comparison is only meaningful if *both* plans can be built for the
*same* query against the *same* bytes.

### Errors are errors

```
caught as expected: expected IDENTIFIER but got 'FROM' at position 7
caught as expected: expected VALUES but got '(' at position 18
caught as expected: unterminated string literal at position 33
caught as expected: expected EOF but got 'hello' at position 20
```

Malformed SQL raises a `ParseException` carrying the character position. Trailing tokens
after a complete statement are rejected rather than ignored.

---

## 🏗️ Architecture

```
  SQL text  ──►  Lexer ──► Parser ──► AST                    sql/
                                       │
                                       ▼
                                    Planner                  plan/    rewrite: id = k ──► IndexSeek
                                       │                              otherwise ──► Filter over SeqScan
                                       ▼
              Volcano iterator tree: SeqScan │ IndexSeek │ Filter │ Limit
                     open() / next() / close(), one row at a time, pipelined
                                       │
                    ┌──────────────────┴──────────────────┐
                    ▼                                     ▼
              B+Tree index                            Row heap                index/ table/
              int key ──► Rid(page, offset)           variable-width records  record/
                    └──────────────────┬──────────────────┘
                                       ▼
                            BufferPool  (pin / unpin / dirty)                 storage/
                            fixed frames, LRU or FIFO eviction
                                       │
                                       ▼
                            DiskManager ──► 4 KB pages on disk
```

Five layers. Each one was built, tested, and made to survive a close/reopen cycle before
the next one was started.

<br>

### 1️⃣ `storage/` — pages and the disk manager

| Class | Job |
|---|---|
| `Page` | A 4 KB byte array with a page number, typed big-endian accessors, bounds checks, and a dirty flag that every write sets automatically |
| `DiskManager` | Treats a file as an array of 4 KB blocks. `readPage(n)` seeks to `n × 4096`; `allocatePage()` grows the file |
| `BufferPool` | Caches a fixed number of pages in frames and enforces the protocol |
| `Frame` | One cached page + its pin count + its dirty bit |
| `EvictionPolicy` → `LruPolicy`, `FifoPolicy` | Eviction as a strategy object, so the two can be compared under the same access pattern |

**The buffer pool protocol** is the invariant the whole engine rests on:

- every `fetchPage` increments a pin count and **must** be paired with exactly one `unpin`
- a frame with `pinCount > 0` can never be evicted — it is in use by a live operator
- a dirty frame is flushed to disk *before* it is evicted, and again on `flushAll`/`close`
- if every frame is pinned, the pool throws rather than silently overwriting live data

Hits, misses, and evictions are all counted, which is what makes "pages read" a
measurable quantity rather than an estimate.

<br>

### 2️⃣ `record/` — rows on a page

`Row` is the tuple `(id, name, age)`. `RowSerializer` encodes it as:

```
[nameLength:4][id:4][nameBytes:nameLength][age:4]
```

Length-prefixed UTF-8, so the byte length and the character count never get confused —
`Sagar ç张🚀` is 10 characters and 15 bytes on disk, and the round trip is lossless.

`RowPage` lays a page format over the raw bytes:

```
[numRows:4][freeOffset:4][record][record][record]...  ──►  free space
```

Records are **variable width and packed back to back**, so finding record *i* means
walking from the header and asking the serializer how long each record is. (This is a
packed page, not a true slotted page with a slot directory — a slot array would buy
O(1) access and in-place deletes, neither of which this engine needs yet.)

One detail worth calling out: an all-zero header is **not** treated as corruption.
`allocatePage()` grows the file with zeros immediately, so a page that was allocated but
never flushed legitimately reads back that way. Any *other* out-of-range header is real
damage and is raised rather than written through.

At the benchmark row shape, that packs **120 rows into each 4 KB page** — which is
exactly why 1,000,000 rows occupy 8,334 pages.

<br>

### 3️⃣ `index/` — the B+Tree

A classical B+Tree mapping `int id → Rid(pageNum, offset)`, with six invariants that are
validated after *every* insert in the stress test:

1. Leaves hold all `(key, rid)` pairs and are linked left → right
2. Internal nodes hold routing keys and child pointers only
3. An internal node with *k* keys has exactly *k + 1* children
4. All leaves sit at the exact same depth
5. **Leaf split = copy-up** — the right half's smallest key is *copied* up; leaves keep everything
6. **Internal split = push-up** — the middle key *moves* up and is excluded from both halves

The default fanout is **128**. That choice is deliberate and documented in the source:
Stage 6 developed the tree at fanout 3 to force splits at every level during testing, but
fanout 3 at 1M keys gives a height near 13 with a tiny Java object per node — 13 pointer
chases with no locality, which would badly understate what an index is worth. Real
B+Trees run fanouts in the hundreds. Tests that specifically want frequent splits still
pass 3 explicitly; the randomized stress test sweeps **3, 4, 8, 32, 128 and 256** and
checks the resulting heights (7, 6, 4, 3, 2, 2).

<br>

### 4️⃣ `table/` — the heap plus the index

`Table` stacks a row heap on a `BufferPool` and maintains the B+Tree on `id`. It enforces
primary-key uniqueness before touching the heap, appends rows onto the last page and
spills onto a new one when full, and wraps every page access in `try/finally` so a thrown
exception can never leak a pin.

`Table.scanWhere` — the Stage 4 pre-planner code path — is deliberately **kept alive
rather than deleted**, because it shares no code with either physical plan and therefore
makes a credible third opinion in the differential test. (See [testing](#-how-it-is-tested).)

<br>

### 5️⃣ `sql/` + `plan/` — parse, plan, execute

`Lexer` → `Parser` → AST → `Planner` → iterator tree → `Executor` drains it.

The planner is **rule-based with exactly one rule**:

> `Filter(id = <int literal>)` over `SeqScan`  ⟶  `IndexSeek`

The precondition is narrow on purpose: the column must be `id` (the only indexed one),
the operator must be `=` (a range would need leaf-chain traversal that `IndexSeek` does
not implement), and the literal must be an integer. The `estimatedCost()` numbers that
`EXPLAIN` prints exist for display only — there are no table statistics yet, so nothing
chooses between plans on cost.

Four physical operators implement the **Volcano / iterator model**:

| Operator | Behavior |
|---|---|
| `SeqScan` | Walks pages in order, holding exactly one pin at a time |
| `IndexSeek` | One tree descent → one `Rid` → one page fetch → at most one row |
| `Filter` | Pulls from its child, applies a `Predicate<Row>`, forwards matches |
| `Limit` | Counts rows, then closes its child early |

Rows are pulled **one at a time** rather than materialized into a list at each step. That
is what makes `LIMIT 3` abandon a scan mid-page and touch **exactly one page** instead of
sixteen — a property the test suite asserts numerically, not directionally.

The `close()` contract is where most of the sharp edges live: it must be idempotent, and
it must be safe on a *partially consumed* operator, because `Limit` abandoning its child
mid-stream is the normal case rather than an error path.

---

## 🧱 How it was built

Each stage is a commit and a working engine. Nothing was stubbed and finished later.

| Stage | What was added | The check that proves it |
|:--:|---|---|
| **1** | `Page` + `DiskManager` — 4 KB blocks on a file | Raw bytes survive close/reopen |
| **2** | `Row` + `RowSerializer` | Lossless round trip, in memory *and* through disk, including multi-byte UTF-8 |
| **3** | `RowPage` — many rows per page | 157 variable-width rows in one page, then the same page through disk |
| **4** | `Table` — rows spilling across pages | 600 rows over 4 pages, all present after reopen |
| **5** | `Lexer`, `Parser`, AST, `Executor` | Token streams match exactly; malformed SQL throws; SQL and direct calls agree |
| **6** | `BPlusTree` + integration into `Table` | Invariants hold after every insert across 6 fanouts; lookups agree with scans |
| **7** | `BufferPool`, pins, dirty pages, LRU/FIFO | Eviction accounting, durability *through eviction alone*, policy divergence |
| **8** | `Planner` + Volcano operators | Regression, differential, plan shape, early termination, predicate semantics |
| **10** | Benchmark harness, `DataGen`, E1, E5 | Guarded measurement — see below |

---

## 📊 Benchmarks

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/e1-index-vs-scan-dark.png">
  <img alt="MiniDB E1: index seek vs full scan from 1K to 1M rows. Pages read per query is flat at 1 for the index and grows linearly to 8,334 for the scan; median latency is flat and sub-microsecond for the index and reaches 26.9 ms for the scan." src="docs/e1-index-vs-scan-light.png">
</picture>

### E1 — index seek vs. full scan

Same query, same rows, two access paths — the planner is forced onto each in turn. The
pool is fixed at **64 frames at every scale**, so the pool-as-a-fraction-of-table shrinks
as *n* grows, which is what happens to a real system as data outgrows memory.

| rows | index: pages | scan: pages | index: median | scan: median |
|-----:|-------------:|------------:|--------------:|-------------:|
| 1,000 | **1** | 9 | **0.43 µs** | 52.7 µs |
| 10,000 | **1** | 84 | **0.72 µs** | 359.4 µs |
| 100,000 | **1** | 834 | **0.44 µs** | 2,571 µs |
| 1,000,000 | **1** | 8,334 | **0.64 µs** | 26,945 µs |

**Pages read is the primary metric** because it is deterministic and is the actual
algorithmic quantity. Latency is reported beside it and carries JIT, GC and OS-cache
caveats that page counts do not. Raw data: [`e1_results.csv`](e1_results.csv), which also
carries `min`, `p95`, `gc_per_sample`, `parse_us`, and both pool regimes.

<details>
<summary><b>How the harness measures — and what it refuses to measure</b></summary>

- **20 warmup iterations**, discarded, so JIT has compiled the hot path before anything is recorded.
- **51 samples.** Odd, so the median is a real observation rather than an interpolation.
- **Adaptive batching.** Each batch is sized to land near 100 µs of work — roughly 300
  iterations for a sub-microsecond index seek, and exactly 1 for a 27 ms scan. A *fixed*
  batch over-corrects at the slow end: at B=50, a 1M warm scan sample spans 1.4 s and
  allocates ~50M rows, so every sample contains multiple GCs and the median loses its
  ability to reject them.
- **A sink.** Every result row feeds a static accumulator, so the query cannot be
  optimized away as dead code.
- **Two pool regimes**, never mixed on one chart: `WARM` (state carries across
  iterations — steady state) and `COLD` (pool cleared each iteration — the only regime
  where pool size matters).

And three **guards that abort the run** rather than publish a null result as a finding:

1. the index arm must actually have planned an `IndexSeek`
2. the query must return a non-zero number of rows
3. a `COLD` cell must have batch size exactly 1

</details>

<br>

### Calibration against SQLite 3.51.2

An asymptotic claim inside one engine proves the curve, not the constant. So the same
query, the same four scales, and the same harness rules were run against SQLite — **in
C, linked directly against `libsqlite3`**, because a Python or JDBC call frame would be a
large fraction of a sub-microsecond measurement and would flatter MiniDB by inflating
SQLite.

The schema is `WITHOUT ROWID`, so `id` is the table's real B-tree key rather than an alias
for SQLite's implicit rowid. `EXPLAIN QUERY PLAN` is asserted at every *n* — an index arm
that silently degraded to a scan would otherwise publish as a null result.

| rows | mode | MiniDB | SQLite | |
|-----:|:-----|-------:|-------:|:--|
| 1,000 | index | 0.43 µs | 2.08 µs | MiniDB 4.8× faster |
| 10,000 | index | 0.72 µs | 2.10 µs | MiniDB 2.9× faster |
| 100,000 | index | 0.44 µs | 2.14 µs | MiniDB 4.8× faster |
| 1,000,000 | index | 0.64 µs | 2.17 µs | MiniDB 3.4× faster |
| 1,000 | scan | 52.7 µs | 16.0 µs | MiniDB 3.3× slower |
| 10,000 | scan | 359 µs | 138 µs | MiniDB 2.6× slower |
| 100,000 | scan | 2,571 µs | 1,778 µs | MiniDB 1.5× slower |
| 1,000,000 | scan | 26,945 µs | 20,067 µs | MiniDB 1.3× slower |

Both index curves are flat. Both scan curves are linear. **The shapes agree, which is the
result worth having.**

> [!WARNING]
> **MiniDB "winning" the index arm is an artifact, not an achievement.** Its B+Tree lives
> in the Java heap, so a descent reads zero pages and decodes no interior nodes; SQLite
> descends real pages through its own cache and pager. MiniDB is being credited for a
> feature it does not have. The price of that missing feature is measured in E5 below and
> is paid on every single `open()`. The **scan arm is the closer comparison** — both
> engines read every page through their own cache and decode every row — and there MiniDB
> lands within about 1.5× of SQLite at the scales where buffer-pool artifacts have washed
> out.

<br>

### E5 — what the in-memory index actually costs

The index is derived from the heap, so it is **rebuilt in full on every `open()`**:

| rows | rebuild | = heap scan | + tree inserts | µs/row |
|-----:|--------:|------------:|---------------:|-------:|
| 1,000 | 0.6 ms | 0.2 ms | 0.4 ms | 0.59 |
| 10,000 | 1.5 ms | 0.9 ms | 0.6 ms | 0.15 |
| 100,000 | 13.2 ms | 2.9 ms | 10.3 ms | 0.13 |
| 1,000,000 | **173.0 ms** | 29.6 ms | 143.4 ms | 0.17 |

**173 ms before a single query runs, every time the file is opened** — to accelerate
lookups that take 0.64 µs. This is the single strongest argument for a disk-resident tree,
and it is the reason that feature sits at the top of the roadmap rather than being waved
away.

<details>
<summary><b>The 13.1× anomaly, and how it was chased down</b></summary>

The last decade costs **13.1×** rather than 10×, reproducibly (13.32 / 13.29 / 13.12 across
three runs; an early 9.77× reading came from a 7-sample configuration too noisy to
conclude from, since raised to 21).

It is **not** the tree getting taller — despite the arithmetic coincidence that
`10 × 4/3 = 13.3` and the height does go 3 → 4 there. Decomposing it: the heap scan grows
**10.1×** (linear, as it should) while the tree inserts grow **14.0×**. All of the
superlinearity is in the tree half.

The cause is `BPlusTree.childIndex`, which walks an internal node's keys **linearly**
instead of binary-searching them. One descent therefore costs `(height − 1) × fanout`
comparisons — and because rebuild inserts keys in ascending order, every descent routes to
the rightmost child and scans *every* key of *every* internal node it passes. 100K has 2
internal levels, 1M has 3, so per-insert work should rise 1.5×; measured, it rises 1.40×.
Weighted by the measured 78/22 split between tree and scan work, that predicts **13.1×** —
against **13.12×** observed.

A fanout sweep at fixed `n = 1M` is the control, because holding *n* fixed leaves height
as the only thing moving:

| fanout | height | tree work | vs. fanout 256 | height model | comparison model |
|-------:|-------:|----------:|---------------:|-------------:|-----------------:|
| 256 | 3 | 158.7 ms | 1.00× | 1.00× | 1.00× |
| 128 | 4 | 140.7 ms | 0.89× | 1.33× | 0.75× |
| 32 | 5 | 86.6 ms | **0.55×** | 1.67× | 0.25× |

**The deepest tree is the fastest one.** Height alone predicts the exact opposite
ordering, so height is not the mechanism; cost tracks `(height − 1) × fanout`, which is the
signature of the linear scan. (The comparison model overshoots the savings because
per-insert work that does not depend on fanout — leaf binary search, allocation, boxing —
dilutes it.)

Making `childIndex` a binary search is a genuine outstanding fix. It would move E1's
index-seek latency as well, so it is left as a change to make **deliberately and
re-measure**, rather than quietly before publishing these numbers.

</details>

---

## 🧪 How it is tested

This is the part of the project that took the longest and the part a file listing cannot
show. 28 checks currently pass. That number is uninteresting on its own — **the question
is what would have to break for one of them to fail.**

### Differential testing against an independent oracle

The planner is forced onto both access paths for the same query, and the two row sets are
compared **as sets**, since `IndexSeek` yields key order and `SeqScan` yields heap order.
A third answer comes from `Table.scanWhere` — the pre-planner Stage 4 code path, kept
alive rather than deleted precisely so it can serve as an oracle that shares no code with
either plan.

### Close / reopen at every layer

Raw page bytes, a single serialized row, a multi-page table, and a table whose pool
evicted continuously are each written, closed, reopened from disk, and re-read.

Stage 7b goes further and **never calls `flushAll`**: it dirties a page, forces it out
through eviction alone, then reopens the file to prove that the eviction path *itself*
persisted the bytes.

### Fault injection — because a suite that has never failed has unknown value

Six deliberate faults were written into the predicate layer one at a time, and the whole
suite was run against each to record which tests actually catch them:

| # | Injected fault | Caught by |
|:--:|---|---|
| F1 | text column ordering allowed via `compareTo` instead of rejected | **8e only** |
| F2 | strict `>` implemented as `>=` | 5d, 8a, 8e |
| F3 | unknown column matches nothing instead of throwing | **8e only** |
| F4 | cross-type literal coerced to `0` instead of rejected | **8e only** |
| F5 | text `!=` uses reference inequality | **8e only** |
| F6 | text `=` uses reference equality | 5d, 8e |

> **The interesting row is F1.** Letting `name > 'Ada'` quietly mean `compareTo` is caught
> by exactly one test in the suite, and the reason generalizes: the differential test
> builds both of its plans from the same `Predicates` class, so a wrong operator would
> **agree with itself** and both arms would return the same wrong rows. Every other test
> only checks that queries which *should* return rows return the right ones — and F1 adds
> behavior where there should be *none*, so there is nothing for them to disagree about.
>
> Only a test that asserts what the engine must **refuse** to do can see it. Stage 8e is
> that test, and F3, F4 and F5 land the same way. The two faults with wider coverage
> (F2, F6) are the two that change the answer to a query some other test already runs.

### The vacuity sweep

Late in the project every test was re-read with one question: *would this still pass if
the thing it tests did nothing at all?*

**Five passed for the wrong reason**, including one written the same day:

| Test | The hole | The fix |
|---|---|---|
| Differential | Compared `id = 4242` against a table with no such row. Two plans both returning empty "agree" — it would have passed having compared **nothing**. | Now asserts the expected row count, and that at least five comparisons were non-empty |
| Buffer-pool integration | Inserted 150 rows into a pool of capacity 3. All 150 fit on **one page**, so the pool never evicted and "survives constant eviction" was never tested. | Now 2,000 rows over 13 pages |
| LRU vs. FIFO | Checked *which* page each policy dropped, but never looked at the **contents** of the survivors — a policy that evicted correctly and corrupted everything else would have passed. | Every page now carries a marker that is verified, including on the evicted page after it is re-read from disk |
| Early termination | Asserted `limitedMisses < fullMisses`, which would still hold if `LIMIT 3` read half the table. | Now asserts **exactly 1 page** |
| Pin-leak check | Relied on eviction starvation, which only appears once *capacity distinct* pages are stuck. Repeated `LIMIT` queries all abandon page 0, so they piled pins onto one frame and could never starve the pool. | Scans are staggered across different pages, and an explicit `assertNoPinnedFrames()` is the real detector |

---

## ✂️ Deliberate scope

These are missing **on purpose**. Each one is a fork that was identified, costed and
declined — with the reason recorded at the time rather than reconstructed now.

<table>
<tr><td width="30%">

**Disk-resident B+Tree nodes**

</td><td>

The index is an in-memory Java structure derived from the heap, so it is rebuilt in full
on every `open()` — **173 ms at 1M rows**, measured by [E5](#e5--what-the-in-memory-index-actually-costs).
A persistent tree would read ~3 pages per lookup instead of 0, and read nothing at all on
startup. This is the top of the roadmap.

</td></tr>
<tr><td>

**Page-0 catalog**

</td><td>

The schema `(id, name, age)` is hardcoded in `Row` and `Predicates`. Persisting a catalog
in page 0 means a real type system, variable column counts, and schema evolution — that is
a project, not a stage, and every experiment here needs exactly one table.

</td></tr>
<tr><td>

**Non-unique index on `age`**

</td><td>

The B+Tree maps one key to one `Rid`. Supporting duplicates means either RID lists in the
leaves or key-suffix uniquification, plus range-scan semantics across leaf boundaries.
`age > 60` is deliberately left as `Filter` over `SeqScan`, which also keeps E1's two arms
cleanly separated.

</td></tr>
<tr><td>

**WAL and crash recovery**

</td><td>

Durability here is flush-on-eviction and flush-on-close, verified by reopening the file.
Real crash safety needs a log, LSNs, and ARIES-style redo/undo. Without transactions there
is nothing to roll back, so the log would be infrastructure for a feature that does not
exist yet.

</td></tr>
</table>

---

## ⚠️ Honest caveats

- **The index is in memory, so a descent reads 0 pages.** This is the single largest thumb
  on the scale in E1 and in the SQLite comparison. A real disk-resident B+Tree would read
  about 3 pages per lookup at 1M rows. E5 measures what is being paid for that shortcut.
- **One physical read versus a real descent.** MiniDB walks Java objects and then does
  exactly one page read via the RID. SQLite descends its B-tree through actual pages,
  decoding an interior node at each level. The two "index lookups" are not the same
  operation.
- **Warm OS cache throughout.** The kernel page cache is never dropped between runs —
  doing so is not portable — so `COLD` means a cold *buffer pool*, not cold storage. No
  number here is a disk-I/O measurement.
- **MiniDB re-parses on every query; SQLite uses a prepared statement.** Parse cost is
  measured separately and reported in `e1_results.csv` (`parse_us`). At 1M rows the index
  query is 0.64 µs total, of which 0.42 µs is parsing — so MiniDB's execution advantage in
  that row is *larger* than the table shows, and just as artificial.
- **Single-threaded, single-table, no transactions.** No concurrency control exists, so
  none of these numbers say anything about contention.
- **`childIndex` is a linear scan, not a binary search.** A known, quantified inefficiency
  in the tree — see E5. Left unfixed so the numbers published here describe the code as it
  stands.
- **Not JMH.** JMH is the right tool for JVM microbenchmarking; adopting it means adopting
  Maven, and this project has no build tool by design. The mitigations that matter are
  applied explicitly instead: results feed a sink so queries cannot be optimized away,
  warmup iterations are discarded, batches are sized to clear timer resolution, and medians
  are reported rather than means, with min and p95 alongside.

---

## 📁 Repo layout

```
src/main/java/com/minidb/
├── storage/     Page, DiskManager, BufferPool, Frame, EvictionPolicy + LRU/FIFO
├── record/      Row, RowSerializer, RowPage
├── index/       BPlusTree, Rid
├── table/       Table — row heap + index, insert/scan/close
├── sql/         Lexer, Parser, AST types, Predicates, Executor
├── plan/        Planner, Operator, SeqScan, IndexSeek, Filter, Limit
├── bench/       Harness, DataGen, E1IndexVsScan, E5RebuildCost
└── Main.java    The test suite — stages 1 through 8, run top to bottom

bench/           plot_e1.py, sqlite_calibration.c
bench-data/      Generated benchmark tables (cached after first run)
docs/            Chart images (light + dark)
*.csv            Benchmark results, committed so the tables above are checkable
```

| Package | Contents |
|---|---|
| `storage/` | `Page`, `DiskManager`, `BufferPool`, `Frame`, `EvictionPolicy` + LRU/FIFO |
| `record/` | `Row`, `RowSerializer`, `RowPage` (page layout over raw bytes) |
| `index/` | `BPlusTree` (search, split, invariant validation), `Rid` |
| `table/` | `Table` — row heap plus index, insert/scan/close |
| `sql/` | `Lexer`, `Parser`, AST types, `Predicates`, `Executor` |
| `plan/` | `Planner`, `Operator`, `SeqScan`, `IndexSeek`, `Filter`, `Limit` |
| `bench/` | `Harness`, `DataGen`, `E1IndexVsScan`, `E5RebuildCost` |
| `Main.java` | The test suite — stages 1–8, run top to bottom |

---

## 🗺️ Roadmap

In priority order, each one justified by a number already in this README:

1. **Binary search in `BPlusTree.childIndex`** — E5 shows the linear scan is the entire
   source of the 13.1× superlinearity, and the fanout sweep is the control that proves it.
2. **Disk-resident B+Tree nodes** — removes the 173 ms startup cost and, just as
   importantly, removes the artifact that makes MiniDB look 3–5× faster than SQLite on
   index lookups.
3. **Page-0 catalog** — the prerequisite for more than one table and more than one schema.
4. **Range scans over the leaf chain** — unlocks `id > k` and `id BETWEEN` as index plans.
5. **A cost model with real statistics** — so `EXPLAIN`'s numbers pick plans rather than
   just printing.

---

<div align="center">

**MiniDB** — built stage by stage, measured rather than asserted.

*If a claim in this README is not backed by a CSV in this repository, it should not be here.*

</div>
