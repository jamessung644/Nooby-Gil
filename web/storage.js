/** Device-local storage. A resolved write means the IndexedDB transaction committed. */
const PREFS_KEY = 'scenic.preferences.v1';
const DB_NAME = 'scenic-local-media';
const DB_VERSION = 1;
const STORE = 'clips';
const MAX_BYTES = 100 * 1024 * 1024;
let databasePromise;

export function loadPreferences() {
  try {
    const value = JSON.parse(localStorage.getItem(PREFS_KEY) || '{}');
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  } catch { return {}; }
}

export function savePreferences(preferences) {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify(preferences));
    return true;
  } catch { return false; }
}

function openDatabase() {
  if (!globalThis.indexedDB) return Promise.reject(new Error('이 브라우저에서 영상 보관함을 지원하지 않습니다.'));
  if (databasePromise) return databasePromise;
  const opening = new Promise((resolve, reject) => {
    let failed = false;
    const fail = error => { failed = true; reject(error); };
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) {
        const clips = db.createObjectStore(STORE, { keyPath: 'id' });
        clips.createIndex('createdAt', 'createdAt');
      }
    };
    request.onerror = () => fail(request.error || new Error('영상 보관함을 열 수 없습니다.'));
    request.onblocked = () => fail(new Error('다른 SCENIC 탭을 닫은 후 다시 시도해 주세요.'));
    request.onsuccess = () => {
      const db = request.result;
      // A blocked request may still succeed after its caller has already retried.
      if (failed) { db.close(); return; }
      const invalidate = () => { if (databasePromise === opening) databasePromise = undefined; };
      db.onversionchange = () => { db.close(); invalidate(); };
      db.onclose = invalidate;
      resolve(db);
    };
  }).catch(error => {
    if (databasePromise === opening) databasePromise = undefined;
    throw error;
  });
  databasePromise = opening;
  return databasePromise;
}

async function transact(mode, operation) {
  const db = await openDatabase();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(STORE, mode);
    let result;
    let request;
    try { request = operation(transaction.objectStore(STORE)); }
    catch (error) { transaction.abort(); reject(error); return; }
    request.onsuccess = () => { result = request.result; };
    transaction.oncomplete = () => resolve(result);
    transaction.onerror = () => reject(transaction.error || request.error || new Error('보관함 작업에 실패했습니다.'));
    transaction.onabort = () => reject(transaction.error || request.error || new Error('보관함 작업이 취소되었습니다.'));
  });
}

export async function listClips() {
  const clips = await transact('readonly', store => store.getAll());
  return clips.sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''));
}

export async function saveClip({ id, name, blob, mimeType, provenance, metadata = {} }) {
  if (!(blob instanceof Blob) || !blob.size) throw new Error('저장할 영상이 비어 있습니다.');
  if (blob.size > MAX_BYTES) throw new Error('영상은 100MB 이하로 보관할 수 있습니다.');
  const type = mimeType || blob.type;
  if (!type || !type.startsWith('video/')) throw new Error('영상 파일만 보관할 수 있습니다.');
  const clip = {
    id: id || globalThis.crypto?.randomUUID?.() || `clip-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    name: String(name || '나의 드라이브 기록').slice(0, 160),
    blob,
    mimeType: type,
    size: blob.size,
    createdAt: new Date().toISOString(),
    provenance: provenance || 'user-upload',
    metadata: { ...metadata },
  };
  await transact('readwrite', store => store.put(clip));
  return clip;
}

export async function deleteClip(id) {
  if (!id) throw new Error('삭제할 영상이 선택되지 않았습니다.');
  await transact('readwrite', store => store.delete(id));
  return true;
}
