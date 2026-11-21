// Host page: owns the editor iframe and relays between it and the Android app.
// Two ways in:
//   host.html?doc=<token>&name=<file>   open that document right away
//   host.html?standby=1                 boot the editor shell, wait for __op.open()
(function () {
  'use strict';

  var bridge = window.AndroidBridge || {
    // Lets the page run in a desktop browser for testing.
    onOpened: function () {}, onError: function (m) { console.error(m); }, onDirty: function () {},
    exportBegin: function () {}, exportChunk: function () {}, exportEnd: function () {},
    exportFailed: function (id, m) { console.error(m); }, log: function (m) { console.log(m); },
  };
  var config = window.__OP_CONFIG || {};
  // Raw-bytes channel (androidx.webkit) when the WebView supports ArrayBuffer messages.
  var binary = config.binarySave && window.AndroidSave && typeof window.AndroidSave.postMessage === 'function'
    ? window.AndroidSave : null;

  var params = new URLSearchParams(location.search);
  var pendingDoc = params.get('doc') ? {
    id: params.get('doc'),
    name: params.get('name') || 'Document.docx',
    readonly: params.get('ro') === '1',
  } : null;

  var frame = document.getElementById('ed');
  var statusEl = document.getElementById('status');
  var msgEl = document.getElementById('msg');

  var seq = 0;
  var pending = {};
  var ready = false;
  var openStarted = false;

  function status(text) {
    if (text) { msgEl.textContent = text; statusEl.classList.remove('hidden'); }
    else statusEl.classList.add('hidden');
  }

  function send(type, payload, transfer) {
    var id = 'h' + (++seq);
    return new Promise(function (resolve, reject) {
      pending[id] = { resolve: resolve, reject: reject };
      frame.contentWindow.postMessage({ id: id, type: type, payload: payload || {} }, location.origin, transfer || []);
    });
  }

  window.addEventListener('message', function (e) {
    if (e.origin !== location.origin || e.source !== frame.contentWindow) return;
    var data = e.data || {};
    var type = data.type;
    if (typeof type !== 'string' || type.indexOf('document:') !== 0) return;
    var payload = data.payload || {};

    if (type === 'document:ready') onReady();
    if (type === 'document:dirty-changed') bridge.onDirty(!!payload.dirty);

    if (data.id && pending[data.id]) {
      var p = pending[data.id];
      delete pending[data.id];
      if (type === 'document:error') p.reject(new Error(payload.message || 'Unknown error'));
      else p.resolve(payload);
    }
  });

  function onReady() {
    if (ready) return;
    ready = true;
    if (pendingDoc) openDocument();
  }

  function openDocument() {
    if (openStarted || !pendingDoc) return;
    openStarted = true;
    var doc = pendingDoc;
    status('Opening ' + doc.name + '…');
    fetch('/__doc/' + encodeURIComponent(doc.id), { cache: 'no-store' })
      .then(function (r) {
        if (!r.ok) throw new Error('Could not read the file (' + r.status + ')');
        // A Blob, not an ArrayBuffer: large bodies can stay out of the JS heap,
        // and the editor accepts it as-is (document:open-file).
        return r.blob();
      })
      .then(function (blob) {
        return send('document:open-file', { blob: blob, fileName: doc.name, readonly: doc.readonly });
      })
      .then(function () {
        status(null);
        bridge.onOpened();
      })
      .catch(function (err) {
        status(null);
        bridge.onError(String((err && err.message) || err));
      });
  }

  // ---- exports back to the app ------------------------------------------

  // Binary path: 1 MB ArrayBuffer chunks, no encoding.
  function streamBinary(id, blob, dirty) {
    var CHUNK = 1024 * 1024;
    var offset = 0;
    binary.postMessage('b|' + id);
    function next() {
      if (offset >= blob.size) {
        binary.postMessage('e|' + id + '|' + (dirty ? '1' : '0'));
        return Promise.resolve();
      }
      var slice = blob.slice(offset, offset + CHUNK);
      offset += CHUNK;
      return slice.arrayBuffer().then(function (buf) {
        binary.postMessage(buf);
        return next();
      });
    }
    return next();
  }

  // Fallback: base64 strings through @JavascriptInterface.
  function streamBase64(id, blob, dirty) {
    var CHUNK = 768 * 1024; // multiple of 3, so chunks encode independently
    var offset = 0;
    bridge.exportBegin(id);
    function next() {
      if (offset >= blob.size) {
        bridge.exportEnd(id, !!dirty);
        return Promise.resolve();
      }
      var slice = blob.slice(offset, offset + CHUNK);
      offset += CHUNK;
      return new Promise(function (resolve, reject) {
        var reader = new FileReader();
        reader.onload = function () {
          var url = reader.result;
          bridge.exportChunk(id, url.substring(url.indexOf(',') + 1));
          resolve();
        };
        reader.onerror = function () { reject(reader.error); };
        reader.readAsDataURL(slice);
      }).then(next);
    }
    return next();
  }

  function streamToApp(id, blob, dirty) {
    return binary ? streamBinary(id, blob, dirty) : streamBase64(id, blob, dirty);
  }

  function fail(id, err) {
    var msg = String((err && err.message) || err);
    if (binary) binary.postMessage('f|' + id + '|' + msg);
    else bridge.exportFailed(id, msg);
  }

  window.__op = {
    open: function (id, name, ro) {
      pendingDoc = { id: id, name: name, readonly: !!ro };
      if (ready) openDocument();
    },
    save: function (id, targetExt) {
      send('document:save', targetExt ? { targetExt: targetExt } : {})
        .then(function (payload) {
          if (!payload.file) throw new Error('The editor returned no file');
          return streamToApp(id, payload.file, payload.dirty);
        })
        .catch(function (err) { fail(id, err); });
    },
    exportUrl: function (id, url) {
      fetch(url)
        .then(function (r) { return r.blob(); })
        .then(function (blob) { return streamToApp(id, blob, null); })
        .catch(function (err) { fail(id, err); });
    },
  };

  if (!pendingDoc) status(null);
  if (typeof bridge.hostReady === 'function') bridge.hostReady();
  frame.src = '/editor?embed=1&embedOrigin=' + encodeURIComponent(location.origin);
  // If the ready message was missed, open anyway once the frame has loaded.
  frame.addEventListener('load', function () {
    setTimeout(function () { if (!ready) { bridge.log('no document:ready, assuming ready'); onReady(); } }, 4000);
  });
})();
