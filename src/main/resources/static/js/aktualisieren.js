// Updates the page in place every n seconds (data-aktualisieren on <body>) instead of reloading it: fetches the same
// address and morphs <main> with idiomorph, so scroll position, an open tooltip, folded areas and the course dialog
// stay as they are. Not in a hidden tab, at once when it is shown again. Without a network the page stays as it is.
(() => {
  const sekunden = Number(document.body.dataset.aktualisieren);
  if (!sekunden || typeof Idiomorph === 'undefined') return;
  let laeuft = false;
  const aktualisieren = async () => {
    if (document.hidden || laeuft) return;
    laeuft = true;
    try {
      const antwort = await fetch(location.href, {cache: 'no-store', credentials: 'same-origin'});
      if (!antwort.ok) return;
      const neu = new DOMParser().parseFromString(await antwort.text(), 'text/html');
      const main = neu.querySelector('main');
      if (!main) return;
      Idiomorph.morph(document.querySelector('main'), main, {
        callbacks: {
          // Whether a folded area is open belongs to the reader, not to the server
          beforeAttributeUpdated: (name, node) => !(name === 'open' && node.tagName === 'DETAILS')
        }
      });
      // An area the server opens because of a problem opens here too
      document.querySelectorAll('details[data-erzwingen]').forEach(d => { d.open = true; });
      document.title = neu.title;
    } catch (e) {
      // no answer, keep the page
    } finally {
      laeuft = false;
    }
  };
  setInterval(aktualisieren, sekunden * 1000);
  document.addEventListener('visibilitychange', aktualisieren);
})();
