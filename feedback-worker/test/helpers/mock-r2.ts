/** Minimal in-memory R2 mock for testing media storage. */

export interface MockR2 {
  _store: Map<string, { data: ArrayBuffer; contentType?: string }>;
  put: (
    key: string,
    value: ArrayBuffer,
    opts?: { httpMetadata?: { contentType?: string } },
  ) => Promise<void>;
  get: (key: string) => Promise<{
    body: ReadableStream;
    httpMetadata?: { contentType?: string };
  } | null>;
  delete: (key: string) => Promise<void>;
}

export function createMockR2(): MockR2 {
  const store = new Map<string, { data: ArrayBuffer; contentType?: string }>();

  return {
    _store: store,

    async put(key, value, opts) {
      store.set(key, {
        data: value,
        contentType: opts?.httpMetadata?.contentType,
      });
    },

    async get(key) {
      const entry = store.get(key);
      if (!entry) return null;
      const data = entry.data;
      return {
        httpMetadata: entry.contentType
          ? { contentType: entry.contentType }
          : undefined,
        body: new ReadableStream({
          start(ctrl) {
            ctrl.enqueue(new Uint8Array(data));
            ctrl.close();
          },
        }),
      };
    },

    async delete(key) {
      store.delete(key);
    },
  };
}
