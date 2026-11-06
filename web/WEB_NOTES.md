# SCENIC web implementation notes

## Run and structure

Native browser ES modules; no build or external JavaScript dependency is required for the core experience. Serve `web/` over HTTP/HTTPS with the repository server. Opening `index.html` directly as a `file://` URL is not supported by browsers that block module imports; use the separately packaged standalone HTML for that case.

- `app.js`: five views, rendering, actions, route state, safe mode, browser GPS, camera/storage flows, API adapters.
- `styles.css`: responsive desktop/tablet/mobile layout, reduced motion support, system font fallback.
- `media.js`: original synthesized Web Audio scenes, personal audio files, browser camera MediaRecorder lifecycle.
- `storage.js`: committed IndexedDB transactions and guarded localStorage preferences.
- `sw.js`: same-origin app-shell cache; API calls, uploaded files and external map resources are not cached by this worker.
- `assets/`: original local vector landscape artwork and app icon. Illustrations do not depict verified views from the routes.

## Functional flows

1. **Explore:** departure region, strict time budget, preferred landscape, query search, recommendation sorting, saved courses, explanation of ranking. An optional OpenAI recommendation button sends only the disclosed preferences and filtered candidate course data after user action; it requires a server API key and shows source/model/reasons only after a successful response. Zero matches show an empty state instead of retaining a stale recommended hero.
2. **Course:** geographic scene-point diagram, optional OpenStreetMap/Leaflet online map, route points and rationale. Optional Kakao Mobility and TourAPI requests use `api.js`. Responses are guarded against changed route selection.
3. **Drive:** two-minute simulation, progress controls while stopped, optional explicit browser GPS request, scene-dependent original sound transitions. Simulation resets real routing geometry and uses the curated diagram. GPS markers use actual browser coordinates; route progress remains an explicitly labeled nearest-sample estimate and is withheld farther than 5 km from a sample route.
4. **Moments:** actual user video upload or explicit camera consent/recording, 100 MB limit, two-minute silent camera recording, IndexedDB persistence, stopped-only playback, local deletion, video download and provenance-rich JSON export. A first-frame color-statistics heuristic can suggest a sound mood; it is not a trained recognition model.
5. **Settings:** current versus unconnected capabilities, optional GPS metadata inclusion (off by default), automatic sound transitions, settings export, route provenance references.

No external media playback starts until a user action. Uploaded music stays selected across automatic landscape changes. The displayed active sound matches the actual audio-engine scene even with automatic switching disabled.

Camera permission requests and active recordings block navigation and drive entry. GPS requests lock editing immediately and use cancellation tokens. Leaving the page stops GPS, audio and camera tracks. The back-forward cache restore rerenders playback state; active recording triggers the browser's standard leave-page warning.

## Truth and safety boundaries

- Standalone web concept; no Pleos vehicle signal, car speed, in-vehicle camera, Spotify or production navigation integration. No AI model is bundled; optional OpenAI server-side recommendation is a real external API adapter. The default ranking remains deterministic and local.
- Course times, distances, scores and connecting lines are explicitly curated demonstration values. Actual drivable geometry requires the optional licensed routing API.
- Driver restriction is a user-controlled demonstration state, not proof of vehicle safety or motion status.
- Recordings never upload or share automatically. Browser storage can be cleared by the browser; export is the user's backup.
- Map tiles, Leaflet and optional web fonts require network access; local diagram, local artwork and system font fallbacks remain usable.
- Camera and geolocation require browser support and a secure context (HTTPS or localhost). Permission denial has an actionable message.

## Verification performed

- Syntax checks passed for every owned JavaScript module.
- Media helper camera lifecycle mock checks passed: manual stop resolves one Blob result, track release, cancellation while permission is pending, restart prevention while stopping.
- `node --test tests/web-smoke.test.mjs` preserves a reproducible DOM-contract mock test with 22 checks: all five views, shared ranking, driver locks, pending camera guards, immediate GPS lock, state reset, provenance, empty filtered hero, simulation geometry, stale route response rejection, back-forward cache recovery, and optional OpenAI response success/filter cancellation/drive cancellation/explicit cancellation. These mocks do not constitute real browser, media or live API validation.
- Actual browser visual rendering, browser video/audio codecs, physical camera, real GPS and external live APIs were **not verified** in this environment. Browser navigation policies prevented opening the packaged local demo; no workaround was attempted.

## Optional AI data boundary

The OpenAI button is user initiated. It sends selected region, scenery preference, time budget, search text and the already filtered public demonstration-course candidates to the server/OpenAI adapter. It sends no clip bytes, browser GPS fix, camera stream or video frame. Responses must reference the candidate IDs; the browser checks this again, escapes returned prose and never fabricates an AI score. A changed filter, selected course, cancellation or driving state invalidates the pending result. Missing keys and network errors remain explicit; deterministic local recommendations continue to work.
