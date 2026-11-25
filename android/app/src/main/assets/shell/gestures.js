// Touch gestures for the presentation editor (loaded into its frame by shim.js).
//
// The editor's own touch handling has two problems on a phone:
//  - Two fingers on a selected object rotate or resize it instead of zooming
//    (a pinch on a selected title box turned it upside down), and a one-finger
//    drag on any selected object moves it, so most of a slide can't be panned.
//  - Its pinch re-renders the slide at every step, which is slow on low-end
//    phones and is how slides end up blank after repeated zooming.
//
// This layer takes over:
//  - Two fingers, anywhere on the slide area: pinch to zoom, drag to pan.
//    While the fingers are down the current picture is only scaled with CSS;
//    the slide is re-rendered once, when the fingers lift, zoomed around the
//    point you pinched.
//  - One finger that doesn't start on the selected object: drag pans the view
//    (with a short fling), a tap goes to the editor as a normal tap, and a long
//    press goes to the editor too (context menu, or press-and-drag to move).
//  - One finger on the selected object, or while drawing/inserting a shape:
//    untouched, so moving, resizing and text selection work as before.
//  - Double tap on empty slide space: switch between fit-to-width and 100 %.
(function () {
  'use strict';
  if (window.__opGestures) return;
  window.__opGestures = true;

  var config = window.__OP_CONFIG || (window.parent && window.parent.__OP_CONFIG) || {};
  var MIN_ZOOM = 25;
  // Every zoom step reallocates canvases at the device pixel ratio; cap it on
  // phones with little memory.
  var MAX_ZOOM = config.lowMemory ? 300 : 500;
  var SLOP = 10;          // px a finger may wander before a tap becomes a drag
  var LONG_PRESS = 450;   // ms

  var replaying = false;
  var pointers = {};      // pointerId -> {x, y, sx, sy, target}
  var count = 0;
  var mode = 'idle';      // idle | pending | sdk | pan | pinch | rest
  var longTimer = 0;
  var pinch = null;
  var panPending = { x: 0, y: 0 };
  var panFrame = 0;
  var velocity = { x: 0, y: 0, t: 0 };
  var fling = 0;
  var lastTap = null;

  function api() { return window.Asc && window.Asc.editor; }
  function control() { var a = api(); return a && a.WordControl; }

  function mainView() {
    var wc = control();
    return wc && wc.m_oMainView && wc.m_oMainView.HtmlElement;
  }

  function inSlideArea(e) {
    var mv = mainView();
    return !!(mv && e.target && mv.contains(e.target));
  }

  function editorBusyWithPointer() {
    var a = api();
    if (!a) return true;
    try {
      if (a.isStartAddShape) return true;
      if (typeof a.isInkDrawerOn === 'function' && a.isInkDrawerOn()) return true;
      var wc = a.WordControl;
      if (wc && wc.DemonstrationManager && wc.DemonstrationManager.Mode) return true;
    } catch (err) {}
    return false;
  }

  function docPoint(x, y) {
    var wc = control();
    return wc.m_oDrawingDocument.ConvertCoordsFromCursor2(x, y);
  }

  function currentObjects() {
    var wc = control();
    var ld = wc && wc.m_oLogicDocument;
    var slide = ld && ld.GetCurrentSlide && ld.GetCurrentSlide();
    return slide && slide.graphicObjects;
  }

  /** Is the finger on the selected object (its box or its handles)? */
  function onSelection(x, y) {
    try {
      var go = currentObjects();
      var sel = go && go.selectedObjects;
      if (!sel || !sel.length) return false;
      var p = docPoint(x, y);
      for (var i = 0; i < sel.length; i++) {
        var o = sel[i];
        if (o.hitToHandles && o.hitToHandles(p.X, p.Y) > -1) return true;
        if (o.hitInBoundingRect && o.hitInBoundingRect(p.X, p.Y)) return true;
        if (o.hit && o.hit(p.X, p.Y)) return true;
      }
    } catch (err) {}
    return false;
  }

  function onAnyObject(x, y) {
    try {
      var go = currentObjects();
      var p = docPoint(x, y);
      return !!(go && go.isPointInDrawingObjects3 && go.isPointInDrawingObjects3(p.X, p.Y, p.Page));
    } catch (err) {
      return true; // unsure: don't treat as empty space
    }
  }

  // ---- talking to the editor ----------------------------------------------

  function replay(type, ptr, id, x, y) {
    var init = {
      bubbles: true, cancelable: true, composed: true, view: window,
      pointerId: id, pointerType: 'touch', isPrimary: true,
      clientX: x, clientY: y, screenX: x, screenY: y,
      button: type === 'pointermove' ? -1 : 0,
      buttons: type === 'pointerup' ? 0 : 1,
      width: 1, height: 1, pressure: type === 'pointerup' ? 0 : 0.5,
    };
    replaying = true;
    try {
      (ptr.target && ptr.target.isConnected ? ptr.target : mainView()).dispatchEvent(new PointerEvent(type, init));
    } catch (err) {
    } finally {
      replaying = false;
    }
  }

  // Absolute positioning, like dragging the scrollbar. (scrollByX/Y go through
  // the wheel handler, which snaps every step to the next slide: a 100 px drag
  // ran through a whole deck.)
  //
  // Vertically the editor lays slides out one after another, each taking
  // `per` scroll units; scrolling past a slide's own range switches slides.
  // So vertical moves are kept inside the current slide, and the part that
  // didn't fit is returned (the caller decides whether that means "next").
  function slideRange() {
    var wc = control(), a = api(), mv = mainView();
    var v = wc.m_oScrollVerApi;
    var n = Math.max(1, (a.getCountPages && a.getCountPages()) || 1);
    var h = mv.clientHeight;
    var per = ((v.maxScrollY || 0) + h) / n;
    var s = currentSlide();
    return { min: s * per, max: s * per + Math.max(0, per - h) };
  }

  function scrollBy(dx, dy) {
    var wc = control();
    if (!wc) return 0;
    var h = wc.m_oScrollHorApi, v = wc.m_oScrollVerApi;
    var over = 0;
    if (dx && h && h.settings && h.settings.isHorizontalScroll) {
      h.scrollToX(Math.max(0, Math.min(h.maxScrollX || 0, h.scrollHCurrentX + dx)));
    }
    if (dy && v && v.settings && v.settings.isVerticalScroll) {
      var r;
      try { r = slideRange(); } catch (err) { r = { min: 0, max: v.maxScrollY || 0 }; }
      var want = v.scrollVCurrentY + dy;
      var y = Math.max(r.min, Math.min(r.max, want));
      over = want - y;
      if (y !== v.scrollVCurrentY) v.scrollToY(y);
    }
    return over;
  }

  function zoomValue() {
    var wc = control();
    return (wc && wc.m_nZoomValue) || 100;
  }

  /** Set the zoom so that document point `p` lands at screen point (x, y). */
  function zoomAround(z, p, x, y) {
    var a = api();
    var wc = control();
    if (!a || !wc) return;
    z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Math.round(z)));
    console.log('[gestures] zoom ' + zoomValue() + ' -> ' + z);
    var slide = currentSlide();
    if (z !== zoomValue()) a.zoom(z);
    if (!p || p.Page < 0) return;
    try {
      // Anchor on the slide itself, not on the grey margin around it.
      var ld = wc.m_oLogicDocument;
      var px = Math.max(0, Math.min(ld.GetWidthMM(), p.X));
      var py = Math.max(0, Math.min(ld.GetHeightMM(), p.Y));
      var now = wc.m_oDrawingDocument.ConvertCoordsToCursorWR(px, py, p.Page);
      scrollBy(now.X - x, now.Y - y);
      // Scrolling past the slide's edge makes the editor switch slides.
      if (currentSlide() !== slide && a.goToPage) a.goToPage(slide);
    } catch (err) {}
  }

  // ---- CSS preview while pinching ------------------------------------------

  function preview(scale, tx, ty) {
    var mv = mainView();
    if (!mv) return;
    mv.style.transformOrigin = pinch.ox + 'px ' + pinch.oy + 'px';
    mv.style.transform = 'translate(' + tx + 'px,' + ty + 'px) scale(' + scale + ')';
  }

  function clearPreview() {
    var mv = mainView();
    if (mv) { mv.style.transform = ''; mv.style.transformOrigin = ''; }
  }

  // ---- gesture bookkeeping -------------------------------------------------

  function block(e) {
    e.stopImmediatePropagation();
    if (e.cancelable) e.preventDefault();
  }

  function first() {
    for (var k in pointers) return { id: Number(k), p: pointers[k] };
    return null;
  }

  function two() {
    var out = [];
    for (var k in pointers) { out.push(pointers[k]); if (out.length === 2) break; }
    return out;
  }

  function stopFling() {
    if (fling) cancelAnimationFrame(fling);
    fling = 0;
  }

  function startPinch() {
    var mv = mainView();
    var f = two();
    var mx = (f[0].x + f[1].x) / 2, my = (f[0].y + f[1].y) / 2;
    var rect = mv.getBoundingClientRect();
    pinch = {
      d0: Math.max(1, Math.hypot(f[0].x - f[1].x, f[0].y - f[1].y)),
      mx0: mx, my0: my, mx: mx, my: my, scale: 1,
      ox: mx - rect.left, oy: my - rect.top,
      zoom0: zoomValue(),
      doc: null,
    };
    try { pinch.doc = docPoint(mx, my); } catch (err) {}
  }

  function updatePinch() {
    var f = two();
    if (f.length < 2) return;
    var d = Math.hypot(f[0].x - f[1].x, f[0].y - f[1].y);
    var scale = d / pinch.d0;
    var z = pinch.zoom0 * scale;
    if (z < MIN_ZOOM) scale = MIN_ZOOM / pinch.zoom0;
    if (z > MAX_ZOOM) scale = MAX_ZOOM / pinch.zoom0;
    pinch.scale = scale;
    pinch.mx = (f[0].x + f[1].x) / 2;
    pinch.my = (f[0].y + f[1].y) / 2;
    preview(scale, pinch.mx - pinch.mx0, pinch.my - pinch.my0);
  }

  function endPinch() {
    if (!pinch) return;
    var p = pinch;
    pinch = null;
    clearPreview();
    var z = p.zoom0 * p.scale;
    if (Math.abs(p.scale - 1) < 0.03) {
      // Essentially a two-finger pan.
      scrollBy(p.mx0 - p.mx, p.my0 - p.my);
    } else {
      zoomAround(z, p.doc, p.mx, p.my);
    }
  }

  // One-finger pan. When the whole slide fits on screen the editor shows
  // exactly one slide and any vertical scroll means "next slide", so there the
  // slide follows the finger and the release decides (swipe). When zoomed in,
  // the view scrolls inside the slide, changing slide at most once per drag.
  var pan = null;

  function slideFitsVertically() {
    try {
      var r = control().m_oDrawingDocument.SlideCurrectRect;
      return (r.bottom - r.top) <= mainView().clientHeight + 2;
    } catch (err) {
      return true;
    }
  }

  function currentSlide() {
    var wc = control();
    return wc && wc.m_oDrawingDocument ? wc.m_oDrawingDocument.SlideCurrent : 0;
  }

  function beginPan() {
    pan = { slide0: currentSlide(), swipe: slideFitsVertically(), dy: 0, over: 0 };
  }

  function queuePan(dx, dy) {
    if (pan.swipe) {
      pan.dy += dy;
      dy = 0;
      var mv = mainView();
      // pan.dy is in scroll direction: positive = finger moved up = next slide.
      if (mv) mv.style.transform = 'translateY(' + (-pan.dy * 0.85) + 'px)';
    }
    panPending.x += dx;
    panPending.y += dy;
    if (panFrame) return;
    panFrame = requestAnimationFrame(flushPan);
  }

  function flushPan() {
    panFrame = 0;
    var x = panPending.x, y = panPending.y;
    panPending.x = panPending.y = 0;
    if (!pan || pan.swipe) { scrollBy(x, y); return; }
    // Moving back after pulling past the edge first undoes the pull.
    if (pan.over && y && (y > 0) !== (pan.over > 0)) {
      var undo = Math.sign(y) * Math.min(Math.abs(y), Math.abs(pan.over));
      pan.over += undo;
      y -= undo;
    }
    pan.over += scrollBy(x, y);
    // The slide shows a little resistance while pulled past its edge.
    var mv = mainView();
    if (mv) mv.style.transform = pan.over ? 'translateY(' + (-Math.max(-90, Math.min(90, pan.over * 0.4))) + 'px)' : '';
  }

  function endPan(cancelled) {
    if (!pan) return;
    var p = pan;
    pan = null;
    if (panFrame) { cancelAnimationFrame(panFrame); flushPan(); }
    if (p.swipe) {
      var mv = mainView();
      if (mv) mv.style.transform = '';
      if (cancelled) return;
      var h = mv ? mv.clientHeight : 600;
      var fast = performance.now() - velocity.t < 80 ? velocity.y : 0;
      var dir = 0;
      if (p.dy > Math.min(120, h * 0.18) || fast > 0.6) dir = 1;
      else if (p.dy < -Math.min(120, h * 0.18) || fast < -0.6) dir = -1;
      var a = api();
      if (dir && a && a.goToPage) {
        var next = Math.max(0, Math.min(a.getCountPages() - 1, p.slide0 + dir));
        console.log('[gestures] swipe to slide ' + (next + 1));
        if (next !== p.slide0) a.goToPage(next);
      }
      return;
    }
    var mvz = mainView();
    if (mvz) mvz.style.transform = '';
    if (cancelled) return;
    var a2 = api();
    if (Math.abs(p.over) > 140 && a2 && a2.goToPage) {
      var to = Math.max(0, Math.min(a2.getCountPages() - 1, p.slide0 + (p.over > 0 ? 1 : -1)));
      if (to !== p.slide0) a2.goToPage(to);
      return;
    }
    startFling();
  }

  function startFling() {
    var vx = velocity.x, vy = velocity.y; // px per ms, in scroll direction
    if (Math.hypot(vx, vy) < 0.3 || performance.now() - velocity.t > 80) return;
    var last = performance.now();
    function step(now) {
      var dt = Math.min(32, now - last);
      last = now;
      if (vx) scrollBy(vx * dt, 0);
      if (vy && scrollBy(0, vy * dt)) vy = 0; // stop at the slide's edge
      var decay = Math.pow(0.995, dt);
      vx *= decay; vy *= decay;
      fling = Math.hypot(vx, vy) > 0.05 ? requestAnimationFrame(step) : 0;
    }
    fling = requestAnimationFrame(step);
  }

  function doubleTapZoom(x, y) {
    var a = api();
    if (!a) return;
    var fitted = control().m_nZoomType === 2 || control().m_nZoomType === 1;
    if (fitted || zoomValue() < 90) {
      var p = null;
      try { p = docPoint(x, y); } catch (err) {}
      zoomAround(100, p, x, y);
    } else if (typeof a.zoomFitToWidth === 'function') {
      a.zoomFitToWidth();
    }
  }

  // ---- event handlers ------------------------------------------------------

  function onDown(e) {
    if (replaying || e.pointerType !== 'touch') return;
    if (count === 0 && !inSlideArea(e)) return;
    stopFling();
    pointers[e.pointerId] = { x: e.clientX, y: e.clientY, sx: e.clientX, sy: e.clientY, target: e.target, t: performance.now() };
    count++;

    if (count === 1) {
      if (editorBusyWithPointer() || onSelection(e.clientX, e.clientY)) {
        mode = 'sdk';
        return;
      }
      mode = 'pending';
      block(e);
      var id = e.pointerId;
      longTimer = setTimeout(function () {
        longTimer = 0;
        var p = pointers[id];
        if (mode !== 'pending' || !p) return;
        // Long press: hand the finger to the editor from here on.
        mode = 'sdk';
        replay('pointerdown', p, id, p.sx, p.sy);
        if (p.x !== p.sx || p.y !== p.sy) replay('pointermove', p, id, p.x, p.y);
      }, LONG_PRESS);
      return;
    }

    if (count === 2) {
      clearTimeout(longTimer);
      if (mode === 'pan') endPan(true);
      if (mode === 'sdk') {
        // The editor has the first finger; finish its action where it is.
        var f = first();
        if (f && f.id !== e.pointerId) replay('pointerup', f.p, f.id, f.p.x, f.p.y);
      }
      mode = 'pinch';
      startPinch();
    }
    block(e);
  }

  function onMove(e) {
    if (replaying) return;
    var p = pointers[e.pointerId];
    if (!p) return;
    var dx = e.clientX - p.x, dy = e.clientY - p.y;
    p.x = e.clientX;
    p.y = e.clientY;

    switch (mode) {
      case 'sdk':
        return;
      case 'pending':
        if (Math.hypot(p.x - p.sx, p.y - p.sy) > SLOP) {
          clearTimeout(longTimer);
          mode = 'pan';
          velocity = { x: 0, y: 0, t: 0 };
          beginPan();
          queuePan(p.sx - p.x, p.sy - p.y);
        }
        break;
      case 'pan': {
        queuePan(-dx, -dy);
        var now = performance.now();
        var dt = Math.max(1, now - (velocity.t || now - 16));
        velocity = { x: 0.8 * (-dx / dt) + 0.2 * velocity.x, y: 0.8 * (-dy / dt) + 0.2 * velocity.y, t: now };
        break;
      }
      case 'pinch':
        updatePinch();
        break;
    }
    block(e);
  }

  function onUp(e) {
    if (replaying) return;
    var p = pointers[e.pointerId];
    if (!p) return;
    delete pointers[e.pointerId];
    count--;
    var cancelled = e.type === 'pointercancel';

    switch (mode) {
      case 'sdk':
        if (count === 0) mode = 'idle';
        return;
      case 'pending':
        clearTimeout(longTimer);
        mode = 'idle';
        block(e);
        if (cancelled) return;
        // A tap: give it to the editor exactly as it happened.
        replay('pointerdown', p, e.pointerId, p.sx, p.sy);
        replay('pointerup', p, e.pointerId, p.sx, p.sy);
        var now = performance.now();
        if (lastTap && now - lastTap.t < 320 && Math.hypot(p.sx - lastTap.x, p.sy - lastTap.y) < 40
            && !onAnyObject(p.sx, p.sy)) {
          lastTap = null;
          doubleTapZoom(p.sx, p.sy);
        } else {
          lastTap = { t: now, x: p.sx, y: p.sy };
        }
        return;
      case 'pan':
        block(e);
        if (count === 0) {
          mode = 'idle';
          endPan(cancelled);
          velocity = { x: 0, y: 0, t: 0 };
        }
        return;
      case 'pinch':
        block(e);
        endPinch();
        mode = count === 0 ? 'idle' : 'rest';
        return;
      case 'rest':
        block(e);
        if (count === 0) mode = 'idle';
        return;
    }
  }

  // Swallow the touch events that go with the pointers this layer owns, so
  // nothing behind it reacts to them either.
  function onTouch(e) {
    if (mode === 'pending' || mode === 'pan' || mode === 'pinch' || mode === 'rest') {
      e.stopImmediatePropagation();
      if (e.cancelable) e.preventDefault();
    }
  }

  console.log('[gestures] ready');
  var opts = { capture: true, passive: false };
  window.addEventListener('pointerdown', onDown, opts);
  window.addEventListener('pointermove', onMove, opts);
  window.addEventListener('pointerup', onUp, opts);
  window.addEventListener('pointercancel', onUp, opts);
  ['touchstart', 'touchmove', 'touchend', 'touchcancel'].forEach(function (t) {
    window.addEventListener(t, onTouch, opts);
  });
})();
