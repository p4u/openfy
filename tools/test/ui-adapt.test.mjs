// Host tests for app/src/main/assets/ui-adapt.js (DESIGN §2.9) and the shipped ui-rules.json.
// Run: node --test tools/test/*.test.mjs   (Node 22 does not expand a bare directory argument)
//
// The script is loaded, unmodified, into a `vm` context. With the rules placeholder left as `null` it only
// exports its pure core through the test hook; for the install smoke test the placeholder is replaced the same
// way native code does it. Small fakes stand in for the DOM.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const ASSETS = path.join(path.dirname(fileURLToPath(import.meta.url)), '../../app/src/main/assets');
const SCRIPT = fs.readFileSync(path.join(ASSETS, 'ui-adapt.js'), 'utf8');
const SHIPPED_RULES = fs.readFileSync(path.join(ASSETS, 'ui-rules.json'), 'utf8');
const PLACEHOLDER = '/*__OS_ADAPT_RULES__*/null';

/** Objects created inside the vm have other prototypes; compare them as plain JSON. */
const plain = (x) => JSON.parse(JSON.stringify(x));

function loadCore() {
  const sandbox = { __OS_ADAPT_TEST__: {} };
  vm.runInContext(SCRIPT, vm.createContext(sandbox), { filename: 'ui-adapt.js' });
  return sandbox.__OS_ADAPT_TEST__.core;
}
const core = loadCore();

const rule = (over = {}) => ({ id: 'r', tier: 1, action: 'hide', selectors: ['[data-testid="x"]'], ...over });
const parse = (rules) => plain(core.parseRules({ version: 1, rules }));

// ---------------------------------------------------------------------------------------------
// rule parsing and validation
// ---------------------------------------------------------------------------------------------

test('the script has exactly one rules placeholder (native templating relies on it)', () => {
  assert.equal(SCRIPT.split(PLACEHOLDER).length - 1, 1);
});

test('parseRules accepts a valid hide and a valid dismiss rule', () => {
  const r = parse([
    rule(),
    rule({ id: 'd', action: 'dismiss', dismissSelectors: ['[data-testid="close-button"]'] }),
  ]);
  assert.deepEqual(r.errors, []);
  assert.deepEqual(r.rules.map((x) => x.id), ['r', 'd']);
});

test('parseRules accepts a JSON string', () => {
  const r = plain(core.parseRules(JSON.stringify({ rules: [rule()] })));
  assert.equal(r.rules.length, 1);
  assert.deepEqual(plain(core.parseRules('{oops')).errors, [{ id: null, error: 'invalid JSON' }]);
});

test('parseRules rejects hashed-looking class names', () => {
  for (const sel of [
    '.dQr6UEoxMhIjxX_bjErq',                 // Spotify's CSS-module hashes
    'div.vMTX2R1FN3IVfjp7a_mx > button',
    '.e-10860-legacy-button',                // encore build prefix + digits
    '[data-testid="x"] .Hi1q48g6TdYQ7xOi5Ssa',
    '.encore-text-body-small-bold2',
  ]) {
    const r = parse([rule({ selectors: ['[data-testid="ok"]', sel] })]);
    assert.equal(r.rules.length, 0, sel);
    assert.match(r.errors[0].error, /hashed-looking class/, sel);
  }
});

test('parseRules rejects [class...] attribute selectors', () => {
  for (const sel of ['[class*="premium"]', 'div[class^=banner]', '[ class ~= x ]']) {
    const r = parse([rule({ selectors: [sel] })]);
    assert.equal(r.rules.length, 0, sel);
    assert.match(r.errors[0].error, /class attribute/, sel);
  }
});

test('parseRules allows dots inside attribute values and plain lowercase class words', () => {
  const sels = [
    'a[href*="spotify.link"]',
    '[role="dialog"]:has(a[href*="play.google.com/store"]):not(#onetrust-consent-sdk *)',
    '[data-testid$="-hook"]:has([data-encore-id="buttonPrimary"])',
    '.encore-dark-theme',
    "a[href*='apps.apple.com']",
  ];
  const r = parse([rule({ selectors: sels })]);
  assert.deepEqual(r.errors, []);
});

test('parseRules rejects malformed rules one by one and keeps the good ones', () => {
  const r = parse([
    rule({ id: 'good' }),
    rule({ id: 'bad-tier', tier: 3 }),
    rule({ id: 'bad-action', action: 'block' }),
    rule({ id: 'no-selectors', selectors: [] }),
    rule({ id: 'empty-selector', selectors: ['  '] }),
    rule({ id: 'Bad_ID' }),
    rule({ id: 'good' }),                                                // duplicate
    rule({ id: 'dismiss-without-button', action: 'dismiss' }),
    rule({ id: 'hide-with-button', dismissSelectors: ['button'] }),
    rule({ id: 'labels-without-selector', action: 'dismiss', dismissSelectors: ['button'], labels: ['x'] }),
    null,
  ]);
  assert.deepEqual(r.rules.map((x) => x.id), ['good']);
  assert.equal(r.errors.length, 10);
  assert.equal(r.errors.find((e) => e.id === 'good').error, 'duplicate id');
});

test('parseRules rejects a document without a rules array', () => {
  assert.equal(plain(core.parseRules({})).rules.length, 0);
  assert.equal(plain(core.parseRules(null)).rules.length, 0);
});

test('the shipped ui-rules.json is valid and never selects by hashed class', () => {
  const parsed = plain(core.parseRules(SHIPPED_RULES));
  assert.deepEqual(parsed.errors, []);
  const byId = Object.fromEntries(parsed.rules.map((r) => [r.id, r]));
  assert.deepEqual(Object.keys(byId).sort(), ['get-app-dialog', 'get-app-popover', 'open-app-button', 'premium-banner']);
  assert.equal(byId['open-app-button'].tier, 1);
  assert.equal(byId['premium-banner'].tier, 2);
  assert.equal(byId['open-app-button'].selectors[0], 'button[data-testid="download-button"]'); // testid first
  assert.equal(byId['premium-banner'].selectors.some((s) => s.includes('[data-testid="premium-banner"]')), true);
  // The text gate exists only on the popover rule and is a lowercased label set.
  assert.ok(byId['get-app-popover'].labels.includes('get app'));
  assert.ok(byId['get-app-popover'].labels.length > 50);
  // Consent dialogs are explicitly excluded from the dialog fallback.
  assert.ok(byId['get-app-dialog'].selectors.every((s) => s.includes(':not(#onetrust-consent-sdk *)')));
});

test('buildHideCss emits one CSS rule per hide selector and nothing for dismiss rules', () => {
  const { rules } = core.parseRules({
    rules: [
      rule({ id: 'h', selectors: ['a', 'b'] }),
      rule({ id: 'd', action: 'dismiss', dismissSelectors: ['button'] }),
    ],
  });
  assert.deepEqual(plain(core.buildHideCss(rules)), [
    { rule: 'h', sel: 0, css: 'a{display:none !important}' },
    { rule: 'h', sel: 1, css: 'b{display:none !important}' },
  ]);
});

// ---------------------------------------------------------------------------------------------
// bundle name
// ---------------------------------------------------------------------------------------------

test('bundleFromUrls finds the hashed player bundle and ignores vendor chunks', () => {
  const urls = [
    'https://open.spotifycdn.com/cdn/build/mobile-web-player/vendor~mobile-web-player.04a3b031.js',
    'https://open.spotifycdn.com/cdn/build/mobile-web-player/mwp-home-page.9269ffa4.js',
    'https://open.spotifycdn.com/cdn/build/mobile-web-player/mobile-web-player.fe7b9205.js?x=1',
  ];
  assert.equal(core.bundleFromUrls(urls), 'mobile-web-player.fe7b9205.js');
  assert.equal(core.bundleFromUrls(['https://open.spotifycdn.com/cdn/build/web-player/web-player.0a1b2c3d.js']), 'web-player.0a1b2c3d.js');
  assert.equal(core.bundleFromUrls(['https://example.com/app.js']), null);
  assert.equal(core.bundleFromUrls([]), null);
});

// ---------------------------------------------------------------------------------------------
// coalescing scheduler
// ---------------------------------------------------------------------------------------------

function fakeClock() {
  const c = { t: 0, frames: [], timers: [] };
  c.deps = {
    raf: (cb) => c.frames.push(cb),
    now: () => c.t,
    setTimer: (cb, ms) => c.timers.push({ at: c.t + ms, cb }),
  };
  c.frame = () => { const f = c.frames.splice(0); f.forEach((cb) => cb()); };
  c.advance = (ms) => {
    c.t += ms;
    const due = c.timers.filter((x) => x.at <= c.t);
    c.timers = c.timers.filter((x) => x.at > c.t);
    due.forEach((x) => x.cb());
  };
  return c;
}

test('scheduler: many schedule() calls before a frame produce one run', () => {
  const clock = fakeClock();
  let runs = 0;
  const s = core.makeScheduler(clock.deps, () => runs++, 0);
  for (let i = 0; i < 100; i++) s.schedule();
  assert.equal(clock.frames.length, 1);
  assert.equal(s.isPending(), true);
  clock.frame();
  assert.equal(runs, 1);
  assert.equal(s.isPending(), false);
  s.schedule();
  s.schedule();
  clock.frame();
  assert.equal(runs, 2);
});

test('scheduler: a call made during the run schedules the next frame, not a re-entrant run', () => {
  const clock = fakeClock();
  let s;
  let runs = 0;
  s = core.makeScheduler(clock.deps, () => { runs++; if (runs === 1) s.schedule(); }, 0);
  s.schedule();
  clock.frame();
  assert.equal(runs, 1);
  assert.equal(clock.frames.length, 1);
  clock.frame();
  assert.equal(runs, 2);
});

test('scheduler: minInterval spaces runs apart and still coalesces', () => {
  const clock = fakeClock();
  let runs = 0;
  const s = core.makeScheduler(clock.deps, () => runs++, 1000);
  s.schedule();
  clock.frame();
  assert.equal(runs, 1);                    // first run is immediate
  clock.advance(100);
  for (let i = 0; i < 10; i++) s.schedule();
  assert.equal(clock.frames.length, 0);     // waiting for the interval, not for a frame
  assert.equal(clock.timers.length, 1);
  clock.advance(899);
  assert.equal(clock.frames.length, 0);
  clock.advance(1);
  clock.frame();
  assert.equal(runs, 2);
  assert.equal(clock.t, 1000);
});

// ---------------------------------------------------------------------------------------------
// engine: hide counting and once-per-element dismissal
// ---------------------------------------------------------------------------------------------

/** A fake DOM: elements declare which selectors they match; children are scoped queries. */
function fakeDom() {
  const root = [];
  const el = (name, sels, extra = {}) => ({ name, sels, children: [], text: '', clicks: 0, ...extra });
  const env = {
    posts: [],
    clicked: [],
    bundleName: 'mobile-web-player.test0001.js',
    queryAll: (sel, scope) => {
      if (sel === 'THROW') throw new SyntaxError('bad selector');
      return (scope ? scope.children : root).filter((e) => e.sels.includes(sel));
    },
    click: (e) => { e.clicks++; env.clicked.push(e.name); },
    textOf: (e) => e.text,
    bundle: () => env.bundleName,
    report: (m) => env.posts.push(plain(m)),
  };
  return { root, el, env };
}

const popoverRule = (over = {}) => ({
  id: 'pop', tier: 1, action: 'dismiss',
  selectors: ['popover', 'popover-fallback'],
  dismissSelectors: ['notnow', 'tertiary'],
  ...over,
});
const engineFor = (rules, env) => core.createEngine(core.parseRules({ rules }).rules, env);

test('hide: counts distinct matching elements and reports "hidden" once per rule per load', () => {
  const { root, el, env } = fakeDom();
  const engine = engineFor([rule({ id: 'open-app', selectors: ['btn', 'btn-fallback'] })], env);

  engine.scanHide();
  assert.deepEqual(env.posts, []);
  assert.deepEqual(plain(engine.stats()).neverMatched, ['open-app']);

  const a = el('a', ['btn']);
  root.push(a);
  engine.scanHide();
  engine.scanHide();                                    // same element again: not counted twice
  assert.deepEqual(env.posts, [{ t: 'adapt', rule: 'open-app', action: 'hidden', bundle: 'mobile-web-player.test0001.js', sel: 0, count: 1 }]);

  root.push(el('b', ['btn']), el('c', ['btn-fallback']));
  engine.scanHide();
  assert.equal(env.posts.length, 1);                    // no spam
  const s = plain(engine.stats());
  assert.equal(s.rules['open-app'].matches, 3);
  assert.deepEqual(s.rules['open-app'].selectorHits, [2, 1]);
  assert.deepEqual(s.neverMatched, []);
  assert.equal(s.bundle, 'mobile-web-player.test0001.js');
});

test('hide: an invalid selector is recorded and does not stop the others', () => {
  const { root, el, env } = fakeDom();
  const engine = core.createEngine([{ id: 'h', tier: 1, action: 'hide', selectors: ['THROW', 'ok'] }], env);
  root.push(el('x', ['ok']));
  engine.scanHide();
  const s = plain(engine.stats());
  assert.equal(s.rules.h.matches, 1);
  assert.deepEqual(Object.keys(s.rules.h.badSelectors), ['THROW']);
});

test('dismiss: clicks the popup\'s own dismiss button, once per element instance', () => {
  const { root, el, env } = fakeDom();
  const engine = engineFor([popoverRule()], env);
  const notNow = el('not-now', ['notnow']);
  const getApp = el('get-app', ['primary']);
  const popup = el('popup', ['popover'], { children: [notNow, getApp] });
  root.push(popup);

  engine.scanDismiss();
  engine.scanDismiss();
  engine.scanDismiss();                                 // the page keeps the popup around: no second click
  assert.deepEqual(env.clicked, ['not-now']);
  assert.equal(env.posts.length, 1);
  assert.deepEqual(env.posts[0], { t: 'adapt', rule: 'pop', action: 'dismissed', bundle: 'mobile-web-player.test0001.js', sel: 0 });

  // A new instance of the popup (React re-creates it) is a new element and is dismissed again.
  const popup2 = el('popup2', ['popover'], { children: [el('not-now2', ['notnow'])] });
  root.push(popup2);
  engine.scanDismiss();
  assert.deepEqual(env.clicked, ['not-now', 'not-now2']);
  assert.equal(plain(engine.stats()).rules.pop.acted, 2);
});

test('dismiss: the same element matched by two selectors is clicked once', () => {
  const { root, el, env } = fakeDom();
  const engine = engineFor([popoverRule()], env);
  root.push(el('popup', ['popover', 'popover-fallback'], { children: [el('btn', ['notnow'])] }));
  engine.scanDismiss();
  engine.scanDismiss();
  assert.deepEqual(env.clicked, ['btn']);
});

test('dismiss: ordered dismissSelectors, first one that exists wins', () => {
  const { root, el, env } = fakeDom();
  const engine = engineFor([popoverRule()], env);
  root.push(el('popup', ['popover'], { children: [el('tertiary-btn', ['tertiary']), el('x-btn', ['notnow'])] }));
  engine.scanDismiss();
  assert.deepEqual(env.clicked, ['x-btn']);
});

test('dismiss: without a dismiss button nothing is clicked, the failure is reported once, and a late button is used', () => {
  const { root, el, env } = fakeDom();
  const engine = engineFor([popoverRule()], env);
  const popup = el('popup', ['popover']);
  root.push(popup);

  engine.scanDismiss();
  engine.scanDismiss();
  assert.deepEqual(env.clicked, []);
  assert.deepEqual(env.posts, [{ t: 'adapt', rule: 'pop', action: 'dismiss-failed', bundle: 'mobile-web-player.test0001.js', sel: 0, reason: 'no-dismiss-button' }]);
  assert.equal(plain(engine.stats()).rules.pop.failures, 1);

  popup.children.push(el('late', ['notnow']));          // content rendered in a second step
  engine.scanDismiss();
  assert.deepEqual(env.clicked, ['late']);
  assert.equal(env.posts.at(-1).action, 'dismissed');
});

test('dismiss: never hides anything itself (no CSS is produced for dismiss rules)', () => {
  const { rules } = core.parseRules({ rules: [popoverRule()] });
  assert.deepEqual(plain(core.buildHideCss(rules)), []);
});

test('dismiss: a label gate only lets listed "get app" popovers through', () => {
  const { root, el, env } = fakeDom();
  const gated = popoverRule({ labelSelector: 'primary', labels: ['Get App', 'Instalar la aplicación'] });
  const engine = engineFor([gated], env);

  const other = el('other', ['popover'], { children: [el('p', ['primary'], { text: 'Delete playlist' }), el('n', ['notnow'])] });
  const ours = el('ours', ['popover'], { children: [el('p2', ['primary'], { text: '  Instalar   la aplicación ' }), el('n2', ['notnow'])] });
  root.push(other, ours);
  engine.scanDismiss();
  engine.scanDismiss();

  assert.deepEqual(env.clicked, ['n2']);                // normalised whitespace and case
  assert.deepEqual(env.posts.map((p) => [p.action, p.reason]), [['dismiss-failed', 'label-not-listed'], ['dismissed', undefined]]);
});

test('dismiss: a click that throws is reported and not retried', () => {
  const { root, el, env } = fakeDom();
  env.click = () => { throw new Error('boom'); };
  const engine = engineFor([popoverRule()], env);
  root.push(el('popup', ['popover'], { children: [el('btn', ['notnow'])] }));
  engine.scanDismiss();
  engine.scanDismiss();
  assert.deepEqual(env.posts.map((p) => [p.action, p.reason]), [['dismiss-failed', 'click-threw']]);
});

// ---------------------------------------------------------------------------------------------
// install smoke test (templated script, fake document)
// ---------------------------------------------------------------------------------------------

function installPage({ withDocumentElement = false } = {}) {
  const posts = [];
  const clock = fakeClock();
  const observers = [];
  const sheets = [];
  const clicks = [];
  // One stable popover element (same identity on every query, as in a real document).
  const popoverRoot = {
    querySelectorAll: (s) => (s === '[data-encore-id="buttonPrimary"]'
      ? [{ textContent: 'Get App' }]
      : s === '[data-encore-id="buttonTertiary"]' ? [{ click: () => clicks.push('not-now') }] : []),
  };
  const popoverSelector = '[data-tippy-root]:has([data-encore-id="buttonPrimary"]):has([data-encore-id="buttonTertiary"])';

  class CSSStyleSheet {
    constructor() { this.cssRules = []; sheets.push(this); }
    insertRule(css) {
      if (css.includes('BAD')) throw new SyntaxError('bad');
      this.cssRules.push(css);
    }
  }
  class MutationObserver {
    constructor(cb) { this.cb = cb; this.target = null; this.opts = null; this.connected = true; observers.push(this); }
    observe(target, opts) { this.target = target; this.opts = opts; }
    disconnect() { this.connected = false; }
  }
  const document = {
    documentElement: withDocumentElement ? {} : null,
    adoptedStyleSheets: [],
    scripts: [{ src: 'https://open.spotifycdn.com/cdn/build/mobile-web-player/mobile-web-player.cafe0001.js' }],
    querySelectorAll: (sel) => (sel === popoverSelector ? [popoverRoot] : []),
  };
  const window = { OpenSpotifyBridge: { postMessage: (m) => posts.push(JSON.parse(m)) } };
  window.top = window;
  const sandbox = {
    window, document, CSSStyleSheet, MutationObserver, Array,
    requestAnimationFrame: clock.deps.raf,
    setTimeout: (cb, ms) => clock.deps.setTimer(cb, ms),
    performance: { now: clock.deps.now, getEntriesByType: () => [] },
    console,
  };
  const rules = JSON.stringify({
    rules: [
      { id: 'hide-it', tier: 1, action: 'hide', selectors: ['button[data-testid="download-button"]', 'BAD{'] },
      {
        id: 'pop', tier: 1, action: 'dismiss',
        selectors: [popoverSelector],
        dismissSelectors: ['[data-encore-id="buttonTertiary"]'],
        labelSelector: '[data-encore-id="buttonPrimary"]',
        labels: ['Get App'],
      },
    ],
  });
  vm.runInContext(SCRIPT.replace(PLACEHOLDER, rules), vm.createContext(sandbox), { filename: 'ui-adapt.js' });
  return { posts, clock, observers, sheets, document, window, clicks };
}

test('install: hide rules go into an adopted stylesheet before documentElement exists', () => {
  const p = installPage();
  assert.equal(p.document.adoptedStyleSheets.length, 1);
  assert.deepEqual(plain(p.sheets[0].cssRules), ['button[data-testid="download-button"]{display:none !important}']);
  const [installed] = p.posts;
  assert.equal(installed.action, 'installed');
  assert.deepEqual(installed.rules, ['hide-it', 'pop']);
  assert.deepEqual(installed.badCss, ['hide-it#1']);       // the invalid selector was dropped alone
});

test('install: the dismiss observer waits for documentElement, then coalesces mutations per frame', () => {
  const p = installPage();
  // Before documentElement: only a childList watcher on the document itself.
  assert.equal(p.observers.length, 1);
  assert.equal(p.observers[0].target, p.document);
  assert.equal(p.observers[0].opts.subtree, undefined);

  p.document.documentElement = {};
  p.observers[0].cb([]);
  assert.equal(p.observers[0].connected, false);
  assert.equal(p.observers.length, 2);
  const main = p.observers[1];
  assert.equal(main.target, p.document.documentElement);
  assert.deepEqual(plain(main.opts), { childList: true, subtree: true });

  // Initial pass is already scheduled; 200 mutations add nothing.
  for (let i = 0; i < 200; i++) main.cb([]);
  assert.equal(p.clock.frames.length, 2);                  // one dismiss pass + one hide pass (rate limited)
  p.clock.frame();
  assert.deepEqual(p.clicks, ['not-now']);                 // clicked once ...
  for (let i = 0; i < 50; i++) main.cb([]);
  p.clock.frame();
  assert.deepEqual(p.clicks, ['not-now']);                 // ... and the same element is never clicked again
  const dismissed = p.posts.filter((m) => m.action === 'dismissed');
  assert.equal(dismissed.length, 1);
  assert.equal(dismissed[0].bundle, 'mobile-web-player.cafe0001.js');
});

test('install: __osAdapt.stats() reports per-rule counts, the bundle and never-matched rules', () => {
  const p = installPage({ withDocumentElement: true });
  p.clock.frame();
  const s = plain(p.window.__osAdapt.stats());
  assert.equal(s.bundle, 'mobile-web-player.cafe0001.js');
  assert.equal(s.rules.pop.acted, 1);
  assert.deepEqual(s.neverMatched, ['hide-it']);
  assert.deepEqual(s.rejectedRules, []);
  assert.equal(Object.keys(p.window).includes('__osAdapt'), false); // non-enumerable
});

test('install: sub-frames and a second load install nothing', () => {
  const p = installPage();
  assert.equal(p.posts.length, 1);
  const sub = vm.createContext({ window: { top: {} }, document: {}, console });
  vm.runInContext(SCRIPT.replace(PLACEHOLDER, '{"rules":[]}'), sub);
  assert.equal(sub.window.__osAdapt, undefined);
});
