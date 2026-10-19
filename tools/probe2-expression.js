(async () => {
  const out = { url: location.href, title: document.title };
  out.bodyTextSample = (document.body.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 400);
  out.iframes = [...document.querySelectorAll('iframe')].map((f) => ({
    src: (f.src || '').slice(0, 150),
    w: f.clientWidth, h: f.clientHeight,
    accessible: (() => { try { return !!f.contentDocument; } catch (e) { return 'CROSS-ORIGIN'; } })(),
  }));
  out.framesCount = window.frames.length;
  out.shadowHosts = [...document.querySelectorAll('*')].filter((el) => el.shadowRoot).length;
  out.topLevelStructure = [...document.body.children].map(
    (el) => el.tagName + (el.id ? '#' + el.id : '') + '.' + String(el.className).slice(0, 80)
  );
  // 抓所有可见文本节点的前几个，判断当前视图内容
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const texts = [];
  let n;
  while ((n = walker.nextNode()) && texts.length < 15) {
    const t = (n.textContent || '').trim();
    if (t.length > 1) texts.push(t.slice(0, 60));
  }
  out.textNodes = texts;
  return out;
})()
