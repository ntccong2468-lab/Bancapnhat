import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import worker from '../src/index.js';
function envSetup(opts={}){
  const sqlite=new DatabaseSync(':memory:');
  sqlite.exec(readFileSync(new URL('../schema.sql', import.meta.url),'utf8'));
  let transactionQueue=Promise.resolve();
  const DB={
    prepare(sql){let binds=[];return {bind(...args){binds=args;return this;},run(){const r=sqlite.prepare(sql).run(...binds);return {meta:{changes:r.changes},...r};},first(){return sqlite.prepare(sql).get(...binds)||null;},all(){return {results:sqlite.prepare(sql).all(...binds)}}};},
    batch(prepares){
      const operation=transactionQueue.then(async()=>{
        sqlite.exec('BEGIN IMMEDIATE');
        try {const results=[];for(const stmt of prepares)results.push(await stmt.run());sqlite.exec('COMMIT');return results;}
        catch(err){sqlite.exec('ROLLBACK');throw err;}
      });
      transactionQueue=operation.catch(()=>{});return operation;
    }
  };
  return {DB, sqlite, ASSETS:{fetch:async()=>new Response('ok')}, REQUIRE_EMAIL_VERIFICATION:'0',...opts};
}
async function req(env,path,method='GET',body=null,cookie='',headers={}){
  const request=new Request('https://vncode.example'+path,{
    method,headers:{...(body?{'content-type':'application/json'}:{}),...(cookie?{cookie}:{}),...headers},body:body?JSON.stringify(body):undefined
  });
  const result=await worker.fetch(request,env);
  const data=await result.json();
  return {status:result.status,data,cookie:result.headers.get('set-cookie')};
}

export {envSetup,req};
