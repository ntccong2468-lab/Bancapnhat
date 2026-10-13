import {test} from 'node:test';
import assert from 'node:assert/strict';
import {sendMail,mailReady} from '../src/mail.js';
test('Resend adapter uses fixed endpoint, disallows redirects, handles delivery failure without fallback',async()=>{
 const oldFetch=globalThis.fetch;let calls=0;
 try {
  globalThis.fetch=async(url,options)=>{calls++;assert.equal(url,'https://api.resend.com/emails');assert.equal(options.redirect,'error');assert.equal(options.headers.authorization,'Bearer test-secret');const body=JSON.parse(options.body);assert.deepEqual(body.to,['client@gmail.com']);assert.equal(body.text,'test message');return new Response('{}',{status:calls===1?200:403});};
  const env={RESEND_API_KEY:'test-secret',EMAIL_FROM:'no-reply@vncode.id.vn'};
  assert.equal(mailReady(env),true);assert.equal(await sendMail(env,'client@gmail.com','Test','test message'),true);assert.equal(await sendMail(env,'client@gmail.com','Test','test message'),false);assert.equal(calls,2);
 }finally{globalThis.fetch=oldFetch;}
});
