// Injected first into every page. The APK already contains every editor file,
// so the web app's offline service worker would only duplicate ~450 MB into
// app storage. Removing the API makes the editor skip it (and its wait).
(function () {
  try {
    if ('serviceWorker' in navigator) {
      try { delete Navigator.prototype.serviceWorker; } catch (e) {}
      if ('serviceWorker' in navigator) {
        Object.defineProperty(navigator, 'serviceWorker', { value: undefined, configurable: true });
      }
    }
  } catch (e) {}
})();
