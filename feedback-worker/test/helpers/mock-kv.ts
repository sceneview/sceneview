/** Minimal in-memory KV mock for testing rate limiting. */

export interface MockKV {
  _store: Map<string, string>;
  get: (key: string) => Promise<string | null>;
  put: (
    key: string,
    value: string,
    opts?: { expirationTtl?: number },
  ) => Promise<void>;
  delete: (key: string) => Promise<void>;
}

export function createMockKV(): MockKV {
  const store = new Map<string, string>();

  return {
    _store: store,

    async get(key: string) {
      return store.get(key) ?? null;
    },

    async put(key: string, value: string) {
      store.set(key, value);
    },

    async delete(key: string) {
      store.delete(key);
    },
  };
}
