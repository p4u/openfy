/*
 * ui-adapt.js (DESIGN §2.9, v0.4): "UI adaptation" mode.
 *
 * Injected at document start into the main world of https://open.spotify.com, but only while the
 * `ui_adaptation` preference is on (it has its own addDocumentStartJavaScript handle so the debug menu can
 * remove it and reload). It is a DELIBERATE, documented deviation from the v0.3 non-goal "no content
 * modification", and it is scoped to two kinds of first-party promotion that are redundant inside a
 * dedicated shell:
 *   tier 1  app-install nags: the "Open App" header button and "get the app" popovers/dialogs,
 *   tier 2  first-party Premium upsell banners.
 * Out of scope and never implemented here: audio ads, third-party/display ads, any network blocking,
 * anything that touches playback.
 *
 * Mechanism (rules come from ui-rules.json, templated into this file by native code):
 *   "hide"    a constructable CSSStyleSheet in document.adoptedStyleSheets, `display:none !important`,
 *             one CSS rule per selector. Works before documentElement exists; does not touch the DOM tree.
 *   "dismiss" a MutationObserver (started once documentElement exists), coalesced to one pass per animation
 *             frame. A matching popup is dismissed by clicking ITS OWN dismiss button, at most once per
 *             element instance. If there is no such button nothing is hidden (CSS could leave a backdrop or a
 *             scroll lock behind); the failure is only reported.
 * Rules never match hashed class names (parseRules rejects them). Third-party consent dialogs (OneTrust)
 * are never touched.
 *
 * Telemetry through OpenfyBridge:
 *   {t:"adapt", rule, action:"hidden"|"dismissed"|"dismiss-failed", bundle, sel, count?, reason?}
 *   {t:"adapt", rule:null, action:"installed", bundle, rules:[ids], rejected:[{id,error}]}
 * `bundle` is the hashed web-player bundle file name, so Spotify deploys can be told apart.
 * Inspect from DevTools with `__osAdapt.stats()` (also included in `__osShell.diag()` as `adapt`).
 */
(function () {
  'use strict';

  // The placeholder below is replaced by native code with the contents of ui-rules.json.
  var RULES_JSON = /*__OS_ADAPT_RULES__*/null;

  // ---------------------------------------------------------------------------------------------
  // Pure core (unit-tested on the host, tools/test/ui-adapt.test.mjs)
  // ---------------------------------------------------------------------------------------------

  /** Class names are only allowed if they look hand-written: lowercase words joined by dashes. */
  function isHashedClass(name) {
    return !/^[a-z]+(-[a-z]+)*$/.test(name);
  }

  /** Returns a reason string if [sel] must not be shipped, or null. */
  function selectorProblem(sel) {
    if (typeof sel !== 'string' || !sel.trim()) return 'empty selector';
    var s = sel
      .replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, '""')          // quoted strings (dots inside are not classes)
      .replace(/\[[^\]]*\]/g, function (m) { return /^\[\s*class\b/i.test(m) ? '[CLASSATTR]' : '[]'; });
    if (s.indexOf('[CLASSATTR]') >= 0) return 'class attribute selector';
    var re = /\.(-?[_a-zA-Z][_a-zA-Z0-9-]*)/g;
    var m;
    while ((m = re.exec(s))) {
      if (isHashedClass(m[1])) return 'hashed-looking class .' + m[1];
    }
    return null;
  }

  function selectorList(value, field, errors) {
    if (!Array.isArray(value) || value.length === 0) {
      errors.push(field + ' must be a non-empty array');
      return null;
    }
    for (var i = 0; i < value.length; i++) {
      var p = selectorProblem(value[i]);
      if (p) {
        errors.push(field + '[' + i + ']: ' + p);
        return null;
      }
    }
    return value.slice();
  }

  /**
   * Validates a rule document (object or JSON string). Invalid rules are dropped one by one and listed in
   * `errors`; the rest still apply.
   * @returns {{rules: object[], errors: {id: string|null, error: string}[]}}
   */
  function parseRules(input) {
    var doc = input;
    var errors = [];
    if (typeof input === 'string') {
      try { doc = JSON.parse(input); } catch (e) { return { rules: [], errors: [{ id: null, error: 'invalid JSON' }] }; }
    }
    if (!doc || !Array.isArray(doc.rules)) {
      return { rules: [], errors: [{ id: null, error: 'missing "rules" array' }] };
    }
    var seen = {};
    var rules = [];
    doc.rules.forEach(function (raw, index) {
      var errs = [];
      var id = raw && typeof raw.id === 'string' ? raw.id : null;
      if (!id || !/^[a-z][a-z0-9-]*$/.test(id)) errs.push('bad id');
      else if (seen[id]) errs.push('duplicate id');
      if (!raw || (raw.tier !== 1 && raw.tier !== 2)) errs.push('tier must be 1 or 2');
      if (!raw || (raw.action !== 'hide' && raw.action !== 'dismiss')) errs.push('action must be "hide" or "dismiss"');
      var selectors = raw ? selectorList(raw.selectors, 'selectors', errs) : null;
      var rule = null;
      if (raw && !errs.length) {
        rule = { id: id, tier: raw.tier, action: raw.action, selectors: selectors };
        if (raw.action === 'hide') {
          if (raw.dismissSelectors || raw.labels || raw.labelSelector) errs.push('hide rules take no dismissSelectors/labels');
        } else {
          rule.dismissSelectors = selectorList(raw.dismissSelectors, 'dismissSelectors', errs);
          if (raw.labels !== undefined || raw.labelSelector !== undefined) {
            var okLabels = Array.isArray(raw.labels) && raw.labels.length > 0 &&
              raw.labels.every(function (l) { return typeof l === 'string' && l.trim() !== ''; });
            if (!okLabels) errs.push('labels must be a non-empty array of strings');
            var lp = selectorProblem(raw.labelSelector);
            if (lp) errs.push('labelSelector: ' + lp);
            if (okLabels && !lp) {
              rule.labelSelector = raw.labelSelector;
              rule.labels = raw.labels.map(normLabel);
            }
          }
        }
      }
      if (errs.length) {
        errors.push({ id: id || ('#' + index), error: errs.join('; ') });
      } else {
        seen[id] = true;
        rules.push(rule);
      }
    });
    return { rules: rules, errors: errors };
  }

  function normLabel(s) {
    return String(s).replace(/\s+/g, ' ').trim().toLowerCase();
  }

  /** One CSS rule per selector: a single invalid selector would otherwise void the whole list. */
  function buildHideCss(rules) {
    var out = [];
    rules.forEach(function (r) {
      if (r.action !== 'hide') return;
      r.selectors.forEach(function (sel, i) {
        out.push({ rule: r.id, sel: i, css: sel + '{display:none !important}' });
      });
    });
    return out;
  }

  /**
   * Coalescing scheduler: any number of schedule() calls before the next run produce exactly one run, on an
   * animation frame, and at most one run per [minInterval] ms.
   * deps: { raf(cb), now(), setTimer(cb, ms) }.
   */
  function makeScheduler(deps, fn, minInterval) {
    var pending = false;
    var last = -Infinity;
    function run() {
      pending = false;
      last = deps.now();
      fn();
    }
    return {
      schedule: function () {
        if (pending) return;
        pending = true;
        var wait = minInterval ? Math.max(0, last + minInterval - deps.now()) : 0;
        if (wait > 0) deps.setTimer(function () { deps.raf(run); }, wait);
        else deps.raf(run);
      },
      isPending: function () { return pending; }
    };
  }

  /** The hashed web-player bundle name, e.g. "mobile-web-player.fe7b9205.js", or null. */
  function bundleFromUrls(urls) {
    for (var i = 0; i < urls.length; i++) {
      var m = /\/((?:mobile-)?web-player\.[0-9a-f]+\.js)(?:[?#]|$)/.exec(String(urls[i]));
      if (m) return m[1];
    }
    return null;
  }

  /**
   * The rule engine. env: { queryAll(selector, scope?) -> Element[] (may throw), click(el), textOf(el),
   * bundle() -> string|null, report(msg) }. Elements are only used as identities (WeakSet keys).
   */
  function createEngine(rules, env) {
    var state = {};
    rules.forEach(function (r) {
      state[r.id] = {
        rule: r,
        matches: 0,
        acted: 0,
        failures: 0,
        selectorHits: r.selectors.map(function () { return 0; }),
        seen: new WeakSet(),
        handled: new WeakSet(),
        failReported: new WeakSet(),
        reportedHidden: false,
        badSelectors: {}
      };
    });

    function post(rule, action, extra) {
      var msg = { t: 'adapt', rule: rule, action: action, bundle: env.bundle() };
      for (var k in extra) msg[k] = extra[k];
      env.report(msg);
    }

    function query(st, sel, scope) {
      try {
        return env.queryAll(sel, scope) || [];
      } catch (e) {
        st.badSelectors[sel] = String(e && e.message || e);
        return [];
      }
    }

    function fail(st, el, selIndex, reason) {
      if (st.failReported.has(el)) return;
      st.failReported.add(el);
      st.failures++;
      post(st.rule.id, 'dismiss-failed', { sel: selIndex, reason: reason });
    }

    function scanHide() {
      rules.forEach(function (r) {
        if (r.action !== 'hide') return;
        var st = state[r.id];
        r.selectors.forEach(function (sel, i) {
          var added = 0;
          query(st, sel).forEach(function (el) {
            if (st.seen.has(el)) return;
            st.seen.add(el);
            st.matches++;
            st.selectorHits[i]++;
            added++;
          });
          if (added && !st.reportedHidden) {
            st.reportedHidden = true; // once per rule per page load
            post(r.id, 'hidden', { sel: i, count: added });
          }
        });
      });
    }

    function dismissOne(st, el, selIndex) {
      var r = st.rule;
      if (st.handled.has(el)) return;
      if (!st.seen.has(el)) {
        st.seen.add(el);
        st.matches++;
        st.selectorHits[selIndex]++;
      }
      if (r.labels) {
        var gate = query(st, r.labelSelector, el).some(function (b) {
          return r.labels.indexOf(normLabel(env.textOf(b))) >= 0;
        });
        if (!gate) { fail(st, el, selIndex, 'label-not-listed'); return; }
      }
      var button = null;
      for (var i = 0; i < r.dismissSelectors.length && !button; i++) {
        button = query(st, r.dismissSelectors[i], el)[0] || null;
      }
      if (!button) { fail(st, el, selIndex, 'no-dismiss-button'); return; }
      st.handled.add(el); // at most one click per element instance
      try {
        env.click(button);
      } catch (e) {
        st.failures++;
        post(r.id, 'dismiss-failed', { sel: selIndex, reason: 'click-threw' });
        return;
      }
      st.acted++;
      post(r.id, 'dismissed', { sel: selIndex });
    }

    function scanDismiss() {
      rules.forEach(function (r) {
        if (r.action !== 'dismiss') return;
        var st = state[r.id];
        r.selectors.forEach(function (sel, i) {
          query(st, sel).forEach(function (el) { dismissOne(st, el, i); });
        });
      });
    }

    function stats() {
      var out = { bundle: env.bundle(), rules: {}, neverMatched: [] };
      rules.forEach(function (r) {
        var st = state[r.id];
        out.rules[r.id] = {
          tier: r.tier,
          action: r.action,
          matches: st.matches,
          acted: st.acted,
          failures: st.failures,
          selectorHits: st.selectorHits.slice(),
          badSelectors: st.badSelectors
        };
        if (st.matches === 0) out.neverMatched.push(r.id);
      });
      return out;
    }

    return { scanHide: scanHide, scanDismiss: scanDismiss, stats: stats };
  }

  /** Calls cb once documentElement exists (it does not at document start). */
  function whenDocumentElement(doc, ObserverCtor, cb) {
    if (doc.documentElement) { cb(); return; }
    var mo = new ObserverCtor(function () {
      if (doc.documentElement) { mo.disconnect(); cb(); }
    });
    mo.observe(doc, { childList: true });
  }

  var core = {
    selectorProblem: selectorProblem,
    parseRules: parseRules,
    buildHideCss: buildHideCss,
    makeScheduler: makeScheduler,
    bundleFromUrls: bundleFromUrls,
    createEngine: createEngine,
    whenDocumentElement: whenDocumentElement
  };

  // Test hook: the host test reads the core from here. Never defined in the app.
  if (typeof __OS_ADAPT_TEST__ !== 'undefined') __OS_ADAPT_TEST__.core = core;

  // ---------------------------------------------------------------------------------------------
  // Installation (browser only)
  // ---------------------------------------------------------------------------------------------

  if (RULES_JSON === null) return;                 // not templated (host test of the core only)
  if (window !== window.top) return;               // main frame only
  if (window.__osAdapt) return;                    // idempotent

  var bridge = window.OpenfyBridge;
  function post(obj) {
    try {
      if (bridge) bridge.postMessage(JSON.stringify(obj));
    } catch (e) {
      try { console.error('osAdapt post failed', e); } catch (_) { /* ignore */ }
    }
  }

  var parsed = parseRules(RULES_JSON);
  var rules = parsed.rules;

  var cachedBundle = null;
  function bundle() {
    if (cachedBundle) return cachedBundle;
    try {
      var urls = [];
      var scripts = document.scripts || [];
      for (var i = 0; i < scripts.length; i++) if (scripts[i].src) urls.push(scripts[i].src);
      cachedBundle = bundleFromUrls(urls);
      if (!cachedBundle && typeof performance !== 'undefined' && performance.getEntriesByType) {
        cachedBundle = bundleFromUrls(performance.getEntriesByType('resource').map(function (e) { return e.name; }));
      }
    } catch (e) { /* ignore */ }
    return cachedBundle;
  }

  var badCss = [];
  var css = buildHideCss(rules);
  if (css.length) {
    try {
      var sheet = new CSSStyleSheet();
      css.forEach(function (c) {
        try { sheet.insertRule(c.css, sheet.cssRules.length); } catch (e) { badCss.push(c.rule + '#' + c.sel); }
      });
      document.adoptedStyleSheets = [].concat(Array.from(document.adoptedStyleSheets), [sheet]);
    } catch (e) {
      badCss.push('sheet: ' + (e && e.message));
    }
  }

  var engine = createEngine(rules, {
    queryAll: function (sel, scope) { return Array.from((scope || document).querySelectorAll(sel)); },
    click: function (el) { el.click(); },
    textOf: function (el) { return el.textContent || ''; },
    bundle: bundle,
    report: post
  });

  var deps = {
    raf: function (cb) { requestAnimationFrame(function () { cb(); }); },
    now: function () { return performance.now(); },
    setTimer: function (cb, ms) { setTimeout(cb, ms); }
  };
  var dismissPass = makeScheduler(deps, engine.scanDismiss, 0);
  var hidePass = makeScheduler(deps, engine.scanHide, 1000); // counting hidden matches does not need frame rate

  Object.defineProperty(window, '__osAdapt', {
    value: Object.freeze({
      stats: function () {
        var s = engine.stats();
        s.rejectedRules = parsed.errors;
        s.badCss = badCss;
        return s;
      }
    }),
    configurable: false,
    enumerable: false,
    writable: false
  });

  whenDocumentElement(document, MutationObserver, function () {
    new MutationObserver(function () {
      dismissPass.schedule();
      hidePass.schedule();
    }).observe(document.documentElement, { childList: true, subtree: true });
    dismissPass.schedule();
    hidePass.schedule();
  });

  post({
    t: 'adapt',
    rule: null,
    action: 'installed',
    bundle: null, // not known yet at document start
    rules: rules.map(function (r) { return r.id; }),
    rejected: parsed.errors,
    badCss: badCss
  });
})();
