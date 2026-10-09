// Synthetic Media Session usage, as Spotify's player would do it (DESIGN §4 H4, native half).
// Usage: node tools/cdp-eval.mjs <port> "$(cat tools/h4-session.js)"
(() => {
  window.__h4calls = [];
  const s = navigator.mediaSession;
  s.metadata = new MediaMetadata({
    title: 'PoC Test', artist: 'Bridge', album: 'H4',
    artwork: [{ src: 'https://i.scdn.co/image/ab67616d0000b273d8601e15fa1b4351fe1fc6ae', sizes: '640x640', type: 'image/jpeg' }],
  });
  s.playbackState = 'playing';
  s.setPositionState({ duration: 200, position: 10, playbackRate: 1 });
  for (const a of ['play', 'pause', 'nexttrack', 'previoustrack', 'seekto']) {
    s.setActionHandler(a, (d) => window.__h4calls.push(JSON.stringify(d)));
  }
  return { session: Object.prototype.toString.call(s), handlers: 'registered' };
})()
