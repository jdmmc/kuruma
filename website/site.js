// 言語。英語が既定で、選んだ言語は次のページにも持ち越す
const ja = () => document.documentElement.getAttribute('data-lang') === 'ja';

function applyLang(lang) {
  const root = document.documentElement;
  root.setAttribute('data-lang', lang);
  root.lang = lang;
  document.title = document.body.dataset[lang === 'ja' ? 'titleJa' : 'titleEn'] || document.title;
  document.querySelectorAll('img[data-alt-ja]').forEach(img => {
    if (!img.dataset.altEn) img.dataset.altEn = img.alt;
    img.alt = lang === 'ja' ? img.dataset.altJa : img.dataset.altEn;
  });
  try { localStorage.setItem('kuruma-lang', lang); } catch (e) {}
  if (typeof charts === 'function') charts();
}

// トルク曲線。KurumaMod の CarSpec#torqueAt と同じ式（最大トルク回転数を頂点にした放物線）
function torqueAt(c, rpm) {
  const x = c.rp / c.rt;
  const f = Math.min(0.9, Math.max(0.1, 1 / ((x - 1) * (3 * x - 1))));
  const o = (rpm - c.rt) / c.rt;
  return Math.max(0, c.tq * (1 - f * o * o));
}

function drawChart(el) {
  const c = { tq: +el.dataset.tq, rt: +el.dataset.rt, rp: +el.dataset.rp, red: +el.dataset.red, idle: 800 };
  const W = 720, H = 380, L = 56, R = 64, T = 24, B = 44;
  const xMax = Math.ceil((c.red + 500) / 1000) * 1000;
  const pts = [];
  let peakP = { v: 0, r: 0 };
  for (let r = c.idle; r <= c.red; r += 25) {
    const t = torqueAt(c, r), p = t * r * 2 * Math.PI / 60 / 735.5;
    pts.push([r, t, p]);
    if (p > peakP.v) peakP = { v: p, r };
  }
  const tMax = Math.ceil(c.tq * 1.25 / 100) * 100;
  const pMax = Math.ceil(peakP.v * 1.25 / 50) * 50;
  const X = r => L + (W - L - R) * r / xMax;
  const Yt = t => H - B - (H - T - B) * t / tMax;
  const Yp = p => H - B - (H - T - B) * p / pMax;
  const line = (fn) => pts.map((p, i) => `${i ? 'L' : 'M'}${X(p[0]).toFixed(1)},${fn(p).toFixed(1)}`).join('');

  let g = '';
  g += `<rect class="red" x="${X(c.red)}" y="${T}" width="${X(xMax) - X(c.red)}" height="${H - T - B}"/>`;
  for (let i = 0; i <= 4; i++) {
    const y = T + (H - T - B) * i / 4;
    g += `<line class="ax" x1="${L}" x2="${W - R}" y1="${y}" y2="${y}"/>`;
    g += `<text x="${L - 10}" y="${y + 4}" text-anchor="end">${Math.round(tMax * (4 - i) / 4)}</text>`;
    g += `<text x="${W - R + 10}" y="${y + 4}">${Math.round(pMax * (4 - i) / 4)}</text>`;
  }
  for (let r = 0; r <= xMax; r += 1000) {
    g += `<text x="${X(r)}" y="${H - B + 22}" text-anchor="middle">${r / 1000}</text>`;
  }
  g += `<text x="${(L + W - R) / 2}" y="${H - 4}" text-anchor="middle">${ja() ? 'エンジン回転数 ×1000 rpm' : 'Engine speed ×1000 rpm'}</text>`;
  g += `<text x="${L - 10}" y="${T - 10}" text-anchor="end">N·m</text>`;
  g += `<text x="${W - R + 10}" y="${T - 10}">PS</text>`;
  g += `<path class="pw" d="${line(p => Yp(p[2]))}"/>`;
  g += `<path class="tq" d="${line(p => Yt(p[1]))}"/>`;
  g += `<circle class="dot" cx="${X(c.rt)}" cy="${Yt(c.tq)}" r="5"/>`;
  g += `<text class="lbl" x="${X(c.rt)}" y="${Yt(c.tq) - 14}" text-anchor="middle">${c.tq} N·m</text>`;
  g += `<circle class="dot" cx="${X(peakP.r)}" cy="${Yp(peakP.v)}" r="5"/>`;
  g += `<text class="lbl" x="${X(peakP.r)}" y="${Yp(peakP.v) - 14}" text-anchor="middle">${Math.round(peakP.v)} PS</text>`;
  g += `<text x="${(X(c.red) + X(xMax)) / 2}" y="${T + 18}" text-anchor="middle">${ja() ? 'レッドゾーン' : 'Redline'}</text>`;

  el.querySelector('svg').innerHTML = g;
}
const charts = () => document.querySelectorAll('.chart[data-tq]').forEach(drawChart);

// モデルの絞り込み（トップ）
document.querySelectorAll('.filters button').forEach(b => b.addEventListener('click', () => {
  document.querySelectorAll('.filters button').forEach(x => x.setAttribute('aria-pressed', String(x === b)));
  const f = b.dataset.filter;
  document.querySelectorAll('.model').forEach(m => { m.hidden = f !== 'all' && !m.dataset.kind.split(' ').includes(f); });
}));

// 比較表：同じ値の行を出すか
const same = document.getElementById('show-same');
if (same) same.addEventListener('change', () => document.querySelector('.compare').classList.toggle('show-same', same.checked));


applyLang(ja() ? 'ja' : 'en');
const toggle = document.getElementById('lang-toggle');
if (toggle) toggle.addEventListener('click', () => applyLang(ja() ? 'en' : 'ja'));

// 狭い画面のメニュー。ボタンで開き、リンクを押すか Esc か外を押すと閉じる
const bar = document.querySelector('.bar');
const menuBtn = bar && bar.querySelector('.menu-btn');
if (menuBtn) {
  const setOpen = open => { bar.classList.toggle('open', open); menuBtn.setAttribute('aria-expanded', String(open)); };
  menuBtn.addEventListener('click', e => { e.stopPropagation(); setOpen(!bar.classList.contains('open')); });
  bar.querySelectorAll('.menu-links a').forEach(a => a.addEventListener('click', () => setOpen(false)));
  document.addEventListener('keydown', e => { if (e.key === 'Escape') setOpen(false); });
  document.addEventListener('click', e => { if (!bar.contains(e.target)) setOpen(false); });
}

// スクロールしたらヘッダーに黒い帯を敷く（写真の上では透明）
if (bar) {
  const onScroll = () => bar.classList.toggle('scrolled', window.scrollY > 8);
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
}
