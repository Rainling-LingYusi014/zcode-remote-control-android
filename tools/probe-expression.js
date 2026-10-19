(async () => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const out = { url: location.href, title: document.title, ts: Date.now() };
  const RE = /已完成|运行中|错误|失败|等待|排队|进行中/;
  const ownText = (el) =>
    [...el.childNodes].filter((n) => n.nodeType === 3).map((n) => n.textContent).join(' ').trim();

  // 1) 状态徽章 = 直接持有状态文本节点的元素（允许内部有图标）
  const badges = [...document.querySelectorAll('body *')].filter((el) => RE.test(ownText(el)));
  out.badgeCount = badges.length;

  // 2) 每个徽章向上找任务卡片，记录结构；第一个卡片给 outerHTML 原文
  const seen = new Set();
  out.cards = [];
  for (const b of badges) {
    let card = b;
    for (let i = 0; i < 6 && card && card.parentElement; i++) {
      card = card.parentElement;
      const t = card.textContent || '';
      if (RE.test(t) && t.length > 8 && t.length < 400) break;
    }
    if (!card) continue;
    const attrs = {};
    for (const a of card.attributes) attrs[a.name] = String(a.value).slice(0, 100);
    const key = JSON.stringify([card.tagName, card.className, attrs]);
    if (seen.has(key)) continue;
    seen.add(key);
    out.cards.push({
      badgeOwnText: ownText(b).slice(0, 20),
      badgeClass: String(b.className).slice(0, 180),
      cardTag: card.tagName,
      cardClass: String(card.className).slice(0, 220),
      cardAttrs: attrs,
      cardText: (card.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 150),
    });
    if (out.cards.length >= 8) break;
  }
  if (badges.length > 0) {
    let card0 = badges[0];
    for (let i = 0; i < 6 && card0 && card0.parentElement; i++) {
      card0 = card0.parentElement;
      const t = card0.textContent || '';
      if (RE.test(t) && t.length > 8 && t.length < 400) break;
    }
    out.firstCardHTML = card0 ? card0.outerHTML.replace(/\s+/g, ' ').slice(0, 1000) : null;
  }

  // 3) body 顶层骨架（4 层、每层最多 6 个子节点）
  out.outline = (function walk(el, depth) {
    if (depth > 4 || !el) return null;
    const kids = [...el.children].slice(0, 6).map((c) => walk(c, depth + 1));
    const label = el.tagName + (el.id ? '#' + el.id : '') +
      (el.className && typeof el.className === 'string' ? '.' + el.className.trim().slice(0, 70) : '');
    return { el: label, nChildren: el.children.length, kids };
  })(document.body, 0);

  // 4) 5 秒 DOM 变更频度基线
  let count = 0;
  const mo = new MutationObserver((ms) => { count += ms.length; });
  mo.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true });
  await sleep(5000);
  mo.disconnect();
  out.mutationsIn5s = count;
  out.totalElements = document.querySelectorAll('*').length;
  return out;
})()
