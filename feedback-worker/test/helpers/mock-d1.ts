/**
 * Minimal in-memory D1 mock for testing.
 * Tracks all bound statements so tests can inspect inserts/updates, and
 * serves `_rows` back from first()/all() so reads can be exercised.
 */

export interface MockStatement {
  sql: string;
  params: unknown[];
}

export interface MockD1 {
  _statements: MockStatement[];
  _rows: Record<string, unknown>[];
  _shouldFail: boolean;
  prepare: (sql: string) => MockPreparedStatement;
  batch: (stmts: MockPreparedStatement[]) => Promise<unknown[]>;
}

interface MockPreparedStatement {
  _sql: string;
  _params: unknown[];
  bind: (...params: unknown[]) => MockPreparedStatement;
  run: () => Promise<{ success: boolean }>;
  first: () => Promise<Record<string, unknown> | null>;
  all: () => Promise<{ results: Record<string, unknown>[] }>;
}

export function createMockD1(): MockD1 {
  const db: MockD1 = {
    _statements: [],
    _rows: [],
    _shouldFail: false,

    prepare(sql: string): MockPreparedStatement {
      const stmt: MockPreparedStatement = {
        _sql: sql,
        _params: [],

        bind(...params: unknown[]) {
          stmt._params = params;
          return stmt;
        },

        async run() {
          if (db._shouldFail) throw new Error("D1 mock failure");
          db._statements.push({ sql: stmt._sql, params: stmt._params });
          return { success: true };
        },

        async first() {
          if (db._shouldFail) throw new Error("D1 mock failure");
          db._statements.push({ sql: stmt._sql, params: stmt._params });
          return db._rows[0] ?? null;
        },

        async all() {
          if (db._shouldFail) throw new Error("D1 mock failure");
          db._statements.push({ sql: stmt._sql, params: stmt._params });
          return { results: db._rows };
        },
      };
      return stmt;
    },

    async batch(stmts: MockPreparedStatement[]) {
      if (db._shouldFail) throw new Error("D1 mock batch failure");
      for (const s of stmts) {
        db._statements.push({ sql: s._sql, params: s._params });
      }
      return stmts.map(() => ({ success: true }));
    },
  };

  return db;
}
