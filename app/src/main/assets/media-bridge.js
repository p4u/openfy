/*
 * media-bridge.js (DESIGN §2.6, v0.3)
 *
 * Injected at document start into the main world of https://open.spotify.com.
 *
 * Android WebView ships Chromium with the Media Session API switched off (kDisableMediaSessionAPI), so
 * pages see no `navigator.mediaSession`, `MediaSession` or `MediaMetadata`. This script therefore
 *   1. PROVIDES a spec-conformant Media Session API when it is absent (a polyfill of a standard web
 *      platform API, in the main world, before page scripts), or WRAPS the native one when it is present;
 *   2. OBSERVES the page's media elements (`<audio>`/`<video>`), including detached ones, as a second
 *      source of truth and for the play/pause fallback;
 *   3. REPORTS everything to the native shell and lets it invoke the registered action handlers.
 *
 * It does not touch the DOM, CSS or content of the page. It is JS-detectable (see the detectability note
 * in docs/DESIGN.md §2.6): polyfilled functions have source text, not "[native code]".
 *
 * Messages (JSON string via OpenfyBridge.postMessage):
 *   {t:"install", session:"polyfill"|"wrapped-native"|"missing", wrapped:[...], hooks:[...], elements}
 *   {t:"metadata", title, artist, album, artwork:[{src,sizes,type}]}   (title/artist empty = cleared)
 *   {t:"state", declared:"none"|"paused"|"playing"}
 *   {t:"position", position, duration, rate}                           (seconds; nulls = cleared)
 *   {t:"handlers", actions:[...]}
 *   {t:"media", ev, paused, ended, pos, dur, rate}                     (seconds)
 *   {t:"error", where, name, message}
 *   {t:"diag", ...}
 */
(function () {
  'use strict';

  // The script is injected into every frame whose origin matches; only the top frame matters.
  if (window !== window.top) return;
  // Idempotency guard: loading the script twice must not wrap anything twice.
  if (window.__osShell) return;

  // ---------------------------------------------------------------------------------------------
  // Transport to the native shell
  // ---------------------------------------------------------------------------------------------

  var bridge = window.OpenfyBridge;

  function post(obj) {
    try {
      if (bridge) bridge.postMessage(JSON.stringify(obj));
    } catch (e) {
      // Never break the page, but leave a trace for debug builds (`console:` lines in logcat).
      try { console.error('osShell post failed', e); } catch (_) { /* ignore */ }
    }
  }

  function reportError(where, e) {
    post({ t: 'error', where: where, name: e && e.name, message: e && e.message });
  }

  function now() {
    return typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now();
  }

  // Registered `setActionHandler` callbacks, shared by the polyfill and the native wrapper.
  var handlers = {};

  function postHandlers() {
    post({ t: 'handlers', actions: Object.keys(handlers) });
  }

  function postMetadata(md) {
    if (!md) {
      post({ t: 'metadata', title: '', artist: '', album: '', artwork: [] });
      return;
    }
    var artwork = [];
    var list = md.artwork || [];
    for (var i = 0; i < list.length; i++) {
      artwork.push({ src: list[i].src, sizes: list[i].sizes, type: list[i].type });
    }
    post({ t: 'metadata', title: md.title, artist: md.artist, album: md.album, artwork: artwork });
  }

  function postPosition(state) {
    if (!state) {
      post({ t: 'position', position: null, duration: null, rate: 1 });
      return;
    }
    post({
      t: 'position',
      position: state.position,
      duration: state.duration,
      rate: state.playbackRate === undefined ? 1 : state.playbackRate
    });
  }

  // ---------------------------------------------------------------------------------------------
  // Media Session: polyfill (WebView has no API) or wrapper (the API is native)
  // ---------------------------------------------------------------------------------------------

  // Chrome 124's MediaSessionAction enum.
  var ACTIONS = [
    'play', 'pause', 'seekbackward', 'seekforward', 'previoustrack', 'nexttrack', 'skipad', 'stop',
    'seekto', 'togglemicrophone', 'togglecamera', 'hangup', 'previousslide', 'nextslide',
    'enterpictureinpicture'
  ];
  var PLAYBACK_STATES = ['none', 'paused', 'playing'];

  /**
   * Installs MediaMetadata, MediaSession and Navigator.prototype.mediaSession following the Media Session
   * spec and Chrome's IDL (error types, enum handling, accessors on prototypes, [SameObject]).
   * Returns the names of what it defined.
   */
  function installPolyfill() {
    var metadataSlots = new WeakMap(); // MediaMetadata -> {title, artist, album, artwork, session}
    var sessionSlots = new WeakMap();  // MediaSession  -> {metadata, playbackState, positionState}
    var navigatorSessions = new WeakMap(); // Navigator -> MediaSession

    function illegalInvocation() {
      return new TypeError('Illegal invocation');
    }

    function slotOf(map, self) {
      var slot = (self !== null && typeof self === 'object') ? map.get(self) : undefined;
      if (!slot) throw illegalInvocation();
      return slot;
    }

    function defineInterface(ctor, tag, descriptors) {
      Object.defineProperties(ctor.prototype, Object.getOwnPropertyDescriptors(descriptors));
      Object.defineProperty(ctor.prototype, Symbol.toStringTag, { value: tag, configurable: true });
      // Native interface objects have a non-writable `prototype`.
      Object.defineProperty(ctor, 'prototype', { writable: false });
      Object.defineProperty(window, tag, { value: ctor, writable: true, configurable: true, enumerable: false });
    }

    // ---- MediaMetadata ----

    var MD_PREFIX = "Failed to construct 'MediaMetadata': ";

    /** WebIDL `DOMString` conversion of one dictionary member. */
    function domString(init, name, prefix) {
      var v = init[name];
      if (v === undefined) return '';
      if (typeof v === 'symbol') throw new TypeError(prefix + 'Cannot convert a Symbol value to a string');
      return String(v);
    }

    /** The spec's "convert artwork algorithm": validate, resolve against the document base URL, freeze. */
    function convertArtwork(list, prefix) {
      if (list === null || typeof list !== 'object' || typeof list[Symbol.iterator] !== 'function') {
        throw new TypeError(prefix + "The provided value cannot be converted to a sequence.");
      }
      var out = [];
      Array.from(list).forEach(function (item) {
        if (item !== undefined && item !== null && typeof item !== 'object' && typeof item !== 'function') {
          throw new TypeError(prefix + 'The provided value is not of type \'MediaImage\'.');
        }
        var dict = item || {};
        var sizes = domString(dict, 'sizes', prefix);
        if (dict.src === undefined) throw new TypeError(prefix + 'required member src is undefined.');
        var src = String(dict.src);
        var type = domString(dict, 'type', prefix);
        var href;
        try {
          href = new URL(src, document.baseURI).href;
        } catch (e) {
          throw new TypeError(prefix + "'" + src + "' can't be resolved to a valid URL.");
        }
        out.push(Object.freeze({ src: href, sizes: sizes, type: type }));
      });
      return Object.freeze(out);
    }

    function notifyBound(slot) {
      if (slot.session) postMetadata(slot);
    }

    function MediaMetadata(init = {}) {
      if (new.target === undefined) {
        throw new TypeError(MD_PREFIX + "Please use the 'new' operator, this DOM object constructor cannot be called as a function.");
      }
      if (init === null) init = {};
      if (typeof init !== 'object' && typeof init !== 'function') {
        throw new TypeError(MD_PREFIX + "The provided value is not of type 'MediaMetadataInit'.");
      }
      var slot = {
        title: domString(init, 'title', MD_PREFIX),
        artist: domString(init, 'artist', MD_PREFIX),
        album: domString(init, 'album', MD_PREFIX),
        artwork: init.artwork === undefined ? Object.freeze([]) : convertArtwork(init.artwork, MD_PREFIX),
        session: null
      };
      metadataSlots.set(this, slot);
    }

    function getString(self, name) {
      return slotOf(metadataSlots, self)[name];
    }

    function setString(self, name, value) {
      var slot = slotOf(metadataSlots, self);
      slot[name] = String(value);
      notifyBound(slot);
    }

    defineInterface(MediaMetadata, 'MediaMetadata', {
      get title() { return getString(this, 'title'); },
      set title(v) { setString(this, 'title', v); },
      get artist() { return getString(this, 'artist'); },
      set artist(v) { setString(this, 'artist', v); },
      get album() { return getString(this, 'album'); },
      set album(v) { setString(this, 'album', v); },
      get artwork() { return slotOf(metadataSlots, this).artwork; },
      set artwork(v) {
        var slot = slotOf(metadataSlots, this);
        slot.artwork = convertArtwork(v, "Failed to set the 'artwork' property on 'MediaMetadata': ");
        notifyBound(slot);
      }
    });

    // ---- MediaSession ----

    var MS_PREFIX = "Failed to execute '";

    function MediaSession() {
      throw new TypeError('Illegal constructor');
    }

    function makeSession() {
      var session = Object.create(MediaSession.prototype);
      sessionSlots.set(session, { metadata: null, playbackState: 'none', positionState: null });
      return session;
    }

    function finiteDouble(v, name) {
      var n = Number(v);
      if (!isFinite(n)) {
        throw new TypeError(MS_PREFIX + "setPositionState' on 'MediaSession': The provided " + name + ' is non-finite.');
      }
      return n;
    }

    defineInterface(MediaSession, 'MediaSession', {
      get metadata() { return slotOf(sessionSlots, this).metadata; },
      set metadata(v) {
        var slot = slotOf(sessionSlots, this);
        if (v === undefined) v = null;
        if (v !== null && !metadataSlots.has(v)) {
          throw new TypeError("Failed to set the 'metadata' property on 'MediaSession': Failed to convert value to 'MediaMetadata'.");
        }
        if (slot.metadata) metadataSlots.get(slot.metadata).session = null;
        slot.metadata = v;
        if (v) metadataSlots.get(v).session = this;
        postMetadata(v);
      },

      get playbackState() { return slotOf(sessionSlots, this).playbackState; },
      set playbackState(v) {
        var slot = slotOf(sessionSlots, this);
        var s = String(v);
        // WebIDL enum *attribute* setters silently ignore invalid values.
        if (PLAYBACK_STATES.indexOf(s) < 0) return;
        slot.playbackState = s;
        post({ t: 'state', declared: s });
      },

      setActionHandler(action, handler) {
        slotOf(sessionSlots, this);
        var m = MS_PREFIX + "setActionHandler' on 'MediaSession': ";
        if (arguments.length < 2) {
          throw new TypeError(m + '2 arguments required, but only ' + arguments.length + ' present.');
        }
        var name = String(action);
        if (ACTIONS.indexOf(name) < 0) {
          throw new TypeError(m + "The provided value '" + name + "' is not a valid enum value of type MediaSessionAction.");
        }
        if (handler !== null && handler !== undefined && typeof handler !== 'function') {
          throw new TypeError(m + "The callback provided as parameter 2 is not of type 'Function'.");
        }
        if (typeof handler === 'function') handlers[name] = handler;
        else delete handlers[name]; // null (or undefined) unregisters
        postHandlers();
      },

      setPositionState(state = {}) {
        var slot = slotOf(sessionSlots, this);
        var m = MS_PREFIX + "setPositionState' on 'MediaSession': ";
        if (state === null) state = {};
        if (typeof state !== 'object' && typeof state !== 'function') {
          throw new TypeError(m + "The provided value is not of type 'MediaPositionState'.");
        }
        if (state.duration === undefined && state.position === undefined && state.playbackRate === undefined) {
          slot.positionState = null; // an empty dictionary clears the position state
          postPosition(null);
          return;
        }
        if (state.duration === undefined) throw new TypeError(m + 'required member duration is undefined.');
        var duration = Number(state.duration); // unrestricted double: Infinity is accepted
        var position = state.position === undefined ? 0 : finiteDouble(state.position, 'position');
        var rate = state.playbackRate === undefined ? 1 : finiteDouble(state.playbackRate, 'playbackRate');
        if (isNaN(duration)) throw new TypeError(m + 'The provided duration cannot be NaN.');
        if (duration < 0) throw new TypeError(m + 'The provided duration cannot be less than zero.');
        if (position < 0) throw new TypeError(m + 'The provided position cannot be less than zero.');
        if (position > duration) throw new TypeError(m + 'The provided position cannot be greater than the duration.');
        if (rate === 0) throw new TypeError(m + 'The provided playbackRate cannot be equal to zero.');
        slot.positionState = Object.freeze({ duration: duration, position: position, playbackRate: rate, at: now() });
        postPosition(slot.positionState);
      },

      // Present in Chrome 124 (M104). The page does not call them; a missing method would be a tell.
      setMicrophoneActive(active) {
        slotOf(sessionSlots, this);
        if (arguments.length < 1) throw new TypeError(MS_PREFIX + "setMicrophoneActive' on 'MediaSession': 1 argument required, but only 0 present.");
        Boolean(active);
      },
      setCameraActive(active) {
        slotOf(sessionSlots, this);
        if (arguments.length < 1) throw new TypeError(MS_PREFIX + "setCameraActive' on 'MediaSession': 1 argument required, but only 0 present.");
        Boolean(active);
      }
    });

    // ---- Navigator.prototype.mediaSession ([SameObject], getter only) ----

    Object.defineProperty(Navigator.prototype, 'mediaSession', {
      get: Object.getOwnPropertyDescriptor({
        get mediaSession() {
          if (!(this instanceof Navigator)) throw illegalInvocation();
          var s = navigatorSessions.get(this);
          if (!s) navigatorSessions.set(this, s = makeSession());
          return s;
        }
      }, 'mediaSession').get,
      set: undefined,
      enumerable: true,
      configurable: true
    });

    return ['MediaMetadata', 'MediaSession', 'Navigator.prototype.mediaSession'];
  }

  /** The native API exists: wrap it (original first, then report). Returns the names it wrapped. */
  function wrapNative() {
    var MS = window.MediaSession.prototype;
    var wrapped = [];

    function wrapAccessor(name, onSet) {
      var desc = Object.getOwnPropertyDescriptor(MS, name);
      if (!desc || !desc.get || !desc.set) return;
      Object.defineProperty(MS, name, {
        configurable: desc.configurable,
        enumerable: desc.enumerable,
        get: function () { return desc.get.call(this); },
        set: function (v) {
          desc.set.call(this, v);
          try { onSet(this, v); } catch (e) { reportError(name, e); }
        }
      });
      wrapped.push(name);
    }

    function wrapMethod(name, after) {
      var desc = Object.getOwnPropertyDescriptor(MS, name);
      if (!desc || typeof desc.value !== 'function') return;
      var orig = desc.value;
      Object.defineProperty(MS, name, {
        configurable: desc.configurable,
        enumerable: desc.enumerable,
        writable: desc.writable,
        value: function () {
          var result = orig.apply(this, arguments);
          try { after.apply(this, arguments); } catch (e) { reportError(name, e); }
          return result;
        }
      });
      wrapped.push(name);
    }

    wrapAccessor('metadata', function (session, md) { postMetadata(md); });
    wrapAccessor('playbackState', function (session) { post({ t: 'state', declared: session.playbackState }); });
    wrapMethod('setActionHandler', function (action, handler) {
      if (typeof handler === 'function') handlers[action] = handler;
      else delete handlers[action]; // null means unregister
      postHandlers();
    });
    wrapMethod('setPositionState', postPosition);
    return wrapped;
  }

  var sessionMode = 'missing';
  var wrappedNames = [];
  try {
    if (!('mediaSession' in navigator) && typeof MediaSession === 'undefined') {
      wrappedNames = installPolyfill();
      sessionMode = 'polyfill';
    } else if (window.MediaSession && window.MediaSession.prototype) {
      wrappedNames = wrapNative();
      sessionMode = 'wrapped-native';
    } else {
      post({ t: 'error', where: 'install', name: 'NoMediaSession', message: 'MediaSession.prototype missing' });
    }
  } catch (e) {
    reportError('install', e);
  }

  // ---------------------------------------------------------------------------------------------
  // Media elements: second source of truth, and the play/pause fallback
  // ---------------------------------------------------------------------------------------------
  //
  // Spotify's element is detached from the document (new Audio() or inside a shadow root), and media
  // events neither bubble nor cross shadow boundaries, so a listener on `document` never sees it. Every
  // element that plays must call play(); hooking play()/pause()/load() and the src/srcObject setters on
  // HTMLMediaElement.prototype finds it. The document capture listeners stay as a backup.

  var MEDIA_EVENTS = ['play', 'playing', 'pause', 'ended', 'durationchange', 'timeupdate'];
  var seen = new WeakSet();
  var refs = []; // WeakRef<HTMLMediaElement>: do not keep dead elements alive
  var activeEl = null;
  var lastPausedEl = null;
  var lastTimeUpdate = 0;
  var lastEvent = null;
  var hooks = [];

  function onMedia(e) {
    // An element in the document is seen twice (document capture, then the element itself).
    if (e === lastEvent) return;
    lastEvent = e;
    var el = e.target;
    if (!(el instanceof HTMLMediaElement)) return;
    if (e.type === 'timeupdate') {
      var t = Date.now();
      if (t - lastTimeUpdate < 1000) return;
      lastTimeUpdate = t;
    }
    if (e.type === 'play' || e.type === 'playing') activeEl = el;
    if (e.type === 'pause' || e.type === 'ended') lastPausedEl = el;
    post({ t: 'media', ev: e.type, paused: el.paused, ended: el.ended, pos: el.currentTime, dur: el.duration, rate: el.playbackRate });
  }

  function track(el) {
    if (!(el instanceof HTMLMediaElement) || seen.has(el)) return;
    seen.add(el);
    refs.push(new WeakRef(el));
    MEDIA_EVENTS.forEach(function (type) { el.addEventListener(type, onMedia); });
  }

  function trackedElements() {
    var live = [];
    refs = refs.filter(function (ref) {
      var el = ref.deref();
      if (el) live.push(el);
      return !!el;
    });
    return live;
  }

  function mediaElements() {
    var all = trackedElements();
    Array.prototype.forEach.call(document.querySelectorAll('audio,video'), function (el) {
      if (all.indexOf(el) < 0) all.push(el);
    });
    return all;
  }

  function hookMedia() {
    var proto = HTMLMediaElement.prototype;
    ['play', 'pause', 'load'].forEach(function (name) {
      var desc = Object.getOwnPropertyDescriptor(proto, name);
      if (!desc || typeof desc.value !== 'function') return;
      var orig = desc.value;
      var hooked = function () {
        try { track(this); } catch (e) { reportError(name, e); }
        return orig.apply(this, arguments);
      };
      Object.defineProperty(hooked, 'name', { value: name, configurable: true });
      Object.defineProperty(proto, name, {
        configurable: desc.configurable, enumerable: desc.enumerable, writable: desc.writable, value: hooked
      });
      hooks.push(name);
    });
    ['src', 'srcObject'].forEach(function (name) {
      var desc = Object.getOwnPropertyDescriptor(proto, name);
      if (!desc || !desc.set) return;
      Object.defineProperty(proto, name, {
        configurable: desc.configurable,
        enumerable: desc.enumerable,
        get: desc.get,
        set: function (v) {
          try { track(this); } catch (e) { reportError(name, e); }
          return desc.set.call(this, v);
        }
      });
      hooks.push(name);
    });
  }

  try {
    hookMedia();
    MEDIA_EVENTS.forEach(function (type) { document.addEventListener(type, onMedia, true); });
    // Best effort: tell native the page is going away so it never keeps a stale "playing" state.
    window.addEventListener('pagehide', function () {
      post({ t: 'media', ev: 'pagehide', paused: true, ended: false });
    });
  } catch (e) {
    reportError('media-hooks', e);
  }

  // ---------------------------------------------------------------------------------------------
  // Native -> page
  // ---------------------------------------------------------------------------------------------

  // Fallback when the page registered no handler: what the browser itself would do.
  function fallback(action) {
    if (action === 'pause') {
      var el = activeEl && !activeEl.paused ? activeEl : mediaElements().filter(function (m) { return !m.paused; })[0];
      if (!el) return 'none';
      el.pause();
      return 'element';
    }
    if (action === 'play') {
      var target = lastPausedEl || activeEl || mediaElements()[0];
      if (!target) return 'none';
      var p = target.play();
      if (p && p.catch) p.catch(function (e) { reportError('play', e); }); // NotAllowedError without user activation
      return 'element';
    }
    return 'none';
  }

  /** Calls the registered handler with a MediaSessionActionDetails-shaped object, else the fallback. */
  function invoke(action, extra) {
    var details = { action: action };
    if (extra) {
      for (var k in extra) {
        if (Object.prototype.hasOwnProperty.call(extra, k)) details[k] = extra[k];
      }
    }
    if (action === 'seekto' && details.fastSeek === undefined) details.fastSeek = false;

    var handler = handlers[action];
    if (typeof handler === 'function') {
      try {
        var r = handler(details);
        if (r && typeof r.catch === 'function') r.catch(function (e) { reportError(action, e); });
        return 'handler';
      } catch (e) {
        reportError(action, e);
        return 'error';
      }
    }
    return fallback(action);
  }

  // ---------------------------------------------------------------------------------------------
  // Diagnostics
  // ---------------------------------------------------------------------------------------------

  function widevine() {
    if (!navigator.requestMediaKeySystemAccess) return Promise.resolve({ ok: false, error: 'unavailable' });
    return navigator.requestMediaKeySystemAccess('com.widevine.alpha', [{
      initDataTypes: ['cenc'],
      audioCapabilities: [{ contentType: 'audio/mp4; codecs="mp4a.40.2"' }]
    }]).then(function () { return { ok: true }; }, function (e) { return { ok: false, error: e && e.name }; });
  }

  async function diag() {
    var uaData = null;
    try {
      if (navigator.userAgentData) {
        uaData = await navigator.userAgentData.getHighEntropyValues([
          'architecture', 'bitness', 'model', 'platformVersion', 'fullVersionList',
          'uaFullVersion', 'wow64', 'formFactors'
        ]);
      }
    } catch (e) {
      uaData = { error: e && e.name };
    }
    var w = await widevine();
    post({
      t: 'diag',
      ua: navigator.userAgent,
      uaData: uaData,
      widevine: w.ok,
      widevineError: w.error,
      bridgeInstalled: true,
      session: sessionMode,
      wrapped: wrappedNames,
      hooks: hooks,
      elements: mediaElements().length,
      visibility: document.visibilityState,
      href: location.href,
      platform: navigator.platform,
      maxTouchPoints: navigator.maxTouchPoints,
      screen: { w: screen.width, h: screen.height, dpr: window.devicePixelRatio, iw: window.innerWidth, ih: window.innerHeight },
      handlers: Object.keys(handlers),
      // ui-adapt.js (DESIGN §2.9) when UI adaptation is on, otherwise null.
      adapt: window.__osAdapt ? window.__osAdapt.stats() : null
    });
  }

  Object.defineProperty(window, '__osShell', {
    value: Object.freeze({ invoke: invoke, diag: diag }),
    configurable: false,
    enumerable: false,
    writable: false
  });

  post({ t: 'install', session: sessionMode, wrapped: wrappedNames, hooks: hooks, elements: mediaElements().length });
})();
