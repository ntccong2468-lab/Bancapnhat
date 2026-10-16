import test from 'node:test';
import assert from 'node:assert/strict';
import { checkMarketplaceAccess } from './check-marketplace-gtin-readonly.mjs';

const response = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'content-type': 'application/json' } });
test('missing secrets never trigger a request', async () => {
  const report = await checkMarketplaceAccess({ env: {}, fetcher: () => assert.fail('No credentials') });
  assert.equal(report.services.length, 3); assert.ok(report.services.every(service => service.status === 'missing_configuration')); assert.equal(report.writesPerformed, false);
});
test('WB reads only the allowlisted list and never includes credentials in its report', async () => {
  const report = await checkMarketplaceAccess({ env: { VNCODE_WB_TEST_API_KEY: 'synthetic-private' }, fetcher: async (url, options) => {
    assert.equal(url.origin, 'https://content-api.wildberries.ru'); assert.equal(url.pathname, '/content/v2/get/cards/list'); assert.equal(options.headers.Authorization, 'synthetic-private'); assert.equal(options.redirect, 'error');
    return response({ cards: [{ nmID: 1, title: 'Product', vendorCode: 'Article', sizes: [{ chrtID: 2, techSize: 'M', skus: ['legacy'] }] }] });
  } });
  assert.equal(report.services[0].sample[0].chrtID, '2'); assert.ok(!JSON.stringify(report).includes('synthetic-private'));
});
test('Ozon joins only requested IDs and keeps native offer/SKU', async () => {
  const calls = []; const report = await checkMarketplaceAccess({ env: { VNCODE_OZON_TEST_API_KEY: 'synthetic-secret', VNCODE_OZON_TEST_CLIENT_ID: 'client-fixture' }, fetcher: async (url, options) => {
    calls.push(url.pathname); assert.equal(options.headers['Api-Key'], 'synthetic-secret'); assert.equal(options.headers['Client-Id'], 'client-fixture');
    return response(url.pathname === '/v3/product/list' ? { result: { items: [{ product_id: 5 }] } } : { items: [{ id: 5, offer_id: 'offer-a', sku: 6, name: 'Product', barcodes: ['legacy'] }] });
  } });
  assert.deepEqual(calls, ['/v3/product/list', '/v3/product/info/list']); assert.equal(report.services[1].sample[0].sku, '6'); assert.ok(!JSON.stringify(report).includes('synthetic-secret'));
});
test('foreign Ozon details are an error instead of successful synchronization', async () => {
  const report = await checkMarketplaceAccess({ env: { VNCODE_OZON_TEST_API_KEY: 'fixture', VNCODE_OZON_TEST_CLIENT_ID: 'fixture' }, fetcher: async url => response(url.pathname === '/v3/product/list' ? { result: { items: [{ product_id: 5 }] } } : { items: [{ id: 99 }] }) });
  assert.equal(report.services[1].code, 'INVALID_SCHEMA');
});
test('HTTP errors cannot expose upstream bodies or credential values', async () => {
  const report = await checkMarketplaceAccess({ env: { VNCODE_WB_TEST_API_KEY: 'private-fixture' }, fetcher: async () => response({ echo: 'private-fixture' }, 401) });
  assert.equal(report.services[0].code, 'HTTP_401'); assert.ok(!JSON.stringify(report).includes('private-fixture'));
});
test('Catalog list alone is explicitly insufficient publication evidence', async () => {
  const report = await checkMarketplaceAccess({ env: { VNCODE_CATALOG_TEST_API_KEY: 'fixture-key' }, fetcher: async url => { assert.equal(url.searchParams.get('apikey'), 'fixture-key'); return response({ result: { goods: [{ gtin: '00000000000017', good_status: 'published' }] } }); } });
  assert.equal(report.services[2].publicationVerified, false); assert.ok(!JSON.stringify(report).includes('fixture-key'));
});
test('the requested sets category returns clothing sets and does not select an unrelated product', async () => {
  const report = await checkMarketplaceAccess({ category: 'sets', env: { VNCODE_WB_TEST_API_KEY: 'fixture' }, fetcher: async () => response({ cards: [
    { nmID: 1, title: 'Bộ thể thao', sizes: [{ chrtID: 2, skus: ['a'] }] },
    { nmID: 3, title: 'Костюм спортивный', sizes: [{ chrtID: 4, skus: ['b'] }] },
    { nmID: 5, title: 'Shirt', sizes: [{ chrtID: 6, skus: ['c'] }] },
  ] }) });
  assert.deepEqual(report.services[0].sample.map(row => row.nmID), ['1', '3']); assert.equal(report.requestedCategory, 'sets'); assert.equal(report.writesPerformed, false);
});
