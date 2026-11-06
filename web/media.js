/** Original procedural soundscapes. No remote audio, recordings, or commercial tracks. */
const SCENES = {
  lake: { notes: [146.83, 220, 293.66, 369.99], filter: 580, noise: 0.024, pulse: 0.07 },
  coast: { notes: [130.81, 196, 261.63, 329.63], filter: 1050, noise: 0.04, pulse: 0.12 },
  forest: { notes: [164.81, 246.94, 329.63, 392], filter: 1450, noise: 0.019, pulse: 0.18 },
  city: { notes: [110, 164.81, 220, 293.66], filter: 850, noise: 0.014, pulse: 0.28 },
};
const MAX_FILE_BYTES = 100 * 1024 * 1024;
const clamp = (n, min, max) => Math.min(max, Math.max(min, n));

export function createAudioEngine() {
  let context;
  let master;
  let current;
  let player;
  let fileUrl;
  let fileName = '';
  let playing = false;
  let source = 'ambient';
  let scene = 'lake';
  let volume = 0.42;
  let unlocked = false;
  let playbackToken = 0;
  const retiring = new Set();
  const getState = () => ({ source, scene, volume, playing, unlocked, fileName, hasFile: Boolean(fileUrl) });

  function ensureContext() {
    if (!context) {
      const AudioContextClass = globalThis.AudioContext || globalThis.webkitAudioContext;
      if (!AudioContextClass) throw new Error('이 브라우저는 앰비언트 사운드를 지원하지 않습니다.');
      context = new AudioContextClass();
      master = context.createGain();
      master.gain.value = volume;
      master.connect(context.destination);
    }
    return context;
  }

  function makeSoundscape(nextScene) {
    const config = SCENES[nextScene];
    const bus = context.createGain();
    const nodes = [];
    bus.gain.value = 0;
    bus.connect(master);
    config.notes.forEach((frequency, index) => {
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      oscillator.type = 'sine';
      oscillator.frequency.value = frequency;
      oscillator.detune.value = (index % 2 ? 1 : -1) * 3;
      gain.gain.value = 0.045 / (1 + index * 0.25);
      oscillator.connect(gain).connect(bus);
      oscillator.start();
      nodes.push(oscillator, gain);
    });
    const buffer = context.createBuffer(1, context.sampleRate * 5, context.sampleRate);
    const data = buffer.getChannelData(0);
    let prior = 0;
    for (let index = 0; index < data.length; index++) {
      prior = (prior + (Math.random() * 2 - 1) * 0.025) / 1.025;
      data[index] = prior * 4;
    }
    const noise = context.createBufferSource();
    const filter = context.createBiquadFilter();
    const noiseGain = context.createGain();
    const lfo = context.createOscillator();
    const lfoGain = context.createGain();
    noise.buffer = buffer;
    noise.loop = true;
    filter.type = 'lowpass';
    filter.frequency.value = config.filter;
    noiseGain.gain.value = config.noise;
    lfo.frequency.value = config.pulse;
    lfoGain.gain.value = config.noise * 0.55;
    lfo.connect(lfoGain).connect(noiseGain.gain);
    noise.connect(filter).connect(noiseGain).connect(bus);
    noise.start();
    lfo.start();
    nodes.push(noise, filter, noiseGain, lfo, lfoGain);
    bus.gain.linearRampToValueAtTime(1, context.currentTime + 1.1);
    return { bus, nodes };
  }

  function destroySoundscape(soundscape, immediate = false) {
    if (!soundscape || soundscape.destroyed) return;
    const destroy = () => {
      if (soundscape.destroyed) return;
      soundscape.destroyed = true;
      for (const node of soundscape.nodes) {
        try { node.stop?.(); } catch { /* Already stopped. */ }
        try { node.disconnect(); } catch { /* Already disconnected. */ }
      }
      soundscape.bus.disconnect();
      retiring.delete(soundscape);
      if (soundscape.timer) clearTimeout(soundscape.timer);
    };
    if (immediate) { destroy(); return; }
    retiring.add(soundscape);
    soundscape.bus.gain.cancelScheduledValues(context.currentTime);
    soundscape.bus.gain.setTargetAtTime(0, context.currentTime, 0.28);
    soundscape.timer = setTimeout(destroy, 1300);
  }

  function endAmbient() {
    destroySoundscape(current, true);
    current = undefined;
    for (const old of [...retiring]) destroySoundscape(old, true);
  }

  async function start(nextScene = scene) {
    const ownToken = ++playbackToken;
    scene = SCENES[nextScene] ? nextScene : 'lake';
    const ownContext = ensureContext();
    player?.pause();
    endAmbient();
    playing = false;
    source = 'ambient';
    try { await ownContext.resume(); }
    catch (error) {
      if (ownToken !== playbackToken) return getState();
      throw error;
    }
    if (ownToken !== playbackToken) return getState();
    if (ownContext.state !== 'running') throw new Error('사운드 시작 버튼을 다시 눌러 주세요.');
    current = makeSoundscape(scene);
    playing = true;
    unlocked = true;
    return getState();
  }

  function setScene(nextScene) {
    if (!SCENES[nextScene] || nextScene === scene) return getState();
    scene = nextScene;
    if (source === 'ambient' && playing && context?.state === 'running') {
      const old = current;
      current = makeSoundscape(scene);
      destroySoundscape(old);
    }
    return getState();
  }

  function pause() {
    playbackToken++;
    player?.pause();
    endAmbient();
    playing = false;
    if (context?.state === 'running') context.suspend().catch(() => {});
    return getState();
  }

  function stop() {
    pause();
    if (player) { try { player.currentTime = 0; } catch { /* No seekable media. */ } }
    return getState();
  }

  async function play() {
    if (source !== 'file' || !player) return start(scene);
    const ownToken = ++playbackToken;
    const ownPlayer = player;
    endAmbient();
    try {
      if (context?.state === 'running') await context.suspend();
      if (ownToken !== playbackToken) return getState();
      await ownPlayer.play();
    } catch {
      if (ownToken !== playbackToken) return getState();
      playing = false;
      throw new Error('오디오를 재생할 수 없습니다. 지원되는 파일인지 확인해 주세요.');
    }
    if (ownToken !== playbackToken) return getState();
    playing = true;
    unlocked = true;
    return getState();
  }

  async function loadFile(file) {
    if (!(file instanceof Blob) || !file.size) throw new Error('오디오 파일이 비어 있습니다.');
    if (file.size > MAX_FILE_BYTES) throw new Error('음원은 100MB 이하의 파일을 선택해 주세요.');
    if (!file.type.startsWith('audio/')) throw new Error('오디오 파일만 선택할 수 있습니다.');
    stop();
    if (fileUrl) URL.revokeObjectURL(fileUrl);
    fileUrl = URL.createObjectURL(file);
    fileName = file.name || '내 음원';
    player = new Audio(fileUrl);
    const ownPlayer = player;
    player.preload = 'metadata';
    player.volume = volume;
    const endFile = () => { if (player === ownPlayer && source === 'file') playing = false; };
    player.onended = endFile;
    player.onerror = endFile;
    source = 'file';
    return getState();
  }

  function setVolume(value) {
    volume = clamp(Number(value) || 0, 0, 1);
    if (master) master.gain.setTargetAtTime(volume, context.currentTime, 0.08);
    if (player) player.volume = volume;
    return getState();
  }

  function cleanup() {
    stop();
    if (fileUrl) URL.revokeObjectURL(fileUrl);
    fileUrl = undefined;
    if (player) { player.removeAttribute('src'); player.load(); player = undefined; }
    context?.close().catch(() => {});
    context = undefined;
    master = undefined;
    source = 'ambient';
    fileName = '';
    unlocked = false;
  }

  return { start, setScene, stop, setVolume, loadFile, play, pause, getState, cleanup };
}

/** Camera recording only begins when the UI explicitly calls start(). */
export function createCameraRecorder() {
  let recorder;
  let stream;
  let chunks = [];
  let bytes = 0;
  let startedAt = 0;
  let stopPromise;
  let resolveStop;
  let rejectStop;
  let stopTimer;
  let pending = false;
  let active = false;
  let token = 0;
  let callback;
  let lastResult;
  let failure;
  const getState = () => ({ recording: recorder?.state === 'recording', pending, mimeType: recorder?.mimeType || '', bytes });

  function stopTracks() {
    clearTimeout(stopTimer);
    if (stream) for (const track of stream.getTracks()) { track.onended = null; track.stop(); }
    stream = undefined;
  }

  async function start({ onPreview, onStopped } = {}) {
    if (pending || active) throw new Error('이미 녹화 중이거나 영상을 정리하고 있습니다.');
    if (!globalThis.navigator?.mediaDevices?.getUserMedia || !globalThis.MediaRecorder) {
      throw new Error('이 환경에서는 카메라 녹화를 지원하지 않습니다. HTTPS 또는 localhost에서 파일 업로드를 이용해 주세요.');
    }
    pending = true;
    const ownToken = ++token;
    try {
      const acquired = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } }, audio: false });
      if (ownToken !== token) {
        acquired.getTracks().forEach(track => track.stop());
        throw new Error('카메라 시작이 취소되었습니다.');
      }
      stream = acquired;
      const mimeType = ['video/webm;codecs=vp8', 'video/webm', 'video/mp4'].find(type => MediaRecorder.isTypeSupported(type));
      recorder = new MediaRecorder(stream, mimeType ? { mimeType, videoBitsPerSecond: 2500000 } : undefined);
      active = true;
      chunks = [];
      bytes = 0;
      failure = undefined;
      lastResult = undefined;
      startedAt = Date.now();
      callback = onStopped;
      stopPromise = new Promise((resolve, reject) => { resolveStop = resolve; rejectStop = reject; });
      // A caller may wait until a later user action before awaiting stop().
      stopPromise.catch(() => {});
      recorder.ondataavailable = event => {
        if (!event.data?.size) return;
        if (bytes + event.data.size > MAX_FILE_BYTES) {
          failure = new Error('녹화가 100MB 제한에 도달했습니다. 더 짧게 녹화해 주세요.');
          if (recorder?.state !== 'inactive') recorder.stop();
          return;
        }
        chunks.push(event.data);
        bytes += event.data.size;
      };
      recorder.onerror = event => {
        failure = event.error || new Error('카메라 녹화 중 오류가 발생했습니다.');
        if (recorder?.state !== 'inactive') recorder.stop();
        else { active = false; stopTracks(); rejectStop?.(failure); }
      };
      recorder.onstop = () => {
        const result = {
          blob: new Blob(chunks, { type: recorder.mimeType || chunks[0]?.type || 'video/webm' }),
          mimeType: recorder.mimeType || chunks[0]?.type || 'video/webm',
          durationMs: Date.now() - startedAt,
        };
        stopTracks();
        active = false;
        chunks = [];
        if (failure) {
          rejectStop?.(failure);
          callback?.({ error: failure });
        } else if (!result.blob.size) {
          const error = new Error('녹화된 영상이 없습니다. 다시 녹화해 주세요.');
          rejectStop?.(error);
          callback?.({ error });
        } else {
          lastResult = result;
          resolveStop?.(result);
          callback?.(result);
        }
        callback = undefined;
      };
      for (const track of stream.getTracks()) track.onended = () => { if (recorder?.state === 'recording') recorder.stop(); };
      recorder.start(1000);
      stopTimer = setTimeout(() => { if (recorder?.state === 'recording') recorder.stop(); }, 120000);
      onPreview?.(stream);
      return { stream, mimeType: recorder.mimeType, maxDurationMs: 120000 };
    } catch (error) {
      if (recorder?.state === 'recording' || recorder?.state === 'paused') recorder.stop();
      else active = false;
      stopTracks();
      if (error.name === 'NotAllowedError') throw new Error('카메라 권한이 허용되지 않았습니다. 영상 파일을 업로드할 수 있습니다.');
      if (error.name === 'NotFoundError') throw new Error('연결된 카메라를 찾을 수 없습니다. 영상 파일을 업로드해 주세요.');
      throw error;
    } finally { pending = false; }
  }

  async function stop() {
    if (recorder?.state === 'recording' || recorder?.state === 'paused') recorder.stop();
    if (stopPromise) return stopPromise;
    if (lastResult) return lastResult;
    throw new Error('진행 중인 녹화가 없습니다.');
  }

  function cleanup() {
    token++;
    callback = undefined;
    if (recorder?.state === 'recording' || recorder?.state === 'paused') recorder.stop();
    stopTracks();
  }

  return { start, stop, cleanup, getState };
}
