// Finds and fetches real, freely licensed photos for the demo dishes from Wikimedia Commons.
// Runs in GitHub Actions (see .github/workflows/demo-images.yml), not on the server.
//
//   node scripts/demo-images.mjs candidates     -> demo-candidates/<key>.jpg contact sheets
//                                                  + demo-candidates/candidates.json
//   node scripts/demo-images.mjs pick picks.json -> seed/images/<key>.jpg (1080x1350)
//                                                  + seed/images/credits.json
//
// Only CC0, public domain, CC BY and CC BY-SA files are used (no NC / ND), and the author
// and licence of every photo are kept so the app can credit them.
import fs from 'node:fs/promises';
import path from 'node:path';
import sharp from 'sharp';

const UA = { 'user-agent': 'FoodFeed/1.0 (demo photos; https://github.com/aniketpitre/Food-MVP)' };
const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..');

// Dish key -> Wikipedia article (its lead photo) and Commons searches.
export const dishes = {
  ananda_sabudana_khichdi: ['Sabudana khichdi', ['sabudana khichdi', 'sago khichdi']],
  ananda_cold_coffee: ['Iced coffee', ['cold coffee glass', 'iced coffee milk glass']],
  ananda_masala_dosa: ['Masala dosa', ['masala dosa', 'masala dosa chutney sambar']],
  godavari_kolhapuri_misal: ['Misal pav', ['kolhapuri misal', 'misal pav']],
  godavari_misal_pav: ['Misal pav', ['misal pav', 'misal pav plate']],
  godavari_kanda_bhaji: ['Pakora', ['kanda bhaji', 'onion bhaji', 'onion pakoda']],
  bhurji_egg_bhurji_pav: ['Egg bhurji', ['egg bhurji', 'anda bhurji']],
  bhurji_anda_ghotala: ['Omelette', ['anda ghotala', 'masala omelette', 'egg bhurji pav']],
  tandoor_chicken_biryani: ['Hyderabadi biryani', ['chicken biryani', 'biryani plate']],
  tandoor_paneer_tikka: ['Paneer tikka', ['paneer tikka', 'paneer tikka plate']],
  annapurna_thali: ['Thali', ['maharashtrian thali', 'vegetarian thali']],
  annapurna_puran_poli: ['Puran poli', ['puran poli']],
  katta_cutting_chai: ['Masala chai', ['cutting chai', 'masala chai glass', 'indian tea glass']],
  katta_bun_maska: ['Bun maska', ['bun maska', 'bun maska chai']],
  katta_vada_pav: ['Vada pav', ['vada pav', 'wada pav']],
  wok_schezwan_rice: ['Fried rice', ['schezwan fried rice', 'fried rice plate']],
  wok_chilli_chicken: ['Chilli chicken', ['chilli chicken']],
  wok_manchurian: ['Manchurian (dish)', ['gobi manchurian', 'veg manchurian']],
  slice_margherita: ['Pizza Margherita', ['pizza margherita', 'margherita pizza']],
  slice_penne: ["Penne all'arrabbiata", ['penne arrabbiata', 'penne pasta tomato']],
  sangam_jalebi: ['Jalebi', ['jalebi', 'jalebi plate']],
  sangam_rabdi: ['Rabri', ['rabri', 'rabdi']],
  sangam_shrikhand: ['Shrikhand', ['shrikhand']],
  barn_veg_burger: ['Veggie burger', ['veggie burger', 'aloo tikki burger']],
  barn_chicken_burger: ['Hamburger', ['chicken burger', 'fried chicken burger']],
  udupi_rava_dosa: ['Dosa (food)', ['rava dosa', 'dosa chutney']],
  udupi_idli: ['Idli', ['idli sambar', 'idli chutney']],
  udupi_filter_coffee: ['Indian filter coffee', ['filter coffee dabara', 'south indian filter coffee']],
  pbp_pav_bhaji: ['Pav bhaji', ['pav bhaji']],
  pbp_tawa_pulao: ['Pulao', ['tawa pulao', 'vegetable pulao']],
};

const strip = (s) => String(s ?? '').replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim();
const free = (lic) => /^(cc0|public domain|pd\b|cc[- ]by(-sa)?[- ]\d|cc[- ]by(-sa)?$)/i.test(lic) && !/nc|nd/i.test(lic);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function json(url) {
  for (let i = 0; i < 4; i++) {
    const res = await fetch(url, { headers: UA });
    if (res.ok) return res.json();
    await sleep(2000 * (i + 1));
  }
  throw new Error(`failed ${url}`);
}

async function leadFile(article) {
  const u = new URL('https://en.wikipedia.org/w/api.php');
  u.search = new URLSearchParams({ action: 'query', format: 'json', formatversion: '2', redirects: '1', prop: 'pageimages', piprop: 'name', titles: article });
  const name = (await json(u)).query?.pages?.[0]?.pageimage;
  return name ? `File:${name.replace(/_/g, ' ')}` : null;
}

/** imageinfo for Commons titles, or a Commons search; returns usable photos only. */
async function commons(params) {
  const u = new URL('https://commons.wikimedia.org/w/api.php');
  u.search = new URLSearchParams({
    action: 'query', format: 'json', formatversion: '2', prop: 'imageinfo',
    iiprop: 'url|extmetadata|size|mime', iiurlwidth: '1280', ...params,
  });
  const pages = (await json(u)).query?.pages ?? [];
  pages.sort((a, b) => (a.index ?? 0) - (b.index ?? 0));
  const out = [];
  for (const p of pages) {
    const i = p.imageinfo?.[0];
    if (!i || i.mime !== 'image/jpeg' || i.width < 700 || i.height < 600) continue;
    const m = i.extmetadata ?? {};
    const licence = strip(m.LicenseShortName?.value);
    if (!free(licence)) continue;
    out.push({
      title: p.title,
      thumb: i.thumburl,
      page: i.descriptionurl,
      author: strip(m.Artist?.value).slice(0, 80) || 'Unknown author',
      licence,
      licenceUrl: m.LicenseUrl?.value ?? null,
    });
  }
  return out;
}

async function candidates() {
  const dir = path.join(root, 'demo-candidates');
  await fs.mkdir(dir, { recursive: true });
  const all = {};
  for (const [key, [article, searches]] of Object.entries(dishes)) {
    const found = [];
    const lead = await leadFile(article).catch(() => null);
    if (lead) found.push(...(await commons({ titles: lead }).catch(() => [])));
    for (const q of searches) {
      found.push(...(await commons({ generator: 'search', gsrsearch: `${q} filetype:bitmap`, gsrnamespace: '6', gsrlimit: '12' }).catch(() => [])));
      await sleep(300);
    }
    const unique = [...new Map(found.map((f) => [f.title, f])).values()].slice(0, 9);
    all[key] = unique;
    // A 3x3 contact sheet with numbers, to choose from by eye.
    const tiles = [];
    for (const [n, f] of unique.entries()) {
      try {
        const res = await fetch(f.thumb, { headers: UA });
        const img = await sharp(Buffer.from(await res.arrayBuffer())).resize(320, 320, { fit: 'cover' }).toBuffer();
        const label = Buffer.from(`<svg width="320" height="320"><rect width="56" height="56" fill="black"/><text x="14" y="42" font-size="40" fill="white" font-family="sans-serif">${n + 1}</text></svg>`);
        tiles.push({ input: await sharp(img).composite([{ input: label }]).toBuffer(), left: (n % 3) * 324, top: Math.floor(n / 3) * 324 });
      } catch {
        // skip a tile that won't download
      }
      await sleep(200);
    }
    await sharp({ create: { width: 968, height: 968, channels: 3, background: '#ffffff' } })
      .composite(tiles).jpeg({ quality: 70 }).toFile(path.join(dir, `${key}.jpg`));
    console.log(`${key}: ${unique.length} candidates`);
  }
  await fs.writeFile(path.join(dir, 'candidates.json'), JSON.stringify(all, null, 2));
}

async function pick(file) {
  const picks = JSON.parse(await fs.readFile(file, 'utf8')); // { key: "File:..." }
  const dir = path.join(root, 'seed', 'images');
  const creditsFile = path.join(dir, 'credits.json');
  const credits = JSON.parse(await fs.readFile(creditsFile, 'utf8').catch(() => '{}'));
  for (const [key, title] of Object.entries(picks)) {
    const [f] = await commons({ titles: title });
    if (!f) throw new Error(`${key}: ${title} is not a usable free photo`);
    const res = await fetch(f.thumb, { headers: UA });
    if (!res.ok) throw new Error(`${key}: download failed`);
    await sharp(Buffer.from(await res.arrayBuffer()))
      .rotate()
      .resize(1080, 1350, { fit: 'cover', position: sharp.strategy.attention })
      .jpeg({ quality: 84, mozjpeg: true })
      .toFile(path.join(dir, `${key}.jpg`));
    credits[key] = { title: f.title, author: f.author, licence: f.licence, licenceUrl: f.licenceUrl, source: f.page };
    console.log(`${key} <- ${f.title} (${f.author}, ${f.licence})`);
    await sleep(300);
  }
  await fs.writeFile(creditsFile, JSON.stringify(credits, null, 2) + '\n');
}

const [mode, arg] = process.argv.slice(2);
if (mode === 'candidates') await candidates();
else if (mode === 'pick') await pick(arg);
else throw new Error('usage: demo-images.mjs candidates | pick picks.json');
