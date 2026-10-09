// Conformance test for app/src/main/assets/media-bridge.js on the host.
// Run: node --test tools/test/*.test.mjs
//
// The script is loaded, unmodified, into a `vm` context that has minimal fakes for the browser pieces it
// touches (window === top, Navigator/navigator, HTMLMediaElement, document, OpenfyBridge).
// Test code that touches page-visible objects runs *inside* the context, so Errors and arrays have the
// context's prototypes (as in a browser) and `instanceof` checks in the script behave as on a page.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const SCRIPT = fs.readFileSync(
  path.join(path.dirname(fileURLToPath(import.meta.url)), '../../app/src/main/assets/media-bridge.js'),
  'utf8',
);

const FAKE_PAGE = `
  globalThis.window = globalThis;
  globalThis.top = globalThis;
  globalThis.Navigator = function Navigator() {};
  globalThis.navigator = new Navigator();
  navigator.userAgent = 'test-agent';
  navigator.platform = 'Linux';

  const listeners = {};
  globalThis.addEventListener = (type, fn) => { (listeners[type] ||= []).push(fn); };
  globalThis.fire = (type) => (listeners[type] || []).forEach((fn) => fn({ type }));

  const docListeners = [];
  globalThis.document = {
    baseURI: 'https://open.spotify.com/album/x',
    visibilityState: 'visible',
    inDom: [],
    addEventListener(type, fn, capture) { docListeners.push({ type, fn, capture }); },
    querySelectorAll() { return this.inDom; },
  };

  // A media element whose events dispatch like the browser's: document capture first (only when it is
  // in the document), then the element's own listeners.
  globalThis.HTMLMediaElement = class HTMLMediaElement {
    constructor() {
      this._l = {}; this._src = ''; this._srcObject = null;
      this.paused = true; this.ended = false; this.currentTime = 0; this.duration = NaN; this.playbackRate = 1;
    }
    play() { this.paused = false; this._dispatch('play'); return Promise.resolve(); }
    pause() { this.paused = true; this._dispatch('pause'); }
    load() {}
    get src() { return this._src; }
    set src(v) { this._src = v; }
    get srcObject() { return this._srcObject; }
    set srcObject(v) { this._srcObject = v; }
    addEventListener(type, fn) { (this._l[type] ||= []).push(fn); }
    _dispatch(type) {
      const e = { type, target: this };
      if (document.inDom.includes(this)) {
        docListeners.filter((l) => l.type === type && l.capture).forEach((l) => l.fn(e));
      }
      (this._l[type] || []).forEach((fn) => fn(e));
    }
  };

  globalThis.screen = { width: 360, height: 800 };
  globalThis.location = { href: 'https://open.spotify.com/' };
  globalThis.devicePixelRatio = 2; globalThis.innerWidth = 360; globalThis.innerHeight = 700;
`;

function makePage({ nativeMediaSession = false } = {}) {
  const messages = [];
  const consoleErrors = [];
  const sandbox = {
    URL,
    performance,
    console: { error: (...a) => consoleErrors.push(a) },
    OpenfyBridge: { postMessage: (m) => messages.push(JSON.parse(m)) },
  };
  const ctx = vm.createContext(sandbox);
  // Each snippet gets its own block scope so `const` names can be reused between snippets.
  const ev = (code) => vm.runInContext(`{\n${code}\n}`, ctx);
  ev(FAKE_PAGE);
  if (nativeMediaSession) {
    // What WebView would look like if it shipped the API: record the "original" calls.
    ev(`
      globalThis.nativeCalls = [];
      globalThis.MediaSession = class MediaSession {
        get metadata() { return this._m ?? null; }
        set metadata(v) { nativeCalls.push(['metadata', v]); this._m = v; }
        get playbackState() { return this._s ?? 'none'; }
        set playbackState(v) { nativeCalls.push(['playbackState', v]); this._s = v; }
        setActionHandler(a, h) { nativeCalls.push(['setActionHandler', a]); }
        setPositionState(s) { nativeCalls.push(['setPositionState', s]); }
      };
      globalThis.MediaMetadata = class MediaMetadata { constructor(i) { Object.assign(this, i); } };
      const session = new MediaSession();
      Object.defineProperty(Navigator.prototype, 'mediaSession', { get() { return session; }, configurable: true, enumerable: true });
    `);
  }
  const load = () => vm.runInContext(SCRIPT, ctx, { filename: 'media-bridge.js' });
  return { ctx, ev, load, messages, consoleErrors, of: (t) => messages.filter((m) => m.t === t) };
}

/** Runs `code` in the page and returns the thrown error's name and message, or null. */
function thrown(page, code) {
  try {
    page.ev(code);
    return null;
  } catch (e) {
    return { name: e.name, message: e.message };
  }
}

function polyfillPage() {
  const page = makePage();
  page.load();
  return page;
}

test('install: polyfill is chosen when WebView has no Media Session API', () => {
  const page = polyfillPage();
  assert.equal(page.ev(`'mediaSession' in navigator`), true);
  assert.equal(page.ev(`typeof MediaMetadata`), 'function');
  assert.equal(page.ev(`typeof MediaSession`), 'function');
  const [install] = page.of('install');
  assert.equal(install.session, 'polyfill');
  assert.deepEqual(install.wrapped, ['MediaMetadata', 'MediaSession', 'Navigator.prototype.mediaSession']);
  assert.equal(install.elements, 0);
});

test('only the top frame installs anything', () => {
  const page = makePage();
  page.ev(`globalThis.top = {}`);
  page.load();
  assert.equal(page.ev(`'mediaSession' in navigator`), false);
  assert.equal(page.ev(`typeof __osShell`), 'undefined');
  assert.equal(page.messages.length, 0);
});

test('MediaMetadata: constructor, defaults and fields', () => {
  const page = polyfillPage();
  assert.equal(page.ev(`MediaMetadata.length`), 0);
  assert.equal(page.ev(`Object.prototype.toString.call(new MediaMetadata())`), '[object MediaMetadata]');
  assert.deepEqual(
    JSON.parse(page.ev(`const d = new MediaMetadata(); JSON.stringify([d.title, d.artist, d.album, d.artwork])`)),
    ['', '', '', []],
  );
  assert.deepEqual(
    JSON.parse(page.ev(`const m = new MediaMetadata({title: 'T', artist: null, album: 5}); JSON.stringify([m.title, m.artist, m.album])`)),
    ['T', 'null', '5'], // WebIDL DOMString conversion: String(v)
  );
  // Attributes are accessors on the prototype, not own data properties.
  assert.equal(page.ev(`Object.getOwnPropertyNames(new MediaMetadata({title: 'x'})).length`), 0);
  assert.equal(
    page.ev(`const d = Object.getOwnPropertyDescriptor(MediaMetadata.prototype, 'title'); d.enumerable && d.configurable && !!d.get && !!d.set`),
    true,
  );
  // Called as a function, and brand checks.
  assert.equal(thrown(page, `MediaMetadata()`)?.name, 'TypeError');
  assert.match(thrown(page, `MediaMetadata()`).message, /Please use the 'new' operator/);
  assert.equal(thrown(page, `Object.getOwnPropertyDescriptor(MediaMetadata.prototype, 'title').get.call({})`)?.message, 'Illegal invocation');
  // Native interface objects have a non-writable `prototype`.
  assert.equal(page.ev(`Object.getOwnPropertyDescriptor(MediaMetadata, 'prototype').writable`), false);
});

test('MediaMetadata: artwork URLs are resolved against the base URI and frozen', () => {
  const page = polyfillPage();
  const out = JSON.parse(page.ev(`
    const m = new MediaMetadata({artwork: [
      {src: '/image/abc', sizes: '640x640', type: 'image/jpeg'},
      {src: 'https://i.scdn.co/image/def'},
    ]});
    JSON.stringify(m.artwork)`));
  assert.deepEqual(out, [
    { src: 'https://open.spotify.com/image/abc', sizes: '640x640', type: 'image/jpeg' },
    { src: 'https://i.scdn.co/image/def', sizes: '', type: '' },
  ]);
  assert.equal(page.ev(`const a = new MediaMetadata({artwork: [{src: 'x'}]}); Object.isFrozen(a.artwork) && Object.isFrozen(a.artwork[0])`), true);
  assert.equal(page.ev(`const a = new MediaMetadata({artwork: [{src: 'x'}]}); a.artwork === a.artwork`), true);
  assert.match(thrown(page, `new MediaMetadata({artwork: [{sizes: '1x1'}]})`).message, /required member src is undefined/);
  assert.equal(thrown(page, `new MediaMetadata({artwork: [{src: 'http://'}]})`)?.name, 'TypeError');
  assert.equal(thrown(page, `new MediaMetadata({artwork: 5})`)?.name, 'TypeError');
  assert.equal(thrown(page, `new MediaMetadata({artwork: ['x']})`)?.name, 'TypeError');
  assert.equal(thrown(page, `new MediaMetadata(5)`)?.name, 'TypeError');
});

test('MediaSession: no public constructor, brand checks, toStringTag', () => {
  const page = polyfillPage();
  assert.equal(thrown(page, `new MediaSession()`)?.message, 'Illegal constructor');
  assert.equal(thrown(page, `MediaSession()`)?.name, 'TypeError');
  assert.equal(page.ev(`Object.prototype.toString.call(navigator.mediaSession)`), '[object MediaSession]');
  assert.equal(page.ev(`Object.getOwnPropertyDescriptor(MediaSession, 'prototype').writable`), false);
  assert.equal(page.ev(`MediaSession.prototype.setActionHandler.length`), 2);
  assert.equal(page.ev(`MediaSession.prototype.setPositionState.length`), 0);
  assert.equal(page.ev(`MediaSession.prototype.setMicrophoneActive.length`), 1);
  assert.equal(page.ev(`MediaSession.prototype.setCameraActive.length`), 1);
  assert.equal(thrown(page, `MediaSession.prototype.setActionHandler.call({}, 'play', null)`)?.message, 'Illegal invocation');
});

test('navigator.mediaSession is a [SameObject] accessor on Navigator.prototype', () => {
  const page = polyfillPage();
  assert.equal(page.ev(`navigator.mediaSession === navigator.mediaSession`), true);
  assert.equal(page.ev(`navigator.hasOwnProperty('mediaSession')`), false);
  assert.equal(
    page.ev(`const d = Object.getOwnPropertyDescriptor(Navigator.prototype, 'mediaSession'); d.enumerable && d.configurable && !!d.get && d.set === undefined`),
    true,
  );
  assert.equal(page.ev(`navigator.mediaSession instanceof MediaSession`), true);
  assert.equal(
    thrown(page, `Object.getOwnPropertyDescriptor(Navigator.prototype, 'mediaSession').get.call({})`)?.message,
    'Illegal invocation',
  );
  // Another Navigator gets its own session.
  assert.equal(page.ev(`new Navigator().mediaSession !== navigator.mediaSession`), true);
});

test('metadata: assignment posts, null clears, bound metadata re-posts on change', () => {
  const page = polyfillPage();
  assert.equal(page.ev(`navigator.mediaSession.metadata`), null);
  page.ev(`navigator.mediaSession.metadata = new MediaMetadata({
    title: 'PoC Test', artist: 'Bridge', album: 'H4',
    artwork: [{src: 'https://i.scdn.co/image/a', sizes: '640x640', type: 'image/jpeg'}]})`);
  assert.deepEqual(page.of('metadata').at(-1), {
    t: 'metadata', title: 'PoC Test', artist: 'Bridge', album: 'H4',
    artwork: [{ src: 'https://i.scdn.co/image/a', sizes: '640x640', type: 'image/jpeg' }],
  });
  // Session binding (Blink's MediaMetadata::NotifySession): later attribute sets are observed.
  page.ev(`navigator.mediaSession.metadata.title = 'Renamed'`);
  assert.equal(page.of('metadata').at(-1).title, 'Renamed');
  page.ev(`navigator.mediaSession.metadata.artwork = [{src: '/z.png'}]`);
  assert.equal(page.of('metadata').at(-1).artwork[0].src, 'https://open.spotify.com/z.png');
  // Unbound metadata does not post.
  const before = page.messages.length;
  page.ev(`const loose = new MediaMetadata({title: 'x'}); loose.title = 'y'`);
  assert.equal(page.messages.length, before);
  // Replacing unbinds the old object.
  page.ev(`globalThis.old = navigator.mediaSession.metadata; navigator.mediaSession.metadata = new MediaMetadata({title: 'Next'})`);
  const afterReplace = page.messages.length;
  page.ev(`old.title = 'ghost'`);
  assert.equal(page.messages.length, afterReplace);
  // null and undefined clear.
  page.ev(`navigator.mediaSession.metadata = null`);
  assert.deepEqual(page.of('metadata').at(-1), { t: 'metadata', title: '', artist: '', album: '', artwork: [] });
  assert.equal(page.ev(`navigator.mediaSession.metadata`), null);
  page.ev(`navigator.mediaSession.metadata = undefined`);
  assert.equal(page.ev(`navigator.mediaSession.metadata`), null);
  // Anything that is not a MediaMetadata is a TypeError.
  assert.equal(thrown(page, `navigator.mediaSession.metadata = {title: 'plain'}`)?.name, 'TypeError');
});

test('playbackState: enum values are stored and posted, invalid values are ignored silently', () => {
  const page = polyfillPage();
  assert.equal(page.ev(`navigator.mediaSession.playbackState`), 'none');
  page.ev(`navigator.mediaSession.playbackState = 'playing'`);
  assert.deepEqual(page.of('state').at(-1), { t: 'state', declared: 'playing' });
  assert.equal(page.ev(`navigator.mediaSession.playbackState`), 'playing');
  const n = page.of('state').length;
  assert.equal(thrown(page, `navigator.mediaSession.playbackState = 'buffering'`), null);
  assert.equal(page.ev(`navigator.mediaSession.playbackState`), 'playing');
  assert.equal(page.of('state').length, n);
  page.ev(`navigator.mediaSession.playbackState = 'paused'`);
  assert.equal(page.ev(`navigator.mediaSession.playbackState`), 'paused');
});

test('setActionHandler: validates the action and the callback, null unregisters', () => {
  const page = polyfillPage();
  page.ev(`navigator.mediaSession.setActionHandler('nexttrack', () => {}); navigator.mediaSession.setActionHandler('seekto', () => {})`);
  assert.deepEqual(page.of('handlers').at(-1), { t: 'handlers', actions: ['nexttrack', 'seekto'] });
  page.ev(`navigator.mediaSession.setActionHandler('nexttrack', null)`);
  assert.deepEqual(page.of('handlers').at(-1), { t: 'handlers', actions: ['seekto'] });
  page.ev(`navigator.mediaSession.setActionHandler('seekto', undefined)`);
  assert.deepEqual(page.of('handlers').at(-1), { t: 'handlers', actions: [] });

  const bad = thrown(page, `navigator.mediaSession.setActionHandler('explode', () => {})`);
  assert.equal(bad.name, 'TypeError');
  assert.match(bad.message, /'explode' is not a valid enum value of type MediaSessionAction/);
  assert.equal(thrown(page, `navigator.mediaSession.setActionHandler('play', 'not a function')`)?.name, 'TypeError');
  assert.equal(thrown(page, `navigator.mediaSession.setActionHandler('play')`)?.name, 'TypeError');
  // Every Chrome 124 action is accepted.
  const actions = ['play', 'pause', 'seekbackward', 'seekforward', 'previoustrack', 'nexttrack', 'skipad', 'stop',
    'seekto', 'togglemicrophone', 'togglecamera', 'hangup', 'previousslide', 'nextslide', 'enterpictureinpicture'];
  for (const a of actions) assert.equal(thrown(page, `navigator.mediaSession.setActionHandler('${a}', null)`), null, a);
});

test('setPositionState: validation and posting', () => {
  const page = polyfillPage();
  page.ev(`navigator.mediaSession.setPositionState({duration: 200, position: 10, playbackRate: 1})`);
  assert.deepEqual(page.of('position').at(-1), { t: 'position', position: 10, duration: 200, rate: 1 });
  // Defaults: position 0, playbackRate 1.
  page.ev(`navigator.mediaSession.setPositionState({duration: 30})`);
  assert.deepEqual(page.of('position').at(-1), { t: 'position', position: 0, duration: 30, rate: 1 });
  // Infinity duration is accepted (live streams); JSON turns it into null, which native treats as unknown.
  assert.equal(thrown(page, `navigator.mediaSession.setPositionState({duration: Infinity, position: 5})`), null);
  // No argument or an empty dictionary clears.
  page.ev(`navigator.mediaSession.setPositionState()`);
  assert.deepEqual(page.of('position').at(-1), { t: 'position', position: null, duration: null, rate: 1 });
  page.ev(`navigator.mediaSession.setPositionState({duration: 9}); navigator.mediaSession.setPositionState({})`);
  assert.equal(page.of('position').at(-1).duration, null);

  const cases = [
    [`{position: 1}`, /required member duration is undefined/],
    [`{duration: NaN}`, /duration cannot be NaN/],
    [`{duration: -1}`, /duration cannot be less than zero/],
    [`{duration: 10, position: -1}`, /position cannot be less than zero/],
    [`{duration: 10, position: 11}`, /position cannot be greater than the duration/],
    [`{duration: 10, playbackRate: 0}`, /playbackRate cannot be equal to zero/],
    [`{duration: 10, position: Infinity}`, /non-finite/],
    [`{duration: 10, playbackRate: NaN}`, /non-finite/],
  ];
  for (const [arg, re] of cases) {
    const before = page.of('position').length;
    const e = thrown(page, `navigator.mediaSession.setPositionState(${arg})`);
    assert.equal(e?.name, 'TypeError', arg);
    assert.match(e.message, re, arg);
    assert.equal(page.of('position').length, before, `${arg} must not post`);
  }
  assert.equal(thrown(page, `navigator.mediaSession.setPositionState(5)`)?.name, 'TypeError');
  // Negative playback rates are allowed (reverse playback).
  assert.equal(thrown(page, `navigator.mediaSession.setPositionState({duration: 10, playbackRate: -1})`), null);
});

test('setMicrophoneActive / setCameraActive exist and are no-ops', () => {
  const page = polyfillPage();
  const n = page.messages.length;
  assert.equal(page.ev(`navigator.mediaSession.setMicrophoneActive(true)`), undefined);
  assert.equal(page.ev(`navigator.mediaSession.setCameraActive(false)`), undefined);
  assert.equal(page.messages.length, n);
  assert.equal(thrown(page, `navigator.mediaSession.setCameraActive()`)?.name, 'TypeError');
});

test('__osShell.invoke calls the registered handler with MediaSessionActionDetails', () => {
  const page = polyfillPage();
  page.ev(`
    globalThis.calls = [];
    navigator.mediaSession.setActionHandler('nexttrack', (d) => calls.push(d));
    navigator.mediaSession.setActionHandler('seekto', (d) => calls.push(d));
  `);
  assert.equal(page.ev(`__osShell.invoke('nexttrack')`), 'handler');
  assert.equal(page.ev(`__osShell.invoke('seekto', {seekTime: 42.5})`), 'handler');
  assert.deepEqual(JSON.parse(page.ev(`JSON.stringify(calls)`)), [
    { action: 'nexttrack' },
    { action: 'seekto', seekTime: 42.5, fastSeek: false },
  ]);
  // No handler, no element: nothing to fall back to.
  assert.equal(page.ev(`__osShell.invoke('previoustrack')`), 'none');
  // A throwing handler is reported, not propagated.
  page.ev(`navigator.mediaSession.setActionHandler('stop', () => { throw new RangeError('boom'); })`);
  assert.equal(page.ev(`__osShell.invoke('stop')`), 'error');
  assert.equal(page.of('error').at(-1).name, 'RangeError');
});

test('media elements: a detached element is discovered through prototype hooks', () => {
  const page = polyfillPage();
  assert.deepEqual(page.of('install')[0].hooks.sort(), ['load', 'pause', 'play', 'src', 'srcObject']);
  page.ev(`globalThis.el = new HTMLMediaElement(); el.src = 'blob:x';`); // never appended to the document
  assert.equal(page.ev(`document.inDom.length`), 0);
  page.ev(`el.play()`);
  const media = page.of('media');
  assert.deepEqual(media.map((m) => m.ev), ['play']);
  assert.equal(media[0].paused, false);
  // Fallback: no pause handler registered, so invoke() pauses the tracked element.
  assert.equal(page.ev(`__osShell.invoke('pause')`), 'element');
  assert.equal(page.ev(`el.paused`), true);
  assert.equal(page.ev(`__osShell.invoke('play')`), 'element');
  assert.equal(page.ev(`el.paused`), false);
  assert.deepEqual(page.of('media').map((m) => m.ev), ['play', 'pause', 'play']);
});

test('media elements: one message per event when the element is also in the document', () => {
  const page = polyfillPage();
  page.ev(`globalThis.el = new HTMLMediaElement(); document.inDom.push(el); el.play()`);
  assert.deepEqual(page.of('media').map((m) => m.ev), ['play']);
});

test('media elements: pagehide tells native nothing is playing', () => {
  const page = polyfillPage();
  page.ev(`fire('pagehide')`);
  const m = page.of('media').at(-1);
  assert.equal(m.ev, 'pagehide');
  assert.equal(m.paused, true);
});

test('diag reports session mode and the observed element count', async () => {
  const page = polyfillPage();
  page.ev(`navigator.requestMediaKeySystemAccess = () => Promise.resolve({})`);
  page.ev(`globalThis.el = new HTMLMediaElement(); el.load()`);
  await page.ev(`__osShell.diag()`);
  const d = page.of('diag')[0];
  assert.equal(d.session, 'polyfill');
  assert.equal(d.elements, 1);
  assert.equal(d.bridgeInstalled, true);
  assert.equal(d.widevine, true);
  assert.equal('sessionWrapped' in d, false);
});

test('idempotency: loading the script twice does not wrap or redefine anything', () => {
  const page = makePage();
  page.load();
  const snapshot = () => page.ev(`[
    MediaSession, MediaMetadata, __osShell,
    Object.getOwnPropertyDescriptor(Navigator.prototype, 'mediaSession').get,
    HTMLMediaElement.prototype.play, HTMLMediaElement.prototype.pause, HTMLMediaElement.prototype.load,
    Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src').set,
  ]`);
  const first = snapshot();
  const count = page.messages.length;
  page.load();
  const second = snapshot();
  first.forEach((v, i) => assert.equal(second[i], v, `entry ${i} changed`));
  assert.equal(page.messages.length, count, 'second load must not post');
  // And the media hooks wrap play() only once: one event per play().
  page.ev(`new HTMLMediaElement().play()`);
  assert.equal(page.of('media').length, 1);
});

test('wrapped-native: with a native API the original runs first and nothing is polyfilled', () => {
  const page = makePage({ nativeMediaSession: true });
  const nativeCtor = page.ev(`MediaSession`);
  page.load();
  assert.equal(page.ev(`MediaSession`), nativeCtor);
  const install = page.of('install')[0];
  assert.equal(install.session, 'wrapped-native');
  assert.deepEqual(install.wrapped, ['metadata', 'playbackState', 'setActionHandler', 'setPositionState']);

  page.ev(`
    const s = navigator.mediaSession;
    s.metadata = new MediaMetadata({title: 'Native', artist: 'A', album: 'B', artwork: [{src: 'https://x/y', sizes: '1x1', type: ''}]});
    s.playbackState = 'playing';
    s.setActionHandler('pause', () => {});
    s.setPositionState({duration: 10, position: 1, playbackRate: 1});
  `);
  assert.deepEqual(JSON.parse(page.ev(`JSON.stringify(nativeCalls.map((c) => c[0]))`)),
    ['metadata', 'playbackState', 'setActionHandler', 'setPositionState']);
  assert.equal(page.of('metadata').at(-1).title, 'Native');
  assert.deepEqual(page.of('state').at(-1), { t: 'state', declared: 'playing' });
  assert.deepEqual(page.of('handlers').at(-1), { t: 'handlers', actions: ['pause'] });
  assert.equal(page.of('position').at(-1).duration, 10);
  assert.equal(page.ev(`navigator.mediaSession.metadata.title`), 'Native'); // getters still work
  assert.equal(page.ev(`__osShell.invoke('pause')`), 'handler');
});

test('session is "missing" when only half of the API exists', () => {
  const page = makePage();
  page.ev(`globalThis.MediaMetadata = function MediaMetadata() {}`); // no MediaSession, but not "absent" either
  page.ev(`Object.defineProperty(Navigator.prototype, 'mediaSession', { get() { return {}; }, configurable: true })`);
  page.load();
  assert.equal(page.of('install')[0].session, 'missing');
  assert.equal(page.of('error')[0].name, 'NoMediaSession');
});

test('post failures are logged, not thrown', () => {
  const page = makePage();
  page.ev(`OpenfyBridge.postMessage = () => { throw new Error('closed'); }`);
  // The script captured OpenfyBridge at load, so break the stub before loading.
  assert.doesNotThrow(() => page.load());
  assert.ok(page.consoleErrors.length > 0);
});
