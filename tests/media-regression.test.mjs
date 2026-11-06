import test from 'node:test';
import assert from 'node:assert/strict';
import { createAudioEngine } from '../web/media.js';

const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
};

function replaceGlobal(t, key, value) {
  const descriptor = Object.getOwnPropertyDescriptor(globalThis, key);
  Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  t.after(() => descriptor ? Object.defineProperty(globalThis, key, descriptor) : delete globalThis[key]);
}

function audioHarness(t) {
  const contexts = [], players = [];
  class Context {
    state = 'suspended';
    currentTime = 0;
    sampleRate = 8;
    destination = {};
    resumes = [];
    activeSources = new Set();
    constructor() { contexts.push(this); }
    resume() {
      const pending = deferred();
      this.resumes.push(pending);
      return pending.promise.then(() => { if (this.state !== 'closed') this.state = 'running'; });
    }
    suspend() { this.state = 'suspended'; return Promise.resolve(); }
    close() { this.state = 'closed'; return Promise.resolve(); }
    node() {
      const parameter = () => ({ value: 0, linearRampToValueAtTime() {}, cancelScheduledValues() {}, setTargetAtTime() {} });
      const node = {
        gain: parameter(), frequency: parameter(), detune: parameter(),
        connect(target) { return target; }, disconnect() {},
        start: () => this.activeSources.add(node), stop: () => this.activeSources.delete(node),
      };
      return node;
    }
    createGain() { return this.node(); }
    createOscillator() { return this.node(); }
    createBufferSource() { return this.node(); }
    createBiquadFilter() { return this.node(); }
    createBuffer(_channels, length) { return { getChannelData: () => new Float32Array(length) }; }
  }
  class Player {
    plays = [];
    paused = true;
    constructor(url) { this.src = url; players.push(this); }
    play() { const pending = deferred(); this.plays.push(pending); this.paused = false; return pending.promise; }
    pause() { this.paused = true; }
    removeAttribute() { this.src = ''; }
    load() {}
  }
  replaceGlobal(t, 'AudioContext', Context);
  replaceGlobal(t, 'Audio', Player);
  const engine = createAudioEngine();
  t.after(() => engine.cleanup());
  return { engine, contexts, players };
}

const audioFile = () => new Blob(['audio'], { type: 'audio/mpeg' });

test('pause cancels an ambient start that is still waiting for audio permission', async t => {
  const { engine, contexts } = audioHarness(t);
  const pending = engine.start('coast');
  engine.pause();
  contexts[0].resumes[0].resolve();
  await pending;
  assert.equal(engine.getState().playing, false);
  assert.equal(contexts[0].activeSources.size, 0);
});

test('cleanup safely cancels a pending ambient start', async t => {
  const { engine, contexts } = audioHarness(t);
  const pending = engine.start();
  engine.cleanup();
  contexts[0].resumes[0].resolve();
  await assert.doesNotReject(pending);
  assert.equal(engine.getState().playing, false);
  assert.equal(engine.getState().unlocked, false);
  assert.equal(contexts[0].activeSources.size, 0);
});

test('loading a personal audio file cancels an older ambient start', async t => {
  const { engine, contexts } = audioHarness(t);
  const pending = engine.start();
  await engine.loadFile(audioFile());
  contexts[0].resumes[0].resolve();
  await pending;
  assert.equal(engine.getState().source, 'file');
  assert.equal(engine.getState().playing, false);
  assert.equal(contexts[0].activeSources.size, 0);
});

test('pause stays effective after a pending personal audio play resolves', async t => {
  const { engine, players } = audioHarness(t);
  await engine.loadFile(audioFile());
  const pending = engine.play();
  engine.pause();
  players[0].plays[0].resolve();
  await pending;
  assert.equal(engine.getState().playing, false);
  assert.equal(players[0].paused, true);
});

test('a superseded file play rejection does not stop the active ambient sound', async t => {
  const { engine, contexts, players } = audioHarness(t);
  await engine.loadFile(audioFile());
  const pending = engine.play();
  const ambient = engine.start('forest');
  contexts[0].resumes[0].resolve();
  await ambient;
  players[0].plays[0].reject(new Error('Interrupted by pause'));
  await assert.doesNotReject(pending);
  assert.equal(engine.getState().source, 'ambient');
  assert.equal(engine.getState().playing, true);
});

test('old file events cannot change the active ambient playback state', async t => {
  const { engine, contexts, players } = audioHarness(t);
  await engine.loadFile(audioFile());
  const pending = engine.start();
  contexts[0].resumes[0].resolve();
  await pending;
  players[0].onended?.();
  players[0].onerror?.();
  assert.equal(engine.getState().playing, true);
});

test('a failed ambient transition does not claim the paused personal file is still playing', async t => {
  const { engine, contexts, players } = audioHarness(t);
  await engine.loadFile(audioFile());
  const filePlay = engine.play();
  players[0].plays[0].resolve();
  await filePlay;
  const ambient = engine.start();
  contexts[0].resumes[0].reject(new Error('Audio permission denied'));
  await assert.rejects(ambient, /Audio permission denied/);
  assert.equal(engine.getState().playing, false);
  assert.equal(players[0].paused, true);
});
