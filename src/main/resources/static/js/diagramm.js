// Values under the pointer for all charts of the pages with the frame. Two kinds of elements:
// - data-verlauf: JSON {von, bis, reihen: [{n, e, s, t: [ms], w: [values or null]}]} on a box; its first element
//   with data-plot (or the box itself) maps the x position linearly from von to bis. Shows a line at the pointer in
//   every data-plot and the value of each series at the nearest time.
// - data-wert: a bar or point; shows its text.
// The listeners sit on the document, so charts replaced by the update in place keep working.
(() => {
  const tip = document.createElement('div');
  tip.className = 'dtip';
  tip.hidden = true;
  document.body.appendChild(tip);
  const cache = new WeakMap();
  const zahl = (w, s) => w == null ? '–' : w.toLocaleString('de-DE', {minimumFractionDigits: s, maximumFractionDigits: s});
  const uhr = t => new Date(t).toLocaleTimeString('de-DE', {hour: '2-digit', minute: '2-digit'});
  const esc = x => String(x).replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'})[c]);

  const zeige = (x, y, html) => {
    tip.innerHTML = html;
    tip.hidden = false;
    const r = tip.getBoundingClientRect();
    let left = x + 14, top = y + 14;
    if (left + r.width > innerWidth - 8) left = x - r.width - 14;
    if (top + r.height > innerHeight - 8) top = y - r.height - 14;
    tip.style.left = Math.max(8, left) + 'px';
    tip.style.top = Math.max(8, top) + 'px';
  };
  const linienWeg = () => document.querySelectorAll('.dlinie').forEach(l => l.remove());
  const verberge = () => { tip.hidden = true; linienWeg(); };

  // Index of the time nearest to t in the sorted times
  const naechster = (ts, t) => {
    let lo = 0, hi = ts.length - 1;
    while (hi - lo > 1) {
      const mitte = (lo + hi) >> 1;
      if (ts[mitte] < t) lo = mitte; else hi = mitte;
    }
    return Math.abs(ts[lo] - t) <= Math.abs(ts[hi] - t) ? lo : hi;
  };

  const verlauf = (box, e) => {
    let d = cache.get(box);
    if (!d || d.roh !== box.dataset.verlauf) {
      try { d = {roh: box.dataset.verlauf, daten: JSON.parse(box.dataset.verlauf)}; } catch (x) { return false; }
      cache.set(box, d);
    }
    const daten = d.daten;
    const plots = box.querySelectorAll('[data-plot]');
    const ziel = plots.length ? plots[0] : box;
    const r = ziel.getBoundingClientRect();
    const anteil = (e.clientX - r.left) / r.width;
    if (anteil < 0 || anteil > 1) { verberge(); return true; }
    const t = daten.von + anteil * (daten.bis - daten.von);
    let zeilen = '', zeit = null;
    for (const reihe of daten.reihen) {
      if (!reihe.t.length) continue;
      const i = naechster(reihe.t, t);
      if (zeit == null) zeit = reihe.t[i];
      zeilen += '<div><span>' + esc(reihe.n) + '</span><b>' + zahl(reihe.w[i], reihe.s) + (reihe.e ? ' ' + esc(reihe.e) : '') + '</b></div>';
    }
    if (!zeilen) { verberge(); return true; }
    linienWeg();
    (plots.length ? plots : [box]).forEach(p => {
      if (p instanceof SVGElement && !(p instanceof SVGSVGElement)) return;
      const pr = p.getBoundingClientRect();
      const linie = document.createElement('div');
      linie.className = 'dlinie';
      linie.style.left = (pr.left + anteil * pr.width) + 'px';
      linie.style.top = pr.top + 'px';
      linie.style.height = pr.height + 'px';
      document.body.appendChild(linie);
    });
    zeige(e.clientX, e.clientY, '<div class="dtip-zeit">' + uhr(zeit) + '</div>' + zeilen);
    return true;
  };

  const bewegt = e => {
    const punkt = e.target.closest && e.target.closest('[data-wert]');
    if (punkt) { linienWeg(); zeige(e.clientX, e.clientY, esc(punkt.dataset.wert)); return; }
    const box = e.target.closest && e.target.closest('[data-verlauf]');
    if (box && verlauf(box, e)) return;
    verberge();
  };
  document.addEventListener('pointermove', bewegt);
  document.addEventListener('pointerdown', bewegt);
  document.addEventListener('scroll', verberge, {passive: true});
  document.addEventListener('pointerleave', verberge);
  document.documentElement.addEventListener('mouseleave', verberge);
})();
