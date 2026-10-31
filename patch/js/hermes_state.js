(function () {
  if (window.__hermesInstalled) {
    try { window.__hermesKick(); } catch (e) {}
    return 'ok';
  }
  window.__hermesInstalled = true;

  function text(selector) {
    var el = document.querySelector(selector);
    return el ? (el.textContent || '').trim() : '';
  }

  function textList(selector) {
    return Array.prototype.map.call(document.querySelectorAll(selector), function (el) {
      return (el.textContent || '').trim();
    });
  }

  function clockMs(value) {
    var match = /^(\d{1,2}):(\d{2})$/.exec(value || '');
    return match ? (parseInt(match[1], 10) * 60 + parseInt(match[2], 10)) * 60000 : 0;
  }

  function moduleContext() {
    try {
      var raw = localStorage.getItem('module-player');
      if (!raw) return {};
      var data = JSON.parse(raw);
      var video = data && data.state && data.state.video ? data.state.video : {};
      return video || {};
    } catch (e) {
      return {};
    }
  }

  function query(url) {
    var result = {};
    var index = (url || '').indexOf('?');
    if (index < 0) return result;
    (url.slice(index + 1) || '').split('&').forEach(function (pair) {
      var kv = pair.split('=');
      if (kv[0]) result[decodeURIComponent(kv[0])] = decodeURIComponent(kv[1] || '');
    });
    return result;
  }

  function archive(ctx) {
    var url = ctx && ctx.streamURL && ctx.streamURL.url ? ctx.streamURL.url : '';
    var params = query(url);
    var start = Date.parse(params.start || '');
    var end = Date.parse(params.end || '');
    var duration = (isFinite(start) && isFinite(end) && end > start) ? (end - start) : 0;
    var position = parseInt(ctx && ctx.position ? ctx.position : 0, 10);
    if (!isFinite(position) || position < 0) position = 0;
    return { duration: duration, position: position };
  }

  function progressPercent() {
    var max = 0;
    Array.prototype.forEach.call(
      document.querySelectorAll('.seeker-progressbar .progress'),
      function (el) {
        var match = /width:\s*([\d.]+)%/.exec(el.getAttribute('style') || '');
        if (match) max = Math.max(max, parseFloat(match[1]));
      }
    );
    return Math.max(0, Math.min(100, max));
  }

  function snapshot() {
    var hash = location.hash || '';
    var player = document.querySelector('.video-maxi-player');
    var playingRow = document.querySelector('.row-item.playing');
    var ctx = moduleContext();
    var source = (ctx.streamService || '').toUpperCase();

    // Player overlay. The focused EPG item is never used here.
    if (player && hash.indexOf('#/player') === 0) {
      var title = text('.video-maxi-player #detail-preview-title');
      var channel = text('.video-maxi-player .channel-name');
      var times = textList('.seekbar-time').filter(function (value) {
        return /^\d{1,2}:\d{2}$/.test(value);
      });
      var duration = times.length >= 2
        ? Math.max(clockMs(times[1]) - clockMs(times[0]), 0)
        : 0;
      var position = duration > 0 ? Math.round(duration * progressPercent() / 100) : 0;

      if (source === 'ARCHIVE') {
        var archived = archive(ctx);
        if (archived.duration > 0) duration = archived.duration;
        if (archived.position > 0) position = archived.position;
      }

      return {
        title: title,
        channel: channel,
        positionMs: position,
        durationMs: duration,
        playing: true,
        source: source || 'LIVE',
        updatedAtEpochMs: Date.now()
      };
    }

    // EPG/home. `.row-item.playing` is the actual playback marker.
    if (playingRow) {
      var row = playingRow.closest('.epg-row');
      var channelId = row ? row.getAttribute('data-channel-id') : '';
      var label = channelId ? document.getElementById('channel-title-label-' + channelId) : null;
      var rowChannel = label ? (label.textContent || '').replace(/\.$/, '').trim() : '';
      var rowTitle = (playingRow.textContent || '').split('\n')[0].trim();
      var archivedRow = archive(ctx);

      return {
        title: rowTitle,
        channel: rowChannel,
        positionMs: archivedRow.position,
        durationMs: archivedRow.duration,
        playing: true,
        source: source || 'LIVE',
        updatedAtEpochMs: Date.now()
      };
    }

    return null;
  }

  function enc(value) {
    return encodeURIComponent(value == null ? '' : String(value));
  }

  function wire(state) {
    return [
      'v1',
      enc(state.title),
      enc(state.channel),
      Math.max(0, Math.round(state.positionMs || 0)),
      Math.max(0, Math.round(state.durationMs || 0)),
      state.playing ? 1 : 0,
      enc(state.source),
      Math.round(state.updatedAtEpochMs || Date.now())
    ].join('|');
  }

  var last = '';
  var lastState = null;

  function post(force) {
    try {
      var state = snapshot();
      if (!state) {
        // EPG/home sometimes has no `.playing` marker. Keep the last known
        // programme instead of clearing the card while the user browses.
        if (lastState) return;
        state = {
          title: '', channel: '', positionMs: 0, durationMs: 0,
          playing: false, source: 'UNKNOWN', updatedAtEpochMs: Date.now()
        };
      }
      var next = wire(state);
      if (!force && next === last) return;
      last = next;
      lastState = state;
      if (window.HermesNative && window.HermesNative.postState) {
        window.HermesNative.postState(next);
      }
    } catch (e) {}
  }

  window.__hermesKick = function () { post(true); };

  // Detect the app's own writes to module-player. Wrapped defensively because
  // some WebView builds expose localStorage methods as non-writable.
  try {
    var originalSetItem = localStorage.setItem.bind(localStorage);
    localStorage.setItem = function (key, value) {
      originalSetItem(key, value);
      if (key === 'module-player') {
        setTimeout(function () { post(true); }, 50);
      }
    };
  } catch (e) {}

  var timer = null;
  var observer = new MutationObserver(function () {
    if (timer) clearTimeout(timer);
    timer = setTimeout(function () { post(false); }, 250);
  });
  observer.observe(document.documentElement, {
    subtree: true,
    childList: true,
    characterData: true,
    attributes: true,
    attributeFilter: ['class']
  });

  window.addEventListener('hashchange', function () { post(true); });

  // Position keyframe only while the player is open. Metadata is event-driven;
  // this keeps the reported position from drifting between progress updates.
  setInterval(function () {
    if ((location.hash || '').indexOf('#/player') === 0) {
      post(false);
    }
  }, 10000);

  post(true);
  return 'ok';
})();
