// Host page: owns the editor iframe and relays between it and the Android app.
(function () {
  'use strict';

  var bridge = window.AndroidBridge || {
    // Lets the page run in a desktop browser for testing.
    onOpened: function () {}, onError: function (m) { console.error(m); }, onDirty: function () {},
    exportBegin: function () {}, exportChunk: function () {}, exportEnd: function () {},
    exportFailed: function (id, m) { console.error(m); }, log: function (m) { console.log(m); },
  };

  var params = new URLSearchParams(location.search);
  var docId = params.get('doc');
  var fileName = params.get('name') || 'Document.docx';
  var readonly = params.get('ro') === '1';

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
    openDocument();
  }

  function openDocument() {
    if (openStarted) return;
    openStarted = true;
    status('Opening ' + fileName + '…');
    fetch('/__doc/' + encodeURIComponent(docId), { cache: 'no-store' })
      .then(function (r) {
        if (!r.ok) throw new Error('Could not read the file (' + r.status + ')');
        return r.arrayBuffer();
      })
      .then(function (buffer) {
        return send('document:open-buffer', { fileName: fileName, buffer: buffer, readonly: readonly }, [buffer]);
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

  // Streams a Blob to the app in base64 chunks.
  function streamToApp(id, blob, dirty) {
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

  window.__op = {
    save: function (id, targetExt) {
      send('document:save', targetExt ? { targetExt: targetExt } : {})
        .then(function (payload) {
          if (!payload.file) throw new Error('The editor returned no file');
          return streamToApp(id, payload.file, payload.dirty);
        })
        .catch(function (err) {
          bridge.exportFailed(id, String((err && err.message) || err));
        });
    },
    exportUrl: function (id, url) {
      fetch(url)
        .then(function (r) { return r.blob(); })
        .then(function (blob) { return streamToApp(id, blob, null); })
        .catch(function (err) {
          bridge.exportFailed(id, String((err && err.message) || err));
        });
    },
  };

  frame.src = '/editor?embed=1&embedOrigin=' + encodeURIComponent(location.origin);
  // If the ready message was missed, open anyway once the frame has loaded.
  frame.addEventListener('load', function () {
    setTimeout(function () { if (!ready) { bridge.log('no document:ready, opening anyway'); onReady(); } }, 4000);
  });
})();
