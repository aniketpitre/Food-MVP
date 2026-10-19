/**
 * The admin dashboard page served at GET /admin. One self-contained file (inline CSS and
 * JS, nothing loaded from elsewhere) so it works behind any proxy and needs no build step.
 * It holds no data: it signs in like the app does and reads the admin endpoints.
 *
 * The script avoids backticks, "${" and backslashes so it can live inside this template
 * string as it is; strings are joined with + instead.
 */
export const DASHBOARD_HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>WorthAbite Admin</title>
<style>
:root {
  --espresso: #2B1B12; --saffron: #F5C518; --cream: #FBF7EF; --paprika: #E2531E;
  --card: #FFFFFF; --ink: #2B1B12; --muted: #7A6A5E; --line: #EADFCC; --soft: #F4ECDD;
  --love: #E2531E; --pass: #3E7CB1; --good: #2E7D4F;
}
* { box-sizing: border-box; }
html, body { margin: 0; background: var(--cream); color: var(--ink); }
body { font: 14px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
a { color: var(--paprika); text-decoration: none; cursor: pointer; }
a:hover { text-decoration: underline; }
header { background: var(--espresso); color: var(--cream); padding: 10px 16px; display: flex; align-items: center; gap: 12px; position: sticky; top: 0; z-index: 5; }
header .logo { font-weight: 800; font-size: 17px; letter-spacing: .2px; }
header .logo b { color: var(--saffron); }
header .who { margin-left: auto; font-size: 13px; opacity: .9; display: flex; gap: 10px; align-items: center; }
header button { background: transparent; color: var(--cream); border: 1px solid rgba(251,247,239,.4); }
nav { display: flex; gap: 4px; overflow-x: auto; padding: 8px 16px 0; background: var(--espresso); position: sticky; top: 46px; z-index: 4; scrollbar-width: none; }
nav button { background: transparent; color: rgba(251,247,239,.75); border: 0; border-radius: 8px 8px 0 0; padding: 8px 12px; white-space: nowrap; font-weight: 600; }
nav button.on { background: var(--cream); color: var(--espresso); box-shadow: inset 0 3px 0 var(--saffron); }
main { max-width: 1200px; margin: 0 auto; padding: 16px; }
h2 { font-size: 20px; margin: 4px 0 12px; }
h3 { font-size: 15px; margin: 0 0 10px; }
.card { background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 14px; margin-bottom: 14px; }
.grid { display: grid; gap: 10px; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); }
.two { display: grid; gap: 14px; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); }
.tile { background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 10px 12px; }
.tile .n { font-size: 22px; font-weight: 800; }
.tile .l { font-size: 12px; color: var(--muted); }
.tile .s { font-size: 12px; color: var(--muted); }
.note { color: var(--muted); font-size: 13px; }
.explain { background: var(--soft); border-left: 4px solid var(--saffron); padding: 10px 12px; border-radius: 6px; font-size: 13px; margin: 8px 0 12px; }
.scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
table { border-collapse: collapse; width: 100%; font-size: 13px; }
th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid var(--line); vertical-align: top; }
th { font-size: 12px; color: var(--muted); font-weight: 600; white-space: nowrap; background: #FFFDF8; position: sticky; top: 0; }
td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
tr.ignored td { color: #A99A8C; text-decoration: line-through; text-decoration-color: rgba(226,83,30,.5); }
tr.ignored td.why { text-decoration: none; color: var(--paprika); }
button, .btn { font: inherit; font-size: 13px; border-radius: 8px; padding: 6px 12px; border: 1px solid var(--espresso); background: var(--espresso); color: var(--cream); cursor: pointer; font-weight: 600; }
button.ghost { background: transparent; color: var(--espresso); }
button.warn { background: var(--paprika); border-color: var(--paprika); }
button.small { padding: 3px 8px; font-size: 12px; }
button:disabled { opacity: .5; cursor: default; }
input, select { font: inherit; padding: 7px 9px; border: 1px solid #D8CBB5; border-radius: 8px; background: #fff; color: var(--ink); min-width: 0; }
input[type=number] { width: 110px; }
.row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 10px; }
.row label { font-size: 12px; color: var(--muted); display: flex; flex-direction: column; gap: 3px; }
.badge { display: inline-block; font-size: 11px; font-weight: 700; padding: 1px 7px; border-radius: 99px; background: var(--soft); color: var(--espresso); white-space: nowrap; }
.badge.love { background: #FCE3D9; color: #9C3510; }
.badge.pass { background: #DDE9F4; color: #24557F; }
.badge.ok { background: #DCEFE3; color: #1F5A38; }
.badge.warn { background: #FFF2C2; color: #6B5300; }
.badge.bad { background: #FBD9CC; color: #9C3510; }
.thumb { width: 56px; height: 56px; object-fit: cover; border-radius: 8px; background: var(--soft); display: block; }
.photos { display: grid; gap: 10px; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); }
.photo { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; background: #fff; font-size: 12px; }
.photo img { width: 100%; aspect-ratio: 1; object-fit: cover; display: block; background: var(--soft); }
.photo div { padding: 6px 8px; display: flex; gap: 4px; flex-wrap: wrap; }
.pair { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.pair img { width: 100%; aspect-ratio: 1; object-fit: cover; border-radius: 8px; background: var(--soft); }
.chart svg { width: 100%; max-width: 640px; height: auto; display: block; }
.chart .legend { display: flex; gap: 14px; font-size: 12px; color: var(--muted); margin-top: 4px; }
.chart .legend i { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 5px; vertical-align: -1px; }
.bar-hit:hover + rect, .bar-hit:hover { opacity: .8; }
#login { max-width: 380px; margin: 60px auto; }
#login .card { padding: 22px; }
#login h1 { margin: 0 0 4px; font-size: 22px; }
#login form { display: flex; flex-direction: column; gap: 10px; margin-top: 14px; }
.error { color: #B33A0F; font-size: 13px; min-height: 18px; }
#drawer { position: fixed; inset: 0; background: rgba(43,27,18,.45); z-index: 20; display: none; }
#drawer.open { display: block; }
#drawer .panel { position: absolute; right: 0; top: 0; bottom: 0; width: min(980px, 100%); background: var(--cream); overflow-y: auto; padding: 16px; box-shadow: -8px 0 30px rgba(0,0,0,.2); }
#drawer .close { position: sticky; top: 0; float: right; z-index: 2; background: var(--cream); box-shadow: 0 2px 8px rgba(43,27,18,.15); }
.toast { position: fixed; bottom: 16px; left: 50%; transform: translateX(-50%); background: var(--espresso); color: var(--cream); padding: 10px 16px; border-radius: 10px; z-index: 30; font-size: 13px; max-width: 90vw; }
.log { background: #1E130D; color: #F3E9D8; font: 12px/1.5 ui-monospace, Menlo, monospace; padding: 8px 10px; border-radius: 8px; max-height: 180px; overflow: auto; white-space: pre-wrap; }
.kv { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; font-size: 13px; }
.kv b { color: var(--muted); font-weight: 600; }
.formula { font: 12px/1.6 ui-monospace, Menlo, monospace; background: #fff; border: 1px dashed #D8CBB5; border-radius: 8px; padding: 8px 10px; overflow-x: auto; }
@media (max-width: 640px) {
  main { padding: 12px; }
  .tile .n { font-size: 19px; }
  #drawer .panel { padding: 12px; }
  .pair { grid-template-columns: 1fr 1fr; }
}
</style>
</head>
<body>
<div id="login" hidden>
  <div class="card">
    <h1>WorthAbite <span style="color:var(--paprika)">admin</span></h1>
    <div class="note">Sign in with an admin account.</div>
    <form id="loginForm">
      <input id="lgLogin" autocomplete="username" placeholder="Email or username" required>
      <input id="lgPassword" type="password" autocomplete="current-password" placeholder="Password" required>
      <button type="submit">Sign in</button>
      <div class="error" id="lgError"></div>
    </form>
    <form id="codeForm" hidden>
      <div class="note" id="codeHint">We emailed you a 6-digit code.</div>
      <input id="lgCode" inputmode="numeric" autocomplete="one-time-code" placeholder="6-digit code" maxlength="6" required>
      <button type="submit">Verify</button>
      <div class="error" id="codeError"></div>
    </form>
  </div>
</div>
<div id="denied" hidden>
  <div id="login2" style="max-width:380px;margin:60px auto"><div class="card"><h1 style="margin:0 0 6px;font-size:22px">Admins only</h1>
  <p class="note">This account isn't an admin. Admins are the accounts whose email is in ADMIN_EMAILS on the server.</p>
  <button id="deniedOut">Sign in with another account</button></div></div>
</div>
<div id="app" hidden>
  <header><span class="logo">Worth<b>A</b>bite admin</span><span class="who"><span id="whoami"></span><button class="small" id="signOut">Sign out</button></span></header>
  <nav id="tabs"></nav>
  <main id="main"></main>
</div>
<div id="drawer"><div class="panel"><button class="close ghost small" id="drawerClose">Close</button><div id="drawerBody"></div></div></div>
<script>
(function () {
  'use strict';
  var TOKEN_KEY = 'worthabite_admin_token';
  var token = null;
  try { token = sessionStorage.getItem(TOKEN_KEY); } catch (e) { token = null; }
  var me = null;
  var cities = null;
  var challengeId = null;
  var jobTimer = null;

  // ---------- small helpers ----------
  function $(id) { return document.getElementById(id); }
  function esc(v) {
    if (v === null || v === undefined) return '';
    return String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function num(v, d) {
    if (v === null || v === undefined || v === '') return '<span class="note">-</span>';
    var n = Number(v);
    if (d === undefined) return n.toLocaleString('en-IN');
    return n.toFixed(d);
  }
  function pct(v) { return v === null || v === undefined ? '-' : (Number(v) * 100).toFixed(1) + '%'; }
  function ago(t) {
    if (!t) return '';
    var s = (Date.now() - new Date(t).getTime()) / 1000;
    if (s < 0) return 'in ' + Math.round(-s / 60) + ' min';
    if (s < 90) return 'just now';
    if (s < 5400) return Math.round(s / 60) + ' min ago';
    if (s < 129600) return Math.round(s / 3600) + ' h ago';
    if (s < 86400 * 60) return Math.round(s / 86400) + ' d ago';
    return new Date(t).toLocaleDateString('en-IN');
  }
  function when(t) { return t ? '<span title="' + esc(new Date(t).toLocaleString('en-IN')) + '">' + esc(ago(t)) + '</span>' : ''; }
  function badge(text, kind) { return '<span class="badge ' + (kind || '') + '">' + esc(text) + '</span>'; }
  function kindBadge(k) {
    var names = { recommend: 'love', pass: 'not for me', save: 'save', tried: 'tried', share: 'share' };
    return badge(names[k] || k, k === 'recommend' ? 'love' : k === 'pass' ? 'pass' : '');
  }
  function statusBadge(s) { return badge(s, s === 'live' || s === 'active' ? 'ok' : s === 'pending' || s === 'open' ? 'warn' : s === 'hidden' || s === 'removed' ? 'bad' : ''); }
  function userLink(id, handle) { return id ? '<a data-user="' + esc(id) + '">@' + esc(handle) + '</a>' : '<span class="note">-</span>'; }
  function dishLink(id, name) { return '<a data-dish="' + esc(id) + '">' + esc(name) + '</a>'; }
  function tile(n, label, sub) { return '<div class="tile"><div class="n">' + n + '</div><div class="l">' + esc(label) + '</div>' + (sub ? '<div class="s">' + sub + '</div>' : '') + '</div>'; }
  function table(head, rows, empty) {
    if (!rows.length) return '<p class="note">' + esc(empty || 'Nothing here.') + '</p>';
    return '<div class="scroll"><table><thead><tr>' + head.map(function (h) {
      var n = h.charAt(0) === '#';
      return '<th' + (n ? ' class="num"' : '') + '>' + esc(n ? h.slice(1) : h) + '</th>';
    }).join('') + '</tr></thead><tbody>' + rows.join('') + '</tbody></table></div>';
  }
  function tr(cells, cls) { return '<tr' + (cls ? ' class="' + cls + '"' : '') + '>' + cells.join('') + '</tr>'; }
  function td(v) { return '<td>' + (v === null || v === undefined ? '' : v) + '</td>'; }
  function tdn(v) { return '<td class="num">' + v + '</td>'; }
  function toast(msg) {
    var t = document.createElement('div');
    t.className = 'toast'; t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(function () { t.remove(); }, 3500);
  }
  function qs(o) {
    var parts = [];
    Object.keys(o).forEach(function (k) { if (o[k] !== '' && o[k] !== null && o[k] !== undefined) parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(o[k])); });
    return parts.length ? '?' + parts.join('&') : '';
  }

  // Every call carries the token; a signed-out or non-admin answer sends you back.
  function api(path, opts) {
    opts = opts || {};
    var headers = {};
    if (token) headers.authorization = 'Bearer ' + token;
    if (opts.body !== undefined) headers['content-type'] = 'application/json';
    return fetch(path, { method: opts.method || 'GET', headers: headers, body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined })
      .then(function (res) {
        var fresh = res.headers.get('x-new-token');
        if (fresh) setToken(fresh);
        return res.text().then(function (text) {
          var data = null;
          try { data = text ? JSON.parse(text) : null; } catch (e) { data = { message: text }; }
          if (res.status === 401 && !opts.noAuthRedirect) { signOut(); throw new Error('Please sign in again.'); }
          if (res.status === 403 && !opts.noAuthRedirect) { showDenied(); throw new Error('Admins only.'); }
          if (!res.ok) throw new Error((data && data.message) || ('Request failed (' + res.status + ')'));
          return data;
        });
      });
  }
  function setToken(t) { token = t; try { if (t) sessionStorage.setItem(TOKEN_KEY, t); else sessionStorage.removeItem(TOKEN_KEY); } catch (e) {} }
  function act(path, body, msg) {
    return api(path, { method: 'POST', body: body || {} }).then(function (r) { toast(msg || 'Done.'); return r; })
      .catch(function (e) { toast(e.message); throw e; });
  }

  // ---------- charts: plain inline SVG ----------
  // Bars from the baseline, one tooltip per day; a stacked pair when two keys are given.
  function barChart(rows, keys, colors, labels, height) {
    var W = 420, H = height || 150, L = 30, B = 20, T = 8;
    var n = rows.length || 1;
    var max = 1;
    rows.forEach(function (r) { var s = 0; keys.forEach(function (k) { s += Number(r[k]) || 0; }); if (s > max) max = s; });
    var step = (W - L - 4) / n, bw = Math.max(2, step - 3);
    var y = function (v) { return (H - B) - (v / max) * (H - B - T); };
    var out = '<svg viewBox="0 0 ' + W + ' ' + H + '" role="img">';
    [0, 0.5, 1].forEach(function (f) {
      var v = Math.round(max * f), yy = y(v);
      out += '<line x1="' + L + '" x2="' + W + '" y1="' + yy + '" y2="' + yy + '" stroke="#EADFCC" stroke-width="1"/>';
      out += '<text x="' + (L - 6) + '" y="' + (yy + 4) + '" font-size="11" text-anchor="end" fill="#7A6A5E">' + v + '</text>';
    });
    rows.forEach(function (r, i) {
      var x = L + 2 + i * step, base = H - B, tip = r.day;
      keys.forEach(function (k, j) {
        var v = Number(r[k]) || 0;
        tip += '  ' + labels[j] + ': ' + v;
        if (!v) return;
        var h = (v / max) * (H - B - T);
        var gap = j > 0 ? 2 : 0;
        out += '<rect x="' + x + '" y="' + (base - h + gap) + '" width="' + bw + '" height="' + Math.max(0, h - gap) + '" rx="2" fill="' + colors[j] + '"/>';
        base = base - h;
      });
      out += '<rect class="bar-hit" x="' + (x - 1) + '" y="' + T + '" width="' + (step) + '" height="' + (H - B - T) + '" fill="transparent"><title>' + esc(tip) + '</title></rect>';
      if ((i % 7 === 0 && n - 1 - i >= 4) || i === n - 1) out += '<text x="' + (i === n - 1 ? x + bw : x + bw / 2) + '" y="' + (H - 6) + '" font-size="11" text-anchor="' + (i === n - 1 ? 'end' : 'middle') + '" fill="#7A6A5E">' + esc(String(r.day).slice(5)) + '</text>';
    });
    out += '</svg>';
    var legend = keys.length > 1 ? '<div class="legend">' + keys.map(function (k, j) { return '<span><i style="background:' + colors[j] + '"></i>' + esc(labels[j]) + '</span>'; }).join('') + '</div>' : '';
    return '<div class="chart">' + out + legend + '</div>';
  }

  // ---------- sign in ----------
  function showLogin() {
    $('app').hidden = true; $('denied').hidden = true; $('login').hidden = false;
    $('loginForm').hidden = false; $('codeForm').hidden = true;
  }
  function showDenied() { $('app').hidden = true; $('login').hidden = true; $('denied').hidden = false; }
  function signOut() {
    if (token) fetch('/auth/logout', { method: 'POST', headers: { authorization: 'Bearer ' + token } }).catch(function () {});
    setToken(null); me = null; showLogin();
  }
  function signedIn(res) {
    setToken(res.token);
    me = res.user || null;
    start();
  }
  $('loginForm').addEventListener('submit', function (e) {
    e.preventDefault();
    $('lgError').textContent = '';
    api('/auth/login', { method: 'POST', body: { login: $('lgLogin').value.trim(), password: $('lgPassword').value }, noAuthRedirect: true })
      .then(function (res) {
        if (res.twoFactor) {
          challengeId = res.challengeId;
          $('loginForm').hidden = true; $('codeForm').hidden = false;
          $('codeHint').textContent = 'We emailed a 6-digit code' + (res.emailHint ? ' to ' + res.emailHint : '') + '.';
          $('lgCode').focus();
          return;
        }
        signedIn(res);
      })
      .catch(function (err) { $('lgError').textContent = err.message; });
  });
  $('codeForm').addEventListener('submit', function (e) {
    e.preventDefault();
    $('codeError').textContent = '';
    api('/auth/login/verify', { method: 'POST', body: { challengeId: challengeId, code: $('lgCode').value.trim() }, noAuthRedirect: true })
      .then(signedIn)
      .catch(function (err) { $('codeError').textContent = err.message; });
  });
  $('signOut').addEventListener('click', signOut);
  $('deniedOut').addEventListener('click', signOut);

  // ---------- tabs ----------
  var TABS = [
    ['overview', 'Overview', overview],
    ['algorithm', 'Algorithm health', algorithm],
    ['recs', 'Recommendations', recs],
    ['ranking', 'Ranking settings', ranking],
    ['users', 'Users', users],
    ['places', 'Places', places],
    ['moderation', 'Moderation', moderation],
    ['activity', 'Activity', activity]
  ];
  var current = 'overview';
  function renderTabs() {
    $('tabs').innerHTML = TABS.map(function (t) { return '<button data-tab="' + t[0] + '" class="' + (t[0] === current ? 'on' : '') + '">' + esc(t[1]) + '</button>'; }).join('');
  }
  function go(tab) {
    current = tab;
    try { history.replaceState(null, '', '#' + tab); } catch (e) {}
    renderTabs();
    if (jobTimer) { clearInterval(jobTimer); jobTimer = null; }
    var t = TABS.filter(function (x) { return x[0] === tab; })[0] || TABS[0];
    $('main').innerHTML = '<p class="note">Loading...</p>';
    Promise.resolve().then(t[2]).catch(function (e) { $('main').innerHTML = '<div class="card error">' + esc(e.message) + '</div>'; });
  }
  function start() {
    // The overview doubles as the admin check: a non-admin gets 403 and sees "Admins only".
    api('/admin/overview').then(function (ov) {
      $('login').hidden = true; $('denied').hidden = true; $('app').hidden = false;
      return (me ? Promise.resolve({ user: me }) : api('/me').catch(function () { return {}; })).then(function (m) {
        me = (m && (m.user || m)) || null;
        $('whoami').textContent = me && me.handle ? '@' + me.handle : '';
        var h = (location.hash || '').slice(1);
        current = TABS.some(function (t) { return t[0] === h; }) ? h : 'overview';
        renderTabs();
        if (current === 'overview') { renderOverview(ov); } else { go(current); }
      });
    }).catch(function (e) { if (token && e.message !== 'Admins only.' && e.message !== 'Please sign in again.') { showLogin(); $('lgError').textContent = e.message; } });
  }
  function getCities() {
    if (cities) return Promise.resolve(cities);
    return api('/cities?limit=1000').then(function (c) { cities = c; return c; });
  }
  function citySelect(id, selected, withAny) {
    return '<select id="' + id + '">' + (withAny ? '<option value="">Any city</option>' : '') + cities.map(function (c) {
      return '<option value="' + c.id + '" data-slug="' + esc(c.slug) + '" data-lat="' + c.lat + '" data-lng="' + c.lng + '"' + (String(c.id) === String(selected) ? ' selected' : '') + '>' + esc(c.name) + (c.dishCount ? ' (' + c.dishCount + ' dishes)' : '') + '</option>';
    }).join('') + '</select>';
  }

  // ---------- Overview ----------
  function overview() { return api('/admin/overview').then(renderOverview); }
  function renderOverview(ov) {
    var t = ov.totals;
    var bySource = {};
    ov.places.forEach(function (p) { bySource[p.source] = bySource[p.source] || {}; bySource[p.source][p.status] = p.n; });
    var placeRows = ['user', 'osm', 'seed'].map(function (s) {
      var r = bySource[s] || {};
      var label = { user: 'Added by people', osm: 'OpenStreetMap', seed: 'Demo (seed)' }[s];
      return tr([td(esc(label)), tdn(num(r.active || 0)), tdn(num(r.pending || 0)), tdn(num(r.hidden || 0))]);
    });
    $('main').innerHTML =
      '<h2>Overview</h2>' +
      '<div class="grid" style="margin-bottom:14px">' +
      tile(num(t.users), 'Accounts', num(t.demoUsers) + ' demo') +
      tile(num(t.newUsers7d), 'New accounts, 7 days') +
      tile(num(t.activeVoters7d), 'People who voted, 7 days') +
      tile(num(t.activeSessions7d), 'Devices active, 7 days') +
      tile(num(t.liveDishes), 'Live dishes', num(t.dishes) + ' in all') +
      tile(num(t.livePosts), 'Live photos', num(t.pendingPosts) + ' waiting, ' + num(t.hiddenPosts) + ' hidden') +
      tile(num(t.verifiedPhotos), 'Verified-visit photos') +
      tile(num(t.loves), 'Loves', num(t.passes) + ' not for me, ' + num(t.saves) + ' saves') +
      tile(num(t.comments), 'Comments') +
      tile(num(t.follows), 'Follows') +
      tile(num(t.messages), 'Messages') +
      tile(num(t.stories), 'Stories', num(t.liveStories) + ' live now') +
      tile(num(t.openReports), 'Open reports') +
      tile(num(t.pendingPlaces), 'Places waiting') +
      '</div>' +
      '<div class="two">' +
      '<div class="card"><h3>Sign-ups per day (30 days)</h3>' + barChart(ov.series, ['signups'], ['#2B1B12'], ['Sign-ups']) + '</div>' +
      '<div class="card"><h3>Photos posted per day (30 days)</h3>' + barChart(ov.series, ['posts'], ['#2B1B12'], ['Photos']) + '</div>' +
      '<div class="card"><h3>Loves and "not for me" per day (30 days)</h3>' + barChart(ov.series, ['loves', 'passes'], ['#E2531E', '#3E7CB1'], ['Loves', 'Not for me']) + '</div>' +
      '<div class="card"><h3>Places by source</h3>' + table(['Source', '#Live', '#Waiting', '#Hidden'], placeRows) +
      '<h3 style="margin-top:14px">Busiest cities</h3>' + table(['City', '#Live dishes'], ov.cities.map(function (c) { return tr([td(esc(c.name)), tdn(num(c.dishes))]); }), 'No dishes yet.') + '</div>' +
      '</div>';
  }

  // ---------- Algorithm health ----------
  function algorithm() {
    return api('/admin/health').then(function (h) {
      var t = h.totals, p = h.prediction;
      var skillTone = p.skill === null ? '' : p.skill > 0.05 ? 'ok' : p.skill > 0 ? 'warn' : 'bad';
      $('main').innerHTML =
        '<h2>Is the ranking working?</h2>' +
        '<div class="card"><h3>Prediction check (last ' + p.days + ' days)</h3>' +
        '<div class="explain">For every recent love or "not for me", work out the dish' + "'" + 's People' + "'" + 's Pick from only the votes cast <b>before</b> it (as if recommending it at that moment) and see whether it predicted the vote. <b>Skill</b> compares it with always guessing the overall share of loves: above 0 means the ranking knows something, 1 would be perfect.</div>' +
        '<div class="grid">' +
        tile(num(p.votes), 'Votes checked') +
        tile(pct(p.accuracy), 'Accuracy', 'always guessing "love": ' + pct(p.alwaysLoveAccuracy)) +
        tile(p.skill === null ? '-' : badge(p.skill.toFixed(3), skillTone), 'Skill') +
        tile(p.skillWithoutTrust === null || p.skillWithoutTrust === undefined ? '-' : p.skillWithoutTrust.toFixed(3), 'Skill without trust', 'every voter counted equally') +
        '</div><p class="note">' + esc(p.note || '') + '</p></div>' +
        '<div class="card"><h3>Votes per day (14 days)</h3>' + barChart(h.votesPerDay, ['loves', 'passes'], ['#E2531E', '#3E7CB1'], ['Loves', 'Not for me']) +
        table(['Day', '#Loves', '#Not for me', '#Voters'], h.votesPerDay.slice().reverse().map(function (d) { return tr([td(esc(d.day)), tdn(d.loves), tdn(d.passes), tdn(d.voters)]); })) + '</div>' +
        '<div class="two">' +
        '<div class="card"><h3>Fairness guards</h3><div class="grid">' +
        tile(num(t.liveDishes), 'Live dishes') +
        tile(num(t.dishesWith3Votes), 'Dishes with 3+ votes') +
        tile(num(t.ownPlaceVotesIgnored), 'Own-place votes ignored', 'votes by whoever added the place') +
        tile(num(t.lockstepAccounts), 'Lockstep accounts', 'voting in step with another account; they share one weight') +
        tile(num(t.copiesHeld), 'Copied photos held') +
        tile(num(t.copiesRemoved), 'Copied photos removed') +
        tile(num(t.userPhotos), 'Live photos by people') +
        tile(num(t.verifiedPhotos), 'Verified-visit photos') +
        tile(num(t.dishesMerged), 'Dishes merged') +
        '</div></div>' +
        '<div class="card"><h3>How much Top changed in a week</h3>' +
        '<div class="explain">The share of each city' + "'" + 's top 20 today that wasn' + "'" + 't there a week ago. 0% for weeks means the list is stuck; near 100% every week means it' + "'" + 's noise.</div>' +
        table(['City', 'Today', 'Compared with', '#New in top', '#Size'], h.topChange.map(function (c) {
          return tr([td(esc(c.city)), td(esc(c.day)), td(esc(c.comparedWith)), tdn(c.newInTop + (c.size ? ' (' + Math.round(100 * c.newInTop / c.size) + '%)' : '')), tdn(c.size)]);
        }), 'Needs two snapshots a week apart (taken once a day).') +
        '<h3 style="margin-top:14px">Card taps by tab (7 days)</h3>' +
        table(['Tab', '#Seen', '#Opened', '#Open rate'], h.taps.map(function (x) {
          return tr([td(esc(x.tab)), tdn(num(x.seen)), tdn(num(x.opened)), tdn(x.seen ? pct(x.opened / x.seen) : '-')]);
        }), 'No card views yet.') + '</div>' +
        '</div>';
    });
  }

  // ---------- Recommendations explorer ----------
  var TAB_HELP = {
    top: 'Top ranks by People' + "'" + 's Pick <b>confidence</b> (loves against "not for me", each weighted by the voter' + "'" + 's trust, standing, verified visit and age, pulled towards the prior when votes are few), then feed points break ties. A dish needs weighted endorsers of at least min_endorsers_top. A place' + "'" + 's 3rd dish onwards moves down. Every 5th card is a Fresh dish (new photo).',
    trending: 'Trending ranks by this week' + "'" + 's feed points (score_7d), and needs weighted endorsers of at least min_endorsers_trending.',
    gems: 'Hidden gems: dishes in the city' + "'" + 's top fraction by feed points at places with few photos and few fans.',
    nearby: 'Nearby ranks by distance from the point, then People' + "'" + 's Pick confidence.',
    foryou: 'For You: dishes loved by people whose taste matches this person (match 0.55 or more, top 50) and people they follow, counted by match, standing and age; a "not for me" from them counts against. Dishes they already voted on are left out. With nobody to go on, the best-rated dishes of types they like fill in.'
  };
  var recsState = { city: '', tab: 'top', lat: '', lng: '', user: '', limit: 50 };
  function recs() {
    return getCities().then(function () {
      if (!recsState.city && cities[0]) recsState.city = cities[0].id;
      $('main').innerHTML =
        '<h2>Recommendations explorer</h2>' +
        '<div class="card"><div class="row">' +
        '<label>City ' + citySelect('rcCity', recsState.city) + '</label>' +
        '<label>Tab <select id="rcTab">' + ['top', 'trending', 'gems', 'nearby', 'foryou'].map(function (t) { return '<option' + (t === recsState.tab ? ' selected' : '') + ' value="' + t + '">' + { top: 'Top', trending: 'Trending', gems: 'Hidden gems', nearby: 'Nearby', foryou: 'For You' }[t] + '</option>'; }).join('') + '</select></label>' +
        '<label class="nearOnly">Latitude <input id="rcLat" type="number" step="any" value="' + esc(recsState.lat) + '"></label>' +
        '<label class="nearOnly">Longitude <input id="rcLng" type="number" step="any" value="' + esc(recsState.lng) + '"></label>' +
        '<label class="fyOnly">User (handle or id) <input id="rcUser" value="' + esc(recsState.user) + '" placeholder="@handle"></label>' +
        '<label>Show <select id="rcLimit">' + [20, 50, 100, 200].map(function (n) { return '<option' + (n === Number(recsState.limit) ? ' selected' : '') + '>' + n + '</option>'; }).join('') + '</select></label>' +
        '<button id="rcGo">Show ranking</button></div>' +
        '<div class="explain" id="rcHelp"></div></div>' +
        '<div id="rcOut"></div>';
      function sync() {
        var tab = $('rcTab').value;
        Array.prototype.forEach.call(document.querySelectorAll('.nearOnly'), function (e) { e.style.display = tab === 'nearby' ? '' : 'none'; });
        Array.prototype.forEach.call(document.querySelectorAll('.fyOnly'), function (e) { e.style.display = tab === 'foryou' ? '' : 'none'; });
        $('rcHelp').innerHTML = TAB_HELP[tab];
        if (tab === 'nearby' && !$('rcLat').value) {
          var o = $('rcCity').selectedOptions[0];
          if (o) { $('rcLat').value = o.getAttribute('data-lat'); $('rcLng').value = o.getAttribute('data-lng'); }
        }
      }
      $('rcTab').addEventListener('change', sync);
      $('rcCity').addEventListener('change', function () { $('rcLat').value = ''; sync(); });
      $('rcGo').addEventListener('click', runRecs);
      sync();
      if (recsState.city && (recsState.tab !== 'foryou' || recsState.user)) runRecs();
    });
  }
  function runRecs() {
    recsState = { city: $('rcCity').value, tab: $('rcTab').value, lat: $('rcLat').value, lng: $('rcLng').value, user: $('rcUser').value.trim(), limit: $('rcLimit').value };
    $('rcOut').innerHTML = '<p class="note">Loading...</p>';
    var q = { city: recsState.city, tab: recsState.tab, limit: recsState.limit };
    if (recsState.tab === 'nearby') { q.lat = recsState.lat; q.lng = recsState.lng; }
    if (recsState.tab === 'foryou') q.user = recsState.user.replace(/^@/, '');
    api('/admin/recs' + qs(q)).then(function (r) {
      var s = r.settings;
      var fy = r.tab === 'foryou';
      var head = ['#Rank', 'Lane', 'Dish', 'Place', '#Loves', '#Passes', '#W loves', '#W passes', '#Rating', '#Confidence', '#Feed pts', '#7-day pts', '#Endorsers', '#W endorsers', '#Photos'];
      if (r.tab === 'nearby') head.push('#Distance');
      if (fy) head.push('Why', '#For You score');
      var rows = r.items.map(function (d) {
        var cells = [tdn(d.rank), td(badge(d.lane)), td(dishLink(d.id, d.name) + (d.category ? '<div class="note">' + esc(d.category) + '</div>' : '')),
          td(esc(d.place) + (d.locality ? '<div class="note">' + esc(d.locality) + '</div>' : '')),
          tdn(d.loves), tdn(d.passes), tdn(num(d.wLoves, 2)), tdn(num(d.wPasses, 2)), tdn(pct(d.rating)), tdn(pct(d.confidence)),
          tdn(num(d.score, 2)), tdn(num(d.score7d, 2)), tdn(d.endorsers), tdn(num(d.wEndorsers, 2)), tdn(d.photos)];
        if (r.tab === 'nearby') cells.push(tdn(d.distanceM === null ? '-' : d.distanceM >= 1000 ? (d.distanceM / 1000).toFixed(1) + ' km' : d.distanceM + ' m'));
        if (fy) cells.push(td(forYouWhy(d)), tdn(num(d.forYouScore, 3)));
        return tr(cells);
      });
      var limits = r.tab === 'top' ? 'Top needs weighted endorsers of at least ' + s.min_endorsers_top + '; prior ' + pct(s.pp_prior) + ' worth ' + s.pp_prior_weight + ' votes; caution z = ' + s.pp_z + '.' :
        r.tab === 'trending' ? 'Trending needs weighted endorsers of at least ' + s.min_endorsers_trending + '.' : '';
      $('rcOut').innerHTML = '<div class="card"><h3>' + r.items.length + ' dishes' + (fy ? ' for this person' : '') + '</h3>' +
        (limits ? '<p class="note">' + esc(limits) + ' Click a dish to see every vote behind it.</p>' : '<p class="note">Click a dish to see every vote behind it.</p>') +
        (fy ? '<p class="note">' + userLink(r.userId, recsState.user.replace(/^@/, '')) + ': open their taste, matches and follows.</p>' : '') +
        table(head, rows, 'No dishes on this tab here yet (a dish needs a live photo and enough votes).') + '</div>';
    }).catch(function (e) { $('rcOut').innerHTML = '<div class="card error">' + esc(e.message) + '</div>'; });
  }
  function forYouWhy(d) {
    if (d.byUserId) {
      var bits = ['loved by ' + userLink(d.byUserId, d.byUser)];
      if (d.byMatch !== null && d.byMatch !== undefined) bits.push('taste match ' + pct(d.byMatch));
      if (d.followed) bits.push('whom this person follows');
      if (d.people > 1) bits.push(d.people + ' people in all');
      return bits.join(', ');
    }
    return 'they like ' + esc(d.forYouCategory || d.category || 'this type') + ' (best-rated of the type)';
  }

  // ---------- Dish detail: every vote and what it was worth ----------
  function openDish(id) {
    openDrawer('<p class="note">Loading...</p>');
    api('/admin/dishes/' + id + '/explain').then(function (x) {
      var d = x.dish, s = x.settings;
      var m = s.pp_prior_weight, p0 = s.pp_prior;
      var wl = d.wLoves, wp = d.wPasses;
      var rating = (wl + m * p0) / (wl + wp + m);
      var conf = Math.max(0, rating - s.pp_z * Math.sqrt(rating * (1 - rating) / (wl + wp + m)));
      var counted = x.votes.filter(function (v) { return v.kind === 'recommend' || v.kind === 'pass'; });
      var others = x.votes.filter(function (v) { return v.kind !== 'recommend' && v.kind !== 'pass'; });
      var voteRows = counted.map(function (v) {
        var why = v.isOwner ? 'ignored: added this place' : d.status !== 'live' ? 'ignored: dish not live' : '';
        return tr([td(userLink(v.userId, v.handle) + (v.isDemo ? ' ' + badge('demo') : '') + (v.expert ? ' ' + badge('expert', 'ok') : '')),
          td(kindBadge(v.kind)), td(when(v.createdAt)), tdn(num(v.standing, 3)), tdn(v.twins ? badge(v.twins, 'warn') : '0'),
          tdn(num(v.trustCategory, 3)), tdn(num(v.trustOverall, 3)), tdn(num(v.trust, 3)),
          td(v.verifiedVisit ? badge('verified visit', 'ok') : ''), tdn(num(v.fade, 3)), tdn('<b>' + num(v.weight, 3) + '</b>'),
          '<td class="why">' + esc(why) + '</td>'], why ? 'ignored' : '');
      });
      var photoCards = x.photos.map(function (p) {
        return '<div class="photo"><img loading="lazy" src="' + esc(p.thumbUrl) + '" alt=""><div>' + statusBadge(p.status) +
          (p.isCover ? badge('cover', 'ok') : '') + (p.held ? badge('held: copy?', 'bad') : '') +
          (p.visit ? badge(p.visit === 'verified' ? 'verified visit' : 'nearby', p.visit === 'verified' ? 'ok' : '') : badge('no visit check')) +
          (p.capture ? badge(p.capture) : '') + (p.source !== 'user' ? badge(p.source) : '') + '</div>' +
          '<div style="padding-top:0">' + (p.authorId ? userLink(p.authorId, p.author) : esc(p.credit || '')) + ' ' + when(p.createdAt) + '</div></div>';
      }).join('');
      openDrawer(
        '<h2>' + esc(d.name) + ' ' + statusBadge(d.status) + '</h2>' +
        '<p class="note">' + esc(d.place) + (d.locality ? ', ' + esc(d.locality) : '') + ' &middot; ' + esc(d.city) + (d.category ? ' &middot; ' + esc(d.category) : '') +
        (d.placeAddedBy ? ' &middot; place added by ' + userLink(d.placeAddedById, d.placeAddedBy) + ' (their votes here are ignored)' : '') +
        (d.mergedInto ? ' &middot; merged into ' + dishLink(d.mergedInto, 'another dish') : '') + '</p>' +
        '<div class="grid" style="margin-bottom:12px">' +
        tile(x.topRank ? '#' + x.topRank.rank : '-', 'Place in city Top', x.topRank ? 'lane: ' + esc(x.topRank.lane) : 'not in Top (or below 500)') +
        tile(d.loves + ' / ' + d.passes, 'Loves / not for me', 'weighted ' + num(wl, 2) + ' / ' + num(wp, 2)) +
        tile(pct(d.rating), 'People' + "'" + 's Pick rating') +
        tile(pct(d.confidence), 'Confidence (ranks Top)') +
        tile(num(d.score, 2), 'Feed points', '7 days: ' + num(d.score7d, 2)) +
        tile(num(d.wEndorsers, 2), 'Weighted endorsers', d.endorsers + ' people; Top needs ' + s.min_endorsers_top) +
        tile(d.champion ? '@' + esc(d.champion) : '-', 'Champion', 'most trusted lover') +
        '</div>' +
        '<div class="card"><h3>How the numbers come out</h3>' +
        '<div class="formula">vote weight = trust (for this dish type, else overall, else floor ' + s.trust_floor + ') &times; standing &times; visit bonus (' + s.visit_weight + ' if they posted a verified photo here) &times; fade (half-life ' + s.pp_half_life_days + ' days)<br>' +
        'rating = (w loves + ' + m + ' &times; ' + p0 + ') / (w loves + w passes + ' + m + ') = (' + wl.toFixed(3) + ' + ' + (m * p0).toFixed(2) + ') / (' + (wl + wp).toFixed(3) + ' + ' + m + ') = <b>' + pct(rating) + '</b><br>' +
        'confidence = rating - ' + s.pp_z + ' &times; sqrt(rating &times; (1 - rating) / (w loves + w passes + ' + m + ')) = <b>' + pct(conf) + '</b></div>' +
        '<p class="note">Standing grows from 0 to 1 over ' + s.standing_ramp_days + ' days after sign-up and is shared between accounts voting in lockstep (twins). Stored values are from the last rebuild, so they can differ slightly from a live recompute.</p></div>' +
        '<div class="card"><h3>Votes (' + counted.length + ')</h3>' +
        table(['Voter', 'Vote', 'When', '#Standing', '#Twins', '#Trust (type)', '#Trust (all)', '#Trust used', 'Visit', '#Fade', '#Weight', ''], voteRows, 'No loves or "not for me" yet.') + '</div>' +
        '<div class="card"><h3>Feed points by person</h3><p class="note">Saves, shares and tried-with-a-love add feed points (capped at ' + s.person_cap + ' per person, fading over ' + s.half_life_days + ' days, times standing). They break ties in Top and drive Trending.</p>' +
        table(['Person', 'Actions', '#Points'], x.feedPoints.map(function (f) {
          return tr([td(userLink(f.userId, f.handle)), td((f.kinds || []).map(kindBadge).join(' ') + (f.isOwner ? ' ' + badge('owner: ignored', 'bad') : '')), tdn(num(f.points, 3))]);
        }), 'Nobody yet.') +
        (others.length ? '<p class="note">' + others.length + ' saves / shares / tried in all.</p>' : '') + '</div>' +
        '<div class="card"><h3>Photos (' + x.photos.length + ')</h3>' + (photoCards ? '<div class="photos">' + photoCards + '</div>' : '<p class="note">No photos.</p>') + '</div>'
      );
    }).catch(function (e) { openDrawer('<div class="card error">' + esc(e.message) + '</div>'); });
  }

  // ---------- User detail: why does this person see what they see ----------
  function openUser(id, city) {
    openDrawer('<p class="note">Loading...</p>');
    api('/admin/users/' + encodeURIComponent(id) + qs({ city: city || '' })).then(function (x) {
      var u = x.user;
      return getCities().then(function () {
        openDrawer(
          '<h2>@' + esc(u.handle) + ' ' + badge(u.role, u.role === 'admin' ? 'warn' : '') + (u.isDemo ? ' ' + badge('demo') : '') + '</h2>' +
          '<p class="note">' + esc(u.displayName || '') + ' &middot; ' + esc(u.email) + ' &middot; joined ' + when(u.createdAt) + (u.homeCity ? ' &middot; ' + esc(u.homeCity) : '') + '</p>' +
          '<div class="grid" style="margin-bottom:12px">' +
          tile(num(u.standing, 3), 'Standing', u.twins ? u.twins + ' lockstep twins' : 'no lockstep twins') +
          tile(num(u.trust, 3), 'Trust (all dishes)', u.expert ? 'expert' : '') +
          tile(num(u.votes), 'Votes') + tile(num(u.posts), 'Photos') +
          tile(num(u.followers), 'Followers', 'follows ' + num(u.following)) +
          tile(num(u.sessions), 'Signed-in devices') +
          tile(u.emailVerified ? 'yes' : 'no', 'Email verified', u.twoFactor ? 'two-step on' : 'two-step off') +
          tile(u.lockedUntil && new Date(u.lockedUntil) > new Date() ? 'locked' : 'no', 'Locked', u.failedLogins ? u.failedLogins + ' wrong passwords' : '') +
          '</div>' +
          '<div class="two">' +
          '<div class="card"><h3>Taste profile</h3>' + (x.taste ? '<div class="kv"><b>Diet</b><span>' + esc(x.taste.diet) + '</span><b>Spice</b><span>' + esc(x.taste.spice) + ' / 5</span><b>Loves</b><span>' + esc(x.taste.categories.join(', ') || '-') + '</span></div>' : '<p class="note">No taste profile filled in.</p>') +
          '<h3 style="margin-top:12px">Learned from votes</h3>' + table(['Dish type', '#Score'], x.affinity.map(function (a) { return tr([td(esc(a.category)), tdn(num(a.score, 2))]); }), 'Nothing learned yet.') + '</div>' +
          '<div class="card"><h3>Trust by dish type</h3><p class="note">How often people who voted after them agreed, beyond always voting the same way.</p>' +
          table(['Type', '#Picks', '#Backers', '#Agreed', '#Trust'], x.trust.map(function (t) { return tr([td(esc(t.category) + (t.expert ? ' ' + badge('expert', 'ok') : '')), tdn(num(t.picks, 1)), tdn(t.backers), tdn(num(t.agreed, 2)), tdn(num(t.trust, 3))]); }), 'No record yet: their votes count at the trust floor.') + '</div>' +
          '</div>' +
          '<div class="card"><h3>Their For You</h3><div class="row"><label>City ' + citySelect('uCity', x.forYouCity) + '</label><button class="small" id="uCityGo">Show</button></div>' +
          table(['#', 'Dish', 'Place', 'Why', '#Score', '#Confidence'], x.forYou.map(function (d) {
            return tr([tdn(d.rank), td(dishLink(d.id, d.name)), td(esc(d.place)), td(forYouWhy({ byUserId: d.byUserId, byUser: d.byUser, byMatch: d.byMatch, followed: d.followed, people: d.people, forYouCategory: d.category })), tdn(num(d.score, 3)), tdn(pct(d.confidence))]);
          }), 'Nothing for them here yet.') + '</div>' +
          '<div class="two">' +
          '<div class="card"><h3>Taste matches (top 10)</h3>' + table(['Person', '#Match', '#Shared votes', '#Agreed', '#Evidence'], x.matches.map(function (m) {
            return tr([td(userLink(m.userId, m.handle)), tdn(pct(m.match)), tdn(m.coVotes), tdn(m.agreed), tdn(num(m.evidence, 2))]);
          }), 'Nobody to compare with yet.') + '<p class="note">For You uses matches of 55% or more.</p></div>' +
          '<div class="card"><h3>Follows (' + x.follows.length + ')</h3>' + table(['Person', 'Since'], x.follows.map(function (f) { return tr([td(userLink(f.userId, f.handle)), td(when(f.createdAt))]); }), 'Follows nobody.') +
          (x.twins.length ? '<h3 style="margin-top:12px">Lockstep twins</h3><p>' + x.twins.map(function (t) { return userLink(t.userId, t.handle); }).join(', ') + '</p>' : '') + '</div>' +
          '</div>' +
          '<div class="two">' +
          '<div class="card"><h3>Recent votes</h3>' + table(['Vote', 'Dish', 'Place', 'When'], x.recentVotes.map(function (v) {
            return tr([td(kindBadge(v.kind) + (v.isOwner ? ' ' + badge('own place: ignored', 'bad') : '')), td(dishLink(v.dishId, v.dish)), td(esc(v.place)), td(when(v.createdAt))]);
          }), 'No votes.') + '</div>' +
          '<div class="card"><h3>Devices</h3>' + table(['Device', 'Signed in', 'Last seen', 'Status'], x.sessions.map(function (s) {
            return tr([td(esc(s.device || 'unknown')), td(when(s.createdAt)), td(when(s.lastSeenAt)), td(s.revokedAt ? badge('signed out') : badge('active', 'ok'))]);
          }), 'No sessions.') + '</div>' +
          '</div>'
        );
        $('uCityGo').addEventListener('click', function () { openUser(u.id, $('uCity').value); });
      });
    }).catch(function (e) { openDrawer('<div class="card error">' + esc(e.message) + '</div>'); });
  }

  // ---------- Ranking settings ----------
  var SETTING_HELP = {
    pp_prior: 'Where a dish with no votes starts. Higher = new dishes look better before anyone votes.',
    pp_prior_weight: 'How many votes that starting point is worth. Higher = more votes needed to move a dish.',
    pp_z: 'Caution for dishes with few votes. Higher = a dish needs more votes to rank high.',
    pp_half_life_days: 'Votes lose half their weight after this many days, so old opinions fade.',
    half_life_days: 'Feed points (saves, shares) halve after this many days.',
    trust_floor: 'Weight of a voter with no track record.',
    trust_span: 'Extra weight a voter can earn when people who vote after them agree.',
    trust_prior_picks: 'A record starts as this many picks nobody backed, so one lucky pick isn' + "'" + 't enough.',
    standing_ramp_days: 'New accounts grow to full weight over this many days (stops sign-up farms).',
    twin_window_hours: 'Two votes on the same dish this close together, the same way, are in lockstep.',
    twin_min_votes: 'Lockstep votes needed before two accounts share one person' + "'" + 's weight.',
    twin_overlap: 'Share of the smaller account' + "'" + 's votes that must be in lockstep.',
    min_endorsers_top: 'Weighted people needed before a dish can enter Top.',
    min_endorsers_trending: 'Weighted people needed before a dish can enter Trending.',
    visit_weight: 'A vote from someone who posted a verified photo at the place counts this much (1 to 2).',
    place_max_in_row: 'Dishes from one place before the rest of its dishes move down Top.',
    place_spread: 'How many cards each extra dish from the same place moves down.',
    person_cap: 'Most feed points one person can give one dish.',
    w_recommend: 'Feed points for a love.', w_save: 'Feed points for a save.', w_tried: 'Feed points for tried (only alongside a love).', w_share: 'Feed points for a share.',
    fresh_days: 'A dish with a photo this new can take a Fresh slot in Top.'
  };
  function ranking() {
    return api('/admin/ranking-config').then(function (rows) {
      $('main').innerHTML = '<h2>Ranking settings</h2>' +
        '<div class="explain">Changes apply at once: saving rebuilds every dish' + "'" + 's feed points and People' + "'" + 's Pick. Values outside a safe range are refused.</div>' +
        '<div class="card">' + table(['Setting', 'Value', 'What it does', ''], rows.map(function (r) {
          return tr([td('<code>' + esc(r.key) + '</code>'), td('<input type="number" step="any" data-key="' + esc(r.key) + '" value="' + esc(r.value) + '">'),
            td(esc(r.note || '') + (SETTING_HELP[r.key] ? '<div class="note">' + esc(SETTING_HELP[r.key]) + '</div>' : '')),
            td('<button class="small" data-save="' + esc(r.key) + '">Save</button>')]);
        })) + '</div>';
    });
  }

  // ---------- Users ----------
  var usersQ = '';
  function users() {
    $('main').innerHTML = '<h2>Users</h2><div class="card"><form class="row" id="uForm"><input id="uQ" placeholder="Handle, name or email" value="' + esc(usersQ) + '" style="flex:1;min-width:180px"><button>Search</button></form><div id="uOut"><p class="note">Loading...</p></div></div>';
    $('uForm').addEventListener('submit', function (e) { e.preventDefault(); usersQ = $('uQ').value.trim(); loadUsers(); });
    return loadUsers();
  }
  function loadUsers() {
    return api('/admin/users' + qs({ q: usersQ, limit: 100 })).then(function (rows) {
      $('uOut').innerHTML = table(['User', 'Email', 'Role', 'Joined', 'Verified', 'Two-step', '#Photos', '#Votes', '#Followers', '#Standing', '#Trust', '#Devices', 'Locked until'], rows.map(function (u) {
        var locked = u.lockedUntil && new Date(u.lockedUntil) > new Date();
        return tr([td(userLink(u.id, u.handle) + (u.isDemo ? ' ' + badge('demo') : '')), td(esc(u.email)), td(u.role === 'admin' ? badge('admin', 'warn') : esc(u.role)), td(when(u.createdAt)),
          td(u.emailVerified ? badge('yes', 'ok') : badge('no')), td(u.twoFactor ? badge('on', 'ok') : 'off'), tdn(u.posts), tdn(u.votes), tdn(u.followers),
          tdn(num(u.standing, 2) + (u.twins ? ' ' + badge(u.twins + ' twins', 'warn') : '')), tdn(num(u.trust, 3)), tdn(u.sessions), td(locked ? badge(new Date(u.lockedUntil).toLocaleString('en-IN'), 'bad') : '')]);
      }), 'No one matches.') + '<p class="note">Newest first, up to 100. Click a handle for their taste, matches and For You.</p>';
    });
  }

  // ---------- Places ----------
  var placesF = { q: '', city: '', status: '', source: '' };
  function places() {
    return getCities().then(function () {
      $('main').innerHTML = '<h2>Places</h2>' +
        '<div class="card"><form class="row" id="pForm"><input id="pQ" placeholder="Place name" value="' + esc(placesF.q) + '" style="flex:1;min-width:160px">' +
        citySelect('pCity', placesF.city, true) +
        '<select id="pStatus"><option value="">Any status</option>' + ['active', 'pending', 'hidden'].map(function (s) { return '<option' + (s === placesF.status ? ' selected' : '') + '>' + s + '</option>'; }).join('') + '</select>' +
        '<select id="pSource"><option value="">Any source</option>' + [['user', 'Added by people'], ['osm', 'OpenStreetMap'], ['seed', 'Demo']].map(function (s) { return '<option value="' + s[0] + '"' + (s[0] === placesF.source ? ' selected' : '') + '>' + s[1] + '</option>'; }).join('') + '</select>' +
        '<button>Search</button></form><div id="pOut"></div></div>' +
        '<div class="card"><h3>Possible duplicates</h3><p class="note">Places in the chosen city (else the busiest) within 150 m of each other with similar names. Merging moves dishes (same-name dishes are merged, votes and photos follow), place photos and links, then hides the old place.</p><div id="pDup"><p class="note">Loading...</p></div></div>' +
        '<div class="two">' +
        '<div class="card"><h3>Import real places from OpenStreetMap</h3><p class="note">Adds every named restaurant, cafe, fast-food stall, bakery, sweet shop, ice-cream parlour, bar and tea or coffee shop around the city centre, with its neighbourhood (&copy; OpenStreetMap contributors). Places already here are linked, not copied; running it again picks up new ones. Runs in the background; it can take a few minutes.</p>' +
        '<div class="row">' + citySelect('oCity', placesF.city || (cities[0] && cities[0].id)) + '<label>Radius km <input id="oRadius" type="number" min="0.5" max="60" step="0.5" placeholder="city size"></label><button id="oGo">Import</button></div></div>' +
        '<div class="card"><h3>Remove demo content</h3><p class="note">The 40 demo_ accounts made the first rankings look alive. Remove them (and their votes) once real people vote; optionally remove the invented demo places and their illustrated photos too. Real and imported places are not touched.</p>' +
        '<div class="row"><button class="warn" id="dmAct">Remove demo accounts</button><button class="warn" id="dmAll">Remove demo accounts and demo places</button></div></div>' +
        '</div>' +
        '<div class="card"><h3>Recent jobs</h3><div id="jobs"><p class="note">Loading...</p></div></div>';
      $('pForm').addEventListener('submit', function (e) { e.preventDefault(); readPlaceFilters(); loadPlaces(); });
      $('oGo').addEventListener('click', function () {
        var o = $('oCity').selectedOptions[0];
        var r = $('oRadius').value;
        act('/admin/cities/' + o.getAttribute('data-slug') + '/import-osm', r ? { radiusKm: Number(r) } : {}, 'Import started for ' + o.textContent.split(' (')[0] + '.').then(loadJobs);
      });
      $('dmAct').addEventListener('click', function () { if (confirm('Remove all demo_ accounts and their votes? This cannot be undone.')) act('/admin/demo/remove', { all: false }, 'Demo accounts removed.').then(loadJobs); });
      $('dmAll').addEventListener('click', function () { if (confirm('Remove demo accounts AND the invented demo places with their dishes and photos? This cannot be undone.')) act('/admin/demo/remove', { all: true }, 'Demo content removed.').then(function () { loadJobs(); loadPlaces(); }); });
      jobTimer = setInterval(loadJobs, 4000);
      loadJobs();
      return loadPlaces();
    });
  }
  function readPlaceFilters() { placesF = { q: $('pQ').value.trim(), city: $('pCity').value, status: $('pStatus').value, source: $('pSource').value }; }
  function loadPlaces() {
    api('/admin/places' + qs({ q: placesF.q, city: placesF.city, status: placesF.status, source: placesF.source, limit: 100 })).then(function (rows) {
      $('pOut').innerHTML = table(['Place', 'City', 'Status', 'Source', 'Added by', '#Dishes', '#Photos', '#Place photos', 'Added', ''], rows.map(function (p) {
        var acts = (p.status !== 'active' ? '<button class="small" data-place-act="approve" data-id="' + p.id + '">Approve</button> ' : '') +
          (p.status !== 'hidden' ? '<button class="small ghost" data-place-act="hide" data-id="' + p.id + '">Hide</button> ' : '') +
          '<button class="small ghost" data-merge-from="' + p.id + '" data-name="' + esc(p.name) + '">Merge into...</button>';
        return tr([td('<b>' + esc(p.name) + '</b>' + (p.locality ? '<div class="note">' + esc(p.locality) + '</div>' : '') + '<div class="note" style="font-size:11px">' + esc(p.id) + '</div>'), td(esc(p.city)), td(statusBadge(p.status)), td(badge(p.source)),
          td(p.addedBy ? '@' + esc(p.addedBy) : ''), tdn(p.dishes), tdn(p.photos), tdn(p.placePhotos), td(when(p.createdAt)), td(acts)]);
      }), 'No places match.');
    }).catch(function (e) { $('pOut').innerHTML = '<p class="error">' + esc(e.message) + '</p>'; });
    // Without a city filter, look for duplicates in the busiest city.
    var dupCity = placesF.city || (cities[0] && cities[0].id);
    if (dupCity) {
      api('/admin/places/duplicates' + qs({ city: dupCity })).then(function (rows) {
        $('pDup').innerHTML = table(['Place A', 'Place B', '#Apart', '#Name match', ''], rows.map(function (d) {
          return tr([td(esc(d.aName) + ' ' + badge(d.aSource) + '<div class="note">' + d.aDishes + ' dishes</div>'), td(esc(d.bName) + ' ' + badge(d.bSource) + '<div class="note">' + d.bDishes + ' dishes</div>'),
            tdn(d.distanceM + ' m'), tdn(pct(d.similarity)),
            td('<button class="small" data-merge="' + d.aId + '" data-into="' + d.bId + '">A into B</button> <button class="small" data-merge="' + d.bId + '" data-into="' + d.aId + '">B into A</button>')]);
        }), 'No likely duplicates in this city.');
      }).catch(function () {});
    }
  }
  function loadJobs() {
    if (!$('jobs')) return;
    api('/admin/jobs').then(function (jobs) {
      if (!$('jobs')) return;
      $('jobs').innerHTML = jobs.length ? jobs.map(function (j) {
        return '<div style="margin-bottom:10px"><b>' + esc(j.kind === 'import-osm' ? 'OpenStreetMap import: ' : 'Remove demo: ') + esc(j.label) + '</b> ' +
          badge(j.status, j.status === 'done' ? 'ok' : j.status === 'failed' ? 'bad' : 'warn') + ' <span class="note">' + when(j.startedAt) + '</span>' +
          (j.log.length ? '<div class="log">' + esc(j.log.join('|NL|')).split('|NL|').join('<br>') + '</div>' : '') + '</div>';
      }).join('') : '<p class="note">Nothing run since the server started.</p>';
    }).catch(function () {});
  }
  function mergePlace(from, into) {
    if (!confirm('Merge this place into the other one? Its dishes, votes and photos move over and it is hidden.')) return;
    act('/admin/restaurants/' + from + '/merge', { into: into }, 'Places merged.').then(function (r) {
      toast('Moved ' + r.moved + ' dishes, merged ' + r.merged + ' same-name dishes, ' + r.photos + ' place photos.');
      loadPlaces();
    }).catch(function () {});
  }

  // ---------- Moderation ----------
  function moderation() {
    return Promise.all([api('/admin/queue'), api('/admin/dishes/similar')]).then(function (res) {
      var q = res[0], similar = res[1];
      $('main').innerHTML = '<h2>Moderation</h2>' +
        '<div class="card"><h3>Places waiting for approval (' + q.pendingPlaces.length + ')</h3>' +
        table(['Place', 'Added by', '#Dishes', 'Added', ''], q.pendingPlaces.map(function (p) {
          return tr([td('<b>' + esc(p.name) + '</b>' + (p.locality ? '<div class="note">' + esc(p.locality) + '</div>' : '') + '<div class="note">' + Number(p.lat).toFixed(5) + ', ' + Number(p.lng).toFixed(5) + '</div>'),
            td(p.createdBy ? '@' + esc(p.createdBy) : ''), tdn(p.dishes), td(when(p.createdAt)),
            td('<button class="small" data-place-act="approve" data-id="' + p.id + '">Approve</button> <button class="small ghost" data-place-act="hide" data-id="' + p.id + '">Hide</button>')]);
        }), 'Nothing waiting.') + '</div>' +
        '<div class="card"><h3>Reports (' + q.reported.length + ')</h3>' +
        table(['What', '', 'Reasons', '#Reports', 'Status', 'Last', ''], q.reported.map(function (r) {
          return tr([td(badge(r.targetType) + ' ' + (r.dishId ? dishLink(r.dishId, r.label || '') : r.targetType === 'user' ? userLink(r.targetId, r.label) : esc(r.label || ''))),
            td(r.thumbUrl ? '<img class="thumb" loading="lazy" src="' + esc(r.thumbUrl) + '" alt="">' : ''), td(esc((r.reasons || []).join(', '))), tdn(r.reports), td(r.status ? statusBadge(r.status) : ''), td(when(r.lastReportAt)), td(reportActions(r))]);
        }), 'No open reports.') + '</div>' +
        '<div class="card"><h3>Photos held as possible copies (' + q.heldPhotos.length + ')</h3><p class="note">Left: the new photo. Right: the earlier photo it looks like. Keep it if it is genuinely a different photo.</p>' +
        (q.heldPhotos.length ? '<div class="two">' + q.heldPhotos.map(function (h) {
          return '<div class="tile"><div class="pair"><div><img loading="lazy" src="' + esc(h.thumbUrl) + '" alt=""><div class="note">@' + esc(h.authorHandle || '?') + ', ' + ago(h.createdAt) + '</div></div>' +
            '<div><img loading="lazy" src="' + esc(h.originalThumbUrl || '') + '" alt=""><div class="note">original: @' + esc(h.originalAuthorHandle || '?') + ', ' + ago(h.originalCreatedAt) + '</div></div></div>' +
            '<p>' + dishLink(h.dishId, h.dishName) + '</p><div class="row"><button class="small" data-mod="/admin/posts/' + h.id + '/restore">Keep (not a copy)</button><button class="small warn" data-mod="/admin/posts/' + h.id + '/hide">Remove</button></div></div>';
        }).join('') + '</div>' : '<p class="note">No photos held.</p>') + '</div>' +
        '<div class="card"><h3>Dishes that look like the same dish (' + similar.length + ')</h3><p class="note">Two names for one dish at one place. Merging keeps one vote per person (their latest), moves photos, and old links open the merged dish.</p>' +
        table(['Place', 'Dish A', 'Dish B', '#Name match', ''], similar.map(function (s) {
          return tr([td(esc(s.placeName)), td(dishLink(s.aId, s.aName) + ' <span class="note">' + s.aPosts + ' photos</span>'), td(dishLink(s.bId, s.bName) + ' <span class="note">' + s.bPosts + ' photos</span>'), tdn(pct(s.similarity)),
            td('<button class="small" data-dish-merge="' + s.aId + '" data-into="' + s.bId + '">A into B</button> <button class="small" data-dish-merge="' + s.bId + '" data-into="' + s.aId + '">B into A</button>')]);
        }), 'No look-alike dishes.') + '</div>';
    });
  }
  function reportActions(r) {
    var t = r.targetType, id = r.targetId;
    var plural = { post: 'posts', dish: 'dishes', comment: 'comments' }[t];
    if (plural) {
      return (r.status === 'hidden' ? '<button class="small" data-mod="/admin/' + plural + '/' + id + '/restore">Restore</button> ' : '<button class="small warn" data-mod="/admin/' + plural + '/' + id + '/hide">Hide</button> ') +
        '<button class="small ghost" data-mod="/admin/' + plural + '/' + id + '/dismiss">Dismiss</button>';
    }
    if (t === 'message' || t === 'story') return '<button class="small warn" data-mod="/admin/talk/' + t + '/' + id + '/remove">Remove</button> <button class="small ghost" data-mod="/admin/talk/' + t + '/' + id + '/dismiss">Dismiss</button>';
    if (t === 'user') return '<button class="small ghost" data-mod="/admin/users/' + id + '/dismiss">Dismiss</button>';
    return '';
  }

  // ---------- Activity ----------
  function activity() {
    return api('/admin/activity?limit=50').then(function (a) {
      $('main').innerHTML = '<h2>Latest activity</h2>' +
        '<div class="card"><h3>Photos</h3>' + table(['', 'Dish', 'Place', 'By', 'Status', 'Checks', 'When'], a.posts.map(function (p) {
          return tr([td('<img class="thumb" loading="lazy" src="' + esc(p.thumbUrl) + '" alt="">'), td(dishLink(p.dishId, p.dish)), td(esc(p.place)), td(p.authorId ? userLink(p.authorId, p.author) : badge(p.source)), td(statusBadge(p.status)),
            td((p.visit ? badge(p.visit === 'verified' ? 'verified visit' : 'nearby', p.visit === 'verified' ? 'ok' : '') : '') + ' ' + (p.capture ? badge(p.capture) : '') + (p.isCopy ? ' ' + badge('copy?', 'bad') : '')), td(when(p.createdAt))]);
        }), 'No photos yet.') + '</div>' +
        '<div class="two">' +
        '<div class="card"><h3>Votes</h3>' + table(['Who', 'Vote', 'Dish', 'When'], a.votes.map(function (v) {
          return tr([td(userLink(v.userId, v.handle)), td(kindBadge(v.kind)), td(dishLink(v.dishId, v.dish) + '<div class="note">' + esc(v.place) + '</div>'), td(when(v.createdAt))]);
        }), 'No votes yet.') + '</div>' +
        '<div class="card"><h3>Sign-ups</h3>' + table(['Who', 'Email', 'Verified', 'When'], a.signups.map(function (s) {
          return tr([td(userLink(s.id, s.handle) + (s.isDemo ? ' ' + badge('demo') : '')), td(esc(s.email)), td(s.emailVerified ? badge('yes', 'ok') : ''), td(when(s.createdAt))]);
        }), 'No sign-ups yet.') + '</div>' +
        '<div class="card"><h3>Comments</h3>' + table(['Who', 'Comment', 'On', 'Status', 'When'], a.comments.map(function (c) {
          return tr([td(userLink(c.authorId, c.author)), td(esc(c.body)), td(dishLink(c.dishId, c.dish)), td(statusBadge(c.status)), td(when(c.createdAt))]);
        }), 'No comments yet.') + '</div>' +
        '<div class="card"><h3>Reports</h3>' + table(['By', 'What', 'Reason', 'Status', 'When'], a.reports.map(function (r) {
          return tr([td(userLink(r.reporterId, r.reporter)), td(badge(r.targetType) + (r.targetType === 'dish' ? ' ' + dishLink(r.targetId, 'open') : r.targetType === 'user' ? ' ' + userLink(r.targetId, 'user') : '')), td(esc(r.reason) + (r.note ? '<div class="note">' + esc(r.note) + '</div>' : '')), td(statusBadge(r.status)), td(when(r.createdAt))]);
        }), 'No reports.') + '</div>' +
        '</div>';
    });
  }

  // ---------- drawer and clicks ----------
  function openDrawer(html) { $('drawerBody').innerHTML = html; $('drawer').classList.add('open'); $('drawer').querySelector('.panel').scrollTop = 0; }
  function closeDrawer() { $('drawer').classList.remove('open'); }
  $('drawerClose').addEventListener('click', closeDrawer);
  $('drawer').addEventListener('click', function (e) { if (e.target === $('drawer')) closeDrawer(); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeDrawer(); });

  document.addEventListener('click', function (e) {
    var el = e.target.closest ? e.target.closest('[data-tab],[data-dish],[data-user],[data-save],[data-place-act],[data-merge],[data-merge-from],[data-mod],[data-dish-merge]') : null;
    if (!el) return;
    e.preventDefault();
    var a = el.getAttribute.bind(el);
    if (a('data-tab')) { closeDrawer(); go(a('data-tab')); return; }
    if (a('data-dish')) { openDish(a('data-dish')); return; }
    if (a('data-user')) { openUser(a('data-user')); return; }
    if (a('data-save')) {
      var key = a('data-save');
      var input = document.querySelector('input[data-key="' + key + '"]');
      el.disabled = true;
      api('/admin/ranking-config/' + encodeURIComponent(key), { method: 'PUT', body: { value: Number(input.value) } })
        .then(function () { toast(key + ' saved; ranking rebuilt.'); })
        .catch(function (err) { toast(err.message); })
        .then(function () { el.disabled = false; });
      return;
    }
    if (a('data-place-act')) {
      act('/admin/restaurants/' + a('data-id') + '/' + a('data-place-act'), {}, a('data-place-act') === 'approve' ? 'Place approved.' : 'Place hidden.')
        .then(function () { if (current === 'moderation') moderation(); else loadPlaces(); }).catch(function () {});
      return;
    }
    if (a('data-merge')) { mergePlace(a('data-merge'), a('data-into')); return; }
    if (a('data-merge-from')) {
      var into = prompt('Merge "' + a('data-name') + '" into which place? Paste the other place' + "'" + 's id (shown under its name).');
      if (into) mergePlace(a('data-merge-from'), into.trim());
      return;
    }
    if (a('data-mod')) { act(a('data-mod'), {}, 'Done.').then(moderation).catch(function () {}); return; }
    if (a('data-dish-merge')) {
      if (!confirm('Merge these two dishes? Votes and photos move over.')) return;
      api('/admin/dishes/' + a('data-dish-merge') + '/merge', { method: 'POST', body: { into: a('data-into') } })
        .then(function () { toast('Dishes merged.'); moderation(); }).catch(function (err) { toast(err.message); });
    }
  });

  if (token) start(); else showLogin();
})();
</script>
</body>
</html>
`;
