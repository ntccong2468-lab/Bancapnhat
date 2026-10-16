import { writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// Read-only POST endpoints are explicitly listed; credentials never appear in arguments/output.
export async function checkMarketplaceAccess({ env = process.env, fetcher = fetch, category = 'all' } = {}) {
  if (!['all', 'sets'].includes(category)) throw new Error('INVALID_CATEGORY');
  const report = { mode: 'read-only', requestedCategory: category, checkedAt: new Date().toISOString(), writesPerformed: false, services: [] };
  const inCategory = item => category === 'all' || /(?:^|[^\p{L}])(?:bộ|комплект(?:ы|ов)?|костюм(?:ы|ов)?|sets?|suits?)(?:$|[^\p{L}])/iu.test(`${item.subjectName ?? ''} ${item.title ?? ''} ${item.name ?? ''}`.normalize('NFKC'));
  async function request(origin, path, headers, body) {
    const response = await fetcher(new URL(path, origin), {
      method: body === undefined ? 'GET' : 'POST', headers: { Accept: 'application/json', ...headers, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }), redirect: 'error', signal: AbortSignal.timeout(25_000),
    });
    if (!response.ok) throw new Error(`HTTP_${response.status}`);
    if (!(response.headers.get('content-type') ?? '').includes('application/json')) throw new Error('INVALID_CONTENT_TYPE');
    // Read bounded chunks rather than buffering an arbitrarily large upstream body.
    const reader = response.body.getReader(); const chunks = []; let bytes = 0;
    try { for (;;) { const { done, value } = await reader.read(); if (done) break; bytes += value.byteLength; if (bytes > 2 * 1024 * 1024) throw new Error('RESPONSE_LIMIT'); chunks.push(value); } }
    finally { await reader.cancel().catch(() => {}); }
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  }
  async function check(name, required, action) {
    const missing = required.filter(key => !env[key]);
    if (missing.length) { report.services.push({ service: name, status: 'missing_configuration', missing }); return; }
    try { report.services.push({ service: name, status: 'read_ok', ...await action() }); }
    catch (error) { const safe = /^HTTP_\d{3}$|^INVALID_CONTENT_TYPE$|^RESPONSE_LIMIT$|^INVALID_SCHEMA$/.test(error.message) ? error.message : error.name === 'TimeoutError' ? 'NETWORK_TIMEOUT' : 'NETWORK_OR_RESPONSE'; report.services.push({ service: name, status: 'failed', code: safe }); }
  }
  const id = value => { if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('INVALID_SCHEMA'); if (typeof value !== 'number' && typeof value !== 'string') throw new Error('INVALID_SCHEMA'); return String(value); };
  const title = value => typeof value === 'string' ? value.slice(0, 160) : '';
  const codes = values => { if (!Array.isArray(values) || values.some(v => typeof v !== 'string')) throw new Error('INVALID_SCHEMA'); return values; };
  await check('WB', ['VNCODE_WB_TEST_API_KEY'], async () => {
    let cursor = { limit: 100 }, scanned = 0; const seen = new Set();
    for (let page = 0; page < (category === 'sets' ? 10 : 1); page++) {
      const data = await request('https://content-api.wildberries.ru', '/content/v2/get/cards/list?locale=ru', { Authorization: env.VNCODE_WB_TEST_API_KEY }, { settings: { cursor, filter: { withPhoto: -1 } } });
      if (!Array.isArray(data.cards) || data.error === true) throw new Error('INVALID_SCHEMA'); scanned += data.cards.length;
      const sample = data.cards.filter(inCategory).flatMap(card => { if (!Array.isArray(card.sizes)) throw new Error('INVALID_SCHEMA'); return card.sizes.slice(0, 10).map(size => ({ nmID: id(card.nmID), chrtID: id(size.chrtID), title: title(card.title), category: title(card.subjectName), article: title(card.vendorCode), size: title(size.techSize), barcodes: codes(size.skus) })); }).slice(0, 20);
      if (sample.length || data.cards.length < 100) return { sample, scanned, completeCatalogRead: data.cards.length < 100 };
      const next = `${data.cursor?.updatedAt}/${data.cursor?.nmID}`; if (!data.cursor?.updatedAt || !data.cursor?.nmID || seen.has(next)) throw new Error('INVALID_SCHEMA'); seen.add(next); cursor = { limit: 100, updatedAt: data.cursor.updatedAt, nmID: data.cursor.nmID };
    }
    return { sample: [], scanned, completeCatalogRead: false };
  });
  await check('Ozon', ['VNCODE_OZON_TEST_CLIENT_ID', 'VNCODE_OZON_TEST_API_KEY'], async () => {
    const headers = { 'Client-Id': env.VNCODE_OZON_TEST_CLIENT_ID, 'Api-Key': env.VNCODE_OZON_TEST_API_KEY };
    let cursor = '', scanned = 0; const seen = new Set();
    for (let page = 0; page < (category === 'sets' ? 10 : 1); page++) {
    const data = await request('https://api-seller.ozon.ru', '/v3/product/list', headers, { filter: { visibility: 'ALL' }, last_id: cursor, limit: 100 });
    if (!Array.isArray(data.result?.items)) throw new Error('INVALID_SCHEMA');
    const ids = data.result.items.map(item => id(item.product_id)); scanned += ids.length; if (ids.length === 0) return { sample: [], scanned, completeCatalogRead: true };
    if (new Set(ids).size !== ids.length) throw new Error('INVALID_SCHEMA');
    const details = await request('https://api-seller.ozon.ru', '/v3/product/info/list', headers, { product_id: ids });
    if (!Array.isArray(details.items)) throw new Error('INVALID_SCHEMA');
    const actual = details.items.map(item => id(item.id)); if (actual.length !== ids.length || new Set(actual).size !== ids.length || actual.some(value => !ids.includes(value))) throw new Error('INVALID_SCHEMA');
    const sample = details.items.filter(inCategory).slice(0, 20).map(item => ({ product_id: id(item.id), offer_id: id(item.offer_id), sku: id(item.sku), title: title(item.name), barcodes: codes(item.barcodes) }));
    if (sample.length || ids.length < 100) return { sample, scanned, completeCatalogRead: ids.length < 100 };
    const next = data.result.last_id; if (typeof next !== 'string' || !next || seen.has(next)) throw new Error('INVALID_SCHEMA'); seen.add(next); cursor = next;
    }
    return { sample: [], scanned, completeCatalogRead: false };
  });
  await check('NationalCatalog', ['VNCODE_CATALOG_TEST_API_KEY'], async () => {
    const path = new URL('/v4/product-list', 'https://xn--80aqu.xn----7sbabas4ajkhfocclk9d3cvfsa.xn--p1ai');
    path.searchParams.set('apikey', env.VNCODE_CATALOG_TEST_API_KEY); path.searchParams.set('limit', '10'); path.searchParams.set('offset', '0');
    path.searchParams.set('from_date', '2000-01-01 00:00:00');
    path.searchParams.set('to_date', new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Moscow', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date()));
    const data = await request(path.origin, path.pathname + path.search, {});
    if (!Array.isArray(data.result?.goods)) throw new Error('INVALID_SCHEMA');
    return { sample: data.result.goods.map(item => ({ gtin: id(item.gtin), title: title(item.good_name), status: title(item.good_status) })), completeCatalogRead: false, publicationVerified: false };
  });
  return report;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2); const options = {};
  for (let i = 0; i < args.length; i += 2) { if (!['--output', '--category'].includes(args[i]) || !args[i + 1] || options[args[i]]) throw new Error('Usage: node tools/check-marketplace-gtin-readonly.mjs [--category sets] [--output report.json]'); options[args[i]] = args[i + 1]; }
  const report = await checkMarketplaceAccess({ category: options['--category'] ?? 'all' }); const output = JSON.stringify(report, null, 2) + '\n';
  if (options['--output']) await writeFile(options['--output'], output, { mode: 0o600 }); else process.stdout.write(output);
  if (report.services.some(service => service.status === 'failed')) process.exitCode = 1;
  else if (report.services.some(service => service.status === 'missing_configuration')) process.exitCode = 2;
}
