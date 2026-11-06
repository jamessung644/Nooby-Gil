/* Kakao Maps Web SDK. Native Android supplies location; this page never requests browser GPS. */
(() => {
  'use strict';
  const MAX_ROAD_POINTS = 50000;
  let latestScene = null, renderedScene = null, map = null, ui = null;
  let layers = [], generation = 0, sdkKey = '', ready = false;
  const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
  const text = (value, max) => typeof value === 'string' && value.trim().length > 0 && value.length <= max;
  const coordinate = (lat, lon) => Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;

  function validateScene(input) {
    if (!object(input) || !text(input.courseId, 64) || !text(input.title, 200)
      || !Array.isArray(input.stops) || input.stops.length > 100
      || !['route', 'location'].includes(input.focus)
      || !Number.isInteger(input.active) || input.active < 0 || input.active >= Math.max(1, input.stops.length)
      || (input.focusRevision !== undefined && (!Number.isSafeInteger(input.focusRevision) || input.focusRevision < 0))) return null;
    const stops = [];
    for (const stop of input.stops) {
      if (!object(stop) || !text(stop.title, 200) || !coordinate(stop.lat, stop.lon)) return null;
      stops.push({ title: stop.title, lat: stop.lat, lon: stop.lon });
    }
    const road = input.roadCoordinates == null ? [] : input.roadCoordinates;
    if (!Array.isArray(road) || road.length === 1 || road.length > MAX_ROAD_POINTS) return null;
    const roadCoordinates = [];
    for (const point of road) {
      if (!Array.isArray(point) || point.length !== 2 || !coordinate(point[0], point[1])) return null;
      roadCoordinates.push([point[0], point[1]]);
    }
    let location = null;
    if (input.location != null) {
      const point = input.location;
      if (!object(point) || !coordinate(point.lat, point.lon)
        || (point.accuracy != null && (!Number.isFinite(point.accuracy) || point.accuracy < 0))) return null;
      location = { lat: point.lat, lon: point.lon, accuracy: point.accuracy ?? null };
    }
    return { courseId: input.courseId, title: input.title, stops, roadCoordinates, location,
      focus: input.focus, active: input.active, focusRevision: input.focusRevision };
  }

  // Installed synchronously, before DOMContentLoaded and native onPageFinished.
  window.scenicMap = Object.freeze({
    setScene(input) {
      const scene = validateScene(input);
      if (!scene) return false;
      latestScene = scene;
      if (ready) {
        try { renderScene(); }
        catch { showUnavailable('지도를 표시하지 못했습니다. 새로고침해 다시 연결해 주세요.'); }
      }
      return true;
    },
  });

  function clearLayers() {
    for (const layer of layers) layer.setMap(null);
    layers = [];
  }

  function viewport(scene) {
    const sdk = window.kakao.maps;
    if (scene.focus === 'location' && scene.location) {
      map.setCenter(new sdk.LatLng(scene.location.lat, scene.location.lon));
      map.setLevel(4);
      return;
    }
    const points = [...scene.stops.map(stop => [stop.lat, stop.lon]), ...scene.roadCoordinates];
    if (!points.length) return;
    const bounds = new sdk.LatLngBounds();
    for (const [lat, lon] of points) bounds.extend(new sdk.LatLng(lat, lon));
    map.setBounds(bounds, 48, 48, 48, 48);
  }

  function renderScene() {
    if (!map || !latestScene) return;
    const scene = latestScene, sdk = window.kakao.maps;
    clearLayers();
    ui.title.textContent = scene.title;
    ui.title.setAttribute('title', scene.title);
    const destinationRoute = scene.courseId.startsWith('destination-');
    ui.detail.textContent = destinationRoute
      ? (scene.roadCoordinates.length ? '선택한 목적지까지 · 조회한 도로 경로' : '출발지와 목적지')
      : (scene.roadCoordinates.length ? `명소 ${scene.stops.length}곳 · 조회한 도로 경로 표시` : `명소 ${scene.stops.length}곳 · 도로 경로 없음`);
    for (let i = 0; i < scene.stops.length; i++) {
      const stop = scene.stops[i], position = new sdk.LatLng(stop.lat, stop.lon);
      layers.push(new sdk.Marker({ map, position, title: `${i + 1}. ${stop.title}`, zIndex: i === scene.active ? 20 : 10 }));
      if (i === scene.active) {
        const label = document.createElement('div');
        label.className = 'stop-label';
        label.textContent = `${i + 1} · ${stop.title}`;
        layers.push(new sdk.CustomOverlay({ map, position, content: label, yAnchor: 2.2, zIndex: 25 }));
      }
    }
    // Only provider road geometry can become a line. Never connect curated stops.
    if (scene.roadCoordinates.length) layers.push(new sdk.Polyline({ map,
      path: scene.roadCoordinates.map(([lat, lon]) => new sdk.LatLng(lat, lon)),
      strokeWeight: 5, strokeColor: '#17191c', strokeOpacity: 0.9, strokeStyle: 'solid', zIndex: 5,
    }));
    if (scene.location) {
      const point = scene.location, position = new sdk.LatLng(point.lat, point.lon);
      if (point.accuracy !== null) layers.push(new sdk.Circle({ map, center: position, radius: point.accuracy,
        strokeWeight: 1, strokeColor: '#656970', strokeOpacity: 0.4, fillColor: '#656970', fillOpacity: 0.15, zIndex: 30,
      }));
      const dot = document.createElement('div');
      dot.className = 'current-location';
      dot.setAttribute('aria-label', 'Android에서 받은 현재 위치');
      layers.push(new sdk.CustomOverlay({ map, position, content: dot, xAnchor: 0.5, yAnchor: 0.5, zIndex: 40 }));
      ui.location.textContent = point.accuracy === null ? '현재 위치 · 정확도 미수신' : `현재 위치 · 정확도 약 ${Math.round(point.accuracy)}m`;
    } else ui.location.textContent = '현재 위치 미수신 · 선택한 경로 표시';
    // Optional native revision preserves the user's pan during ordinary GPS updates.
    if (!renderedScene || scene.focusRevision === undefined || renderedScene.courseId !== scene.courseId
      || renderedScene.focus !== scene.focus || renderedScene.focusRevision !== scene.focusRevision) viewport(scene);
    renderedScene = scene;
  }

  function showUnavailable(message) {
    ready = false;
    ui.canvas.hidden = true;
    ui.message.hidden = false;
    ui.messageTitle.textContent = '실제 지도가 아직 연결되지 않았어요';
    ui.messageDetail.textContent = message;
    ui.retry.disabled = false;
  }

  async function configuration() {
    const controller = new AbortController();
    let timer;
    try {
      return await Promise.race([
        (async () => {
          const response = await fetch('/api/map-config', { cache: 'no-store', credentials: 'same-origin', redirect: 'error', signal: controller.signal });
          if (!response.ok) throw new Error('configuration');
          const config = await response.json();
          if (!object(config) || typeof config.configured !== 'boolean' || typeof config.javascriptKey !== 'string'
            || config.javascriptKey.length > 256 || (config.configured && !config.javascriptKey.trim())) throw new Error('configuration');
          return config;
        })(),
        new Promise((_, reject) => { timer = setTimeout(() => { controller.abort(); reject(new Error('timeout')); }, 8000); }),
      ]);
    } finally { clearTimeout(timer); }
  }

  function loadSdk(key) {
    return new Promise((resolve, reject) => {
      let settled = false, script = null;
      const finish = (error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (script) { script.onload = null; script.onerror = null; if (error) script.remove(); }
        if (error) reject(new Error(`map-sdk:${error}`)); else resolve();
      };
      const timer = setTimeout(() => finish('timeout'), 30000);
      const load = () => {
        try {
          if (typeof window.kakao?.maps?.load !== 'function') return finish('missing-api');
          window.kakao.maps.load(() => finish(false));
        } catch { finish('initialization'); }
      };
      if (sdkKey === key && window.kakao?.maps?.load) return load();
      sdkKey = key;
      script = document.createElement('script');
      script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false`;
      script.async = true;
      script.onload = load;
      script.onerror = () => finish('network');
      document.head.appendChild(script);
    });
  }

  async function connect() {
    const attempt = ++generation;
    ready = false;
    ui.retry.disabled = true;
    ui.canvas.hidden = true;
    ui.message.hidden = false;
    ui.messageTitle.textContent = '지도를 준비하고 있어요';
    ui.messageDetail.textContent = '잠시만 기다려 주세요.';
    let config;
    try { config = await configuration(); }
    catch {
      if (attempt === generation) showUnavailable('지도 서버에 연결하지 못했습니다. 서버 실행 상태와 네트워크를 확인한 뒤 새로고침해 주세요.');
      return;
    }
    if (attempt !== generation) return;
    if (!config.configured) {
      showUnavailable('지도 연결 준비가 필요합니다.\nMac 서버의 .env에 KAKAO_MAP_JS_KEY(JavaScript 키)를 설정하고, Kakao에 이 페이지의 웹 도메인을 등록한 뒤 서버를 다시 시작해 주세요.');
      return;
    }
    // Kakao binds its SDK to an app key. Reload instead of reusing a different key.
    if (sdkKey && sdkKey !== config.javascriptKey && window.kakao) { window.location.reload(); return; }
    try {
      await loadSdk(config.javascriptKey);
      if (attempt !== generation) return;
      const sdk = window.kakao.maps;
      ui.canvas.hidden = false;
      ui.message.hidden = true;
      if (!map) {
        map = new sdk.Map(ui.canvas, { center: new sdk.LatLng(37.5665, 126.9780), level: 9 });
        map.addControl(new sdk.ZoomControl(), sdk.ControlPosition.RIGHT);
      } else map.relayout();
      renderedScene = null;
      ready = true;
      if (latestScene) renderScene();
      else ui.location.textContent = '기본 화면 · 현재 위치 아님';
      ui.retry.disabled = false;
    } catch (error) {
      const sdkFailure = typeof error?.message === 'string' && error.message.startsWith('map-sdk:');
      if (attempt === generation) showUnavailable(sdkFailure
        ? (error.message === 'map-sdk:timeout' ? '카카오 지도 응답 시간이 초과됐습니다. 네트워크를 확인한 뒤 지도 새로고침을 눌러 주세요.' : '카카오 지도 스크립트를 불러오지 못했습니다. 지도 키·등록 도메인·네트워크를 확인해 주세요.')
        : '카카오 지도를 화면에 표시하는 중 오류가 발생했습니다. 지도 새로고침을 눌러 주세요.');
      if (typeof console !== 'undefined' && typeof console.warn === 'function') console.warn('SCENIC_MAP', sdkFailure ? error.message : 'map-display', String(error?.name || 'Error'));
    }
  }

  function start() {
    ui = Object.fromEntries(Object.entries({ title: 'map-title', detail: 'map-detail', canvas: 'map-canvas', message: 'map-message',
      messageTitle: 'map-message-title', messageDetail: 'map-message-detail', location: 'map-location', retry: 'map-retry' })
      .map(([key, id]) => [key, document.getElementById(id)]));
    ui.retry.addEventListener('click', connect);
    const relayout = () => { if (ready && map) map.relayout(); };
    if (typeof ResizeObserver === 'function') new ResizeObserver(relayout).observe(ui.canvas);
    else window.addEventListener('resize', relayout);
    connect();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start, { once: true });
  else start();
})();
