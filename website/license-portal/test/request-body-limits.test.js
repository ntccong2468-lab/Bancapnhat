import {test} from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
import {envSetup} from './fixtures.js';

const endpoint='https://vncode.example/api/register';
function request(body, headers={}) {
 return new Request(endpoint,{method:'POST',headers:{'content-type':'application/json',...headers},body,duplex:'half'});
}
function chunks(bytes, size, onCancel=()=>{}) {
 let at=0;
 return new ReadableStream({pull(c){if(at>=bytes.length){c.close();return;}c.enqueue(bytes.subarray(at,at+size));at+=size;},cancel(){onCancel();}});
}
async function invoke(req){const env=envSetup();try{return await worker.fetch(req,env);}finally{env.sqlite.close();}}
function registration(padding='') {return {email:'body@example.test',password:'a-strong-test-password',confirm:'a-strong-test-password',padding};}

test('oversized chunked request without Content-Length is stopped and cancelled',async()=>{
 let cancelled=false;
 const body=chunks(new Uint8Array(200000).fill(97),4096,()=>{cancelled=true;});
 const response=await invoke(request(body));
 assert.equal(response.status,413);assert.equal(cancelled,true);
});

test('misleading short Content-Length does not bypass limit',async()=>{
 let cancelled=false;
 const body=chunks(new Uint8Array(100000).fill(97),1024,()=>{cancelled=true;});
 const response=await invoke(request(body,{'content-length':'4'}));
 assert.equal(response.status,413);assert.equal(cancelled,true);
});

test('too large Content-Length is rejected and body cancelled',async()=>{
 let cancelled=false;
 const stream=new ReadableStream({pull(c){c.enqueue(new Uint8Array(1));},cancel(){cancelled=true;}});
 const response=await invoke(request(stream,{'content-length':'48001'}));
 assert.equal(response.status,413);assert.equal(cancelled,true);
});

test('valid JSON accepts up to 12000 UTF-16 code units and rejects 12001',async()=>{
 const base=JSON.stringify(registration());
 const exact=JSON.stringify(registration('中'.repeat(12000-base.length)));
 assert.equal(exact.length,12000);
 assert.equal((await invoke(request(chunks(new TextEncoder().encode(exact),5)))).status,201);
 const over=JSON.stringify(registration('中'.repeat(12001-base.length)));
 assert.equal((await invoke(request(chunks(new TextEncoder().encode(over),7)))).status,413);
});

test('split supplementary Unicode is accepted, malformed JSON remains 400',async()=>{
 const valid=JSON.stringify(registration('😀'.repeat(100)));
 assert.equal((await invoke(request(chunks(new TextEncoder().encode(valid),3)))).status,201);
 assert.equal((await invoke(request('{bad json'))).status,400);
});
