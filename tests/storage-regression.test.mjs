import test from 'node:test';
import assert from 'node:assert/strict';

let moduleNumber = 0;
async function storageHarness(t) {
  const requests = [];
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'indexedDB');
  Object.defineProperty(globalThis, 'indexedDB', { configurable: true, value: { open() { const request = {}; requests.push(request); return request; } } });
  t.after(() => descriptor ? Object.defineProperty(globalThis, 'indexedDB', descriptor) : delete globalThis.indexedDB);
  const storage = await import(`../web/storage.js?regression=${++moduleNumber}`);
  return { storage, requests };
}

function database() {
  return {
    closed: false,
    close() { this.closed = true; },
    transaction() {
      if (this.closed) throw new DOMException('Database is closed', 'InvalidStateError');
      const request = { result: [] };
      const transaction = { objectStore: () => ({ getAll: () => request }) };
      queueMicrotask(() => { request.onsuccess(); transaction.oncomplete(); });
      return transaction;
    },
  };
}

test('a blocked open closes its late connection and leaves the successful retry cached', async t => {
  const { storage, requests } = await storageHarness(t);
  const blocked = storage.listClips();
  requests[0].onblocked();
  await assert.rejects(blocked, /다른 누비길 탭/);

  const retry = storage.listClips();
  const current = database();
  requests[1].result = current;
  requests[1].onsuccess();
  assert.deepEqual(await retry, []);

  const abandoned = database();
  requests[0].result = abandoned;
  requests[0].onsuccess();
  assert.equal(abandoned.closed, true, 'a rejected open must not keep a connection blocking other tabs');
  abandoned.onversionchange?.();
  assert.deepEqual(await storage.listClips(), []);
  assert.equal(requests.length, 2, 'the stale connection must not invalidate the current connection');
});

test('an unexpectedly closed database can be reopened on the next library operation', async t => {
  const { storage, requests } = await storageHarness(t);
  const initial = storage.listClips();
  const first = database();
  requests[0].result = first;
  requests[0].onsuccess();
  await initial;
  first.closed = true;
  first.onclose?.();
  const retry = storage.listClips();
  const observed = retry.catch(error => error);
  await Promise.resolve();
  assert.equal(requests.length, 2);
  requests[1].result = database();
  requests[1].onsuccess();
  assert.deepEqual(await observed, []);
});
