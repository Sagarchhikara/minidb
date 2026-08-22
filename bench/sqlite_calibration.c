/*
 * E-cal: SQLite calibration for MiniDB's E1.
 *
 * Answers one question -- "is MiniDB's index/scan gap the real shape, and how far off
 * the pace is it in absolute terms?" -- and nothing else. Same scales as E1, same query,
 * same two access paths, same warmup/batching/percentile rules as com.minidb.bench.Harness.
 *
 * Written in C against libsqlite3 rather than through a language binding, because the
 * numbers under test are sub-microsecond: a Python or JDBC call frame would be a large
 * fraction of the measurement and would flatter MiniDB by inflating SQLite.
 *
 * Schema is WITHOUT ROWID, so `id` is the table's actual B-tree key rather than an alias
 * for SQLite's implicit rowid. The plan is asserted with EXPLAIN QUERY PLAN at every n --
 * an index arm that silently degraded to a scan would otherwise publish as a null result.
 *
 * Build: gcc -O2 bench/sqlite_calibration.c /usr/lib64/libsqlite3.so.0 -o /tmp/sqlite_cal
 * (No sqlite3.h on this box, so the handful of entry points used are declared here.)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct sqlite3 sqlite3;
typedef struct sqlite3_stmt sqlite3_stmt;

extern int sqlite3_open(const char *, sqlite3 **);
extern int sqlite3_close(sqlite3 *);
extern int sqlite3_exec(sqlite3 *, const char *, int (*)(void *, int, char **, char **),
                        void *, char **);
extern int sqlite3_prepare_v2(sqlite3 *, const char *, int, sqlite3_stmt **, const char **);
extern int sqlite3_step(sqlite3_stmt *);
extern int sqlite3_reset(sqlite3_stmt *);
extern int sqlite3_finalize(sqlite3_stmt *);
extern int sqlite3_bind_int(sqlite3_stmt *, int, int);
extern int sqlite3_column_int(sqlite3_stmt *, int);
extern const unsigned char *sqlite3_column_text(sqlite3_stmt *, int);
extern const char *sqlite3_errmsg(sqlite3 *);
extern const char *sqlite3_libversion(void);

#define SQLITE_OK   0
#define SQLITE_ROW  100
#define SQLITE_DONE 101

/* Mirrors Harness.WARMUP / Harness.MEASURE / TARGET_BATCH_NANOS exactly. */
#define WARMUP  20
#define MEASURE 51
#define TARGET_BATCH_NANOS 100000L
#define MAX_BATCH 1000

static const int SCALES[] = {1000, 10000, 100000, 1000000};
static const int NUM_SCALES = 4;

/* Guards against the compiler deleting the query, same job as Harness.sink. */
static volatile long sink;

static long now_nanos(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000000000L + ts.tv_nsec;
}

static int cmp_double(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return (x > y) - (x < y);
}

/* Linear-interpolated percentile over a sorted array -- same rule as Harness.percentile. */
static double percentile(double *sorted, int len, double q) {
    double pos = q * (len - 1);
    int lo = (int)pos, hi = lo + (pos > (double)lo ? 1 : 0);
    if (lo == hi) return sorted[lo];
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - (double)lo);
}

static void die(sqlite3 *db, const char *what) {
    fprintf(stderr, "FATAL: %s: %s\n", what, db ? sqlite3_errmsg(db) : "(no handle)");
    exit(1);
}

static void run_sql(sqlite3 *db, const char *sql) {
    char *err = NULL;
    if (sqlite3_exec(db, sql, NULL, NULL, &err) != SQLITE_OK) {
        fprintf(stderr, "FATAL: %s -> %s\n", sql, err ? err : "?");
        exit(1);
    }
}

/*
 * Same rows as com.minidb.bench.DataGen: ids 1..n dense, fixed-width name, age 20..79.
 * Regenerated only when absent, for the same reason DataGen caches.
 */
static void ensure_db(const char *path, int n) {
    FILE *probe = fopen(path, "rb");
    if (probe) {
        fclose(probe);
        sqlite3 *db = NULL;
        if (sqlite3_open(path, &db) != SQLITE_OK) die(db, "open for verify");
        sqlite3_stmt *st = NULL;
        if (sqlite3_prepare_v2(db, "SELECT count(*) FROM users", -1, &st, NULL) == SQLITE_OK
            && sqlite3_step(st) == SQLITE_ROW && sqlite3_column_int(st, 0) == n) {
            sqlite3_finalize(st);
            sqlite3_close(db);
            return;
        }
        if (st) sqlite3_finalize(st);
        sqlite3_close(db);
        remove(path);
    }

    sqlite3 *db = NULL;
    if (sqlite3_open(path, &db) != SQLITE_OK) die(db, "open for build");
    run_sql(db, "PRAGMA journal_mode=DELETE;");
    run_sql(db, "CREATE TABLE users("
                "  id   INTEGER NOT NULL,"
                "  name TEXT    NOT NULL,"
                "  age  INTEGER NOT NULL,"
                "  PRIMARY KEY(id)"
                ") WITHOUT ROWID;");
    run_sql(db, "BEGIN;");
    sqlite3_stmt *ins = NULL;
    if (sqlite3_prepare_v2(db, "INSERT INTO users VALUES (?, ?, ?)", -1, &ins, NULL) != SQLITE_OK)
        die(db, "prepare insert");
    for (int id = 1; id <= n; id++) {
        char name[64];
        snprintf(name, sizeof name, "user-%09d-padding", id);
        sqlite3_bind_int(ins, 1, id);
        /* -1 length, SQLITE_TRANSIENT is (void*)-1 */
        extern int sqlite3_bind_text(sqlite3_stmt *, int, const char *, int, void (*)(void *));
        sqlite3_bind_text(ins, 2, name, -1, (void (*)(void *)) - 1);
        sqlite3_bind_int(ins, 3, 20 + (id % 60));
        if (sqlite3_step(ins) != SQLITE_DONE) die(db, "insert step");
        sqlite3_reset(ins);
    }
    sqlite3_finalize(ins);
    run_sql(db, "COMMIT;");
    run_sql(db, "ANALYZE;");
    sqlite3_close(db);
    fprintf(stderr, "  built %s (%d rows)\n", path, n);
}

/*
 * Asserts the access path before the clock starts.
 *
 * This is the SQLite half of the guard Harness.run() applies to MiniDB: if the index arm
 * quietly planned a scan, both arms would trace the same curve and the null result would
 * read as "the index does not help" instead of "the plan was wrong".
 */
static void assert_plan(sqlite3 *db, const char *sql, const char *must_contain,
                        const char *must_not_contain, char *plan_out, size_t plan_len) {
    char eqp[512];
    snprintf(eqp, sizeof eqp, "EXPLAIN QUERY PLAN %s", sql);
    sqlite3_stmt *st = NULL;
    if (sqlite3_prepare_v2(db, eqp, -1, &st, NULL) != SQLITE_OK) die(db, "prepare EQP");

    plan_out[0] = '\0';
    while (sqlite3_step(st) == SQLITE_ROW) {
        const unsigned char *detail = sqlite3_column_text(st, 3);
        if (detail) {
            if (plan_out[0]) strncat(plan_out, " | ", plan_len - strlen(plan_out) - 1);
            strncat(plan_out, (const char *)detail, plan_len - strlen(plan_out) - 1);
        }
    }
    sqlite3_finalize(st);

    if (!strstr(plan_out, must_contain)) {
        fprintf(stderr, "FATAL: plan for [%s] must contain '%s' but was: %s\n",
                sql, must_contain, plan_out);
        exit(1);
    }
    if (must_not_contain && strstr(plan_out, must_not_contain)) {
        fprintf(stderr, "FATAL: plan for [%s] must NOT contain '%s' but was: %s\n",
                sql, must_not_contain, plan_out);
        exit(1);
    }
}

/* One execution of the prepared statement, draining every row it returns. */
static int execute_once(sqlite3_stmt *st) {
    int rows = 0, rc;
    sqlite3_reset(st);
    while ((rc = sqlite3_step(st)) == SQLITE_ROW) {
        rows++;
        sink += sqlite3_column_int(st, 0);
        const unsigned char *name = sqlite3_column_text(st, 1);
        if (name) sink += name[0];
        sink += sqlite3_column_int(st, 2);
    }
    if (rc != SQLITE_DONE) { fprintf(stderr, "FATAL: step rc=%d\n", rc); exit(1); }
    return rows;
}

typedef struct { double median, min, p95; int batch, rows; char plan[512]; } result;

static result measure(sqlite3 *db, const char *sql, const char *must, const char *must_not) {
    result r;
    memset(&r, 0, sizeof r);
    assert_plan(db, sql, must, must_not, r.plan, sizeof r.plan);

    sqlite3_stmt *st = NULL;
    if (sqlite3_prepare_v2(db, sql, -1, &st, NULL) != SQLITE_OK) die(db, "prepare query");

    /* A query that returns nothing measures the miss path, not the work. */
    r.rows = execute_once(st);
    if (r.rows == 0) { fprintf(stderr, "FATAL: [%s] returned no rows\n", sql); exit(1); }

    /* Adaptive batch, same rule as Harness.calibrateBatch. */
    for (int i = 0; i < 5; i++) execute_once(st);
    long best = -1;
    for (int i = 0; i < 5; i++) {
        long t0 = now_nanos();
        execute_once(st);
        long d = now_nanos() - t0;
        if (best < 0 || d < best) best = d;
    }
    r.batch = (int)(best > 0 ? TARGET_BATCH_NANOS / best : MAX_BATCH);
    if (r.batch < 1) r.batch = 1;
    if (r.batch > MAX_BATCH) r.batch = MAX_BATCH;

    for (int i = 0; i < WARMUP; i++)
        for (int k = 0; k < r.batch; k++) execute_once(st);

    double *samples = malloc(sizeof(double) * MEASURE);
    for (int i = 0; i < MEASURE; i++) {
        long t0 = now_nanos();
        for (int k = 0; k < r.batch; k++) execute_once(st);
        samples[i] = (double)(now_nanos() - t0) / 1000.0 / r.batch;
    }
    qsort(samples, MEASURE, sizeof(double), cmp_double);
    r.median = percentile(samples, MEASURE, 0.50);
    r.min = samples[0];
    r.p95 = percentile(samples, MEASURE, 0.95);
    free(samples);
    sqlite3_finalize(st);
    return r;
}

int main(void) {
    printf("E-cal: SQLite %s, WITHOUT ROWID schema, prepared statements, warm cache.\n",
           sqlite3_libversion());
    printf("Same scales, same query, same harness rules as MiniDB E1 "
           "(warmup %d, %d samples, adaptive batch).\n\n", WARMUP, MEASURE);
    printf("%-9s %-6s %8s %6s %11s %11s %11s   %s\n",
           "n", "mode", "rows", "batch", "median us", "min us", "p95 us", "EXPLAIN QUERY PLAN");

    FILE *csv = fopen("sqlite_results.csv", "w");
    fprintf(csv, "n,mode,rows,batch,median_us,min_us,p95_us,plan\n");

    for (int i = 0; i < NUM_SCALES; i++) {
        int n = SCALES[i];
        char path[256];
        snprintf(path, sizeof path, "bench-data/sqlite_%d.db", n);
        ensure_db(path, n);

        sqlite3 *db = NULL;
        if (sqlite3_open(path, &db) != SQLITE_OK) die(db, "open for bench");

        char idx_sql[256], scan_sql[256];
        /* Same query E1 runs: a point lookup in the middle of the key range. */
        snprintf(idx_sql, sizeof idx_sql,
                 "SELECT id, name, age FROM users WHERE id = %d", n / 2);
        /* Unary + defeats index use on the same predicate -- SQLite's useIndexes(false). */
        snprintf(scan_sql, sizeof scan_sql,
                 "SELECT id, name, age FROM users WHERE +id = %d", n / 2);

        result idx = measure(db, idx_sql, "SEARCH", "SCAN");
        result scan = measure(db, scan_sql, "SCAN", "SEARCH");

        result *both[2] = {&idx, &scan};
        const char *names[2] = {"index", "scan"};
        for (int m = 0; m < 2; m++) {
            printf("%-9d %-6s %8d %6d %11.3f %11.3f %11.3f   %s\n",
                   n, names[m], both[m]->rows, both[m]->batch, both[m]->median,
                   both[m]->min, both[m]->p95, both[m]->plan);
            fprintf(csv, "%d,%s,%d,%d,%.3f,%.3f,%.3f,\"%s\"\n",
                    n, names[m], both[m]->rows, both[m]->batch, both[m]->median,
                    both[m]->min, both[m]->p95, both[m]->plan);
        }
        sqlite3_close(db);
    }

    fclose(csv);
    printf("\nWrote sqlite_results.csv  (sink=%ld)\n", sink);
    return 0;
}
