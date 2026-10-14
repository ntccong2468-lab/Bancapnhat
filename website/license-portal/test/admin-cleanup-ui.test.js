import {test} from 'node:test';
import assert from 'node:assert/strict';
import {parseHTML} from 'linkedom';
import {wireAccountDeletion,wireVoidLicenseDeletion} from '../public/admin-cleanup.js';
import {mountDesktopAdmin} from '../public/desktop-admin.js';
import {envSetup,req} from './fixtures.js';
const escapeHtml=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

test('desktop admin displays a real registration and deletes it only after exact Gmail confirmation',async()=>{
 const env=envSetup(),creds={email:'owner@example.test',password:'A_Strong_Password_12345',confirm:'A_Strong_Password_12345'};
 await req(env,'/api/register','POST',creds);env.sqlite.prepare("UPDATE users SET role='admin',status='approved' WHERE email=?").run(creds.email);
 const login=await req(env,'/api/login','POST',creds),cookie=login.cookie.split(';')[0];
 const registration=await req(env,'/api/client/registration/register','POST',{email:'customer@gmail.com'});assert.equal(registration.status,201);
 const {document}=parseHTML('<main><h1>Admin</h1><section>Users</section></main>');
 const prior={document:globalThis.document,FormData:globalThis.FormData,prompt:globalThis.prompt};
 const notices=[];let typed=null,posts=0,refreshes=0;
 Object.assign(globalThis,{document,prompt:()=>typed,FormData:class {
  constructor(form){this.values=new Map([...form.querySelectorAll('[name]')].map(e=>[e.name,e.value||'']));}
  get(key){return this.values.get(key)||'';}has(key){return this.values.has(key);}
 }});
 try {
  const call=async path=>{const r=await req(env,path,'GET',null,cookie);assert.equal(r.status,200);return r.data;};
  const post=async(path,body)=>{posts++;const r=await req(env,path,'POST',body,cookie);if(r.status!==200)throw Error(r.data.error);return r.data;};
  await mountDesktopAdmin(document.querySelector('main'),{call,post,escapeHtml,humanDate:String,notice:(message,error)=>notices.push({message,error}),onAccountDeleted:async()=>{refreshes++;}});
  assert.ok(document.querySelector('#desktop-users').textContent.includes('customer@gmail.com'));
  const button=document.querySelector('[data-delete-user]');assert.ok(button);
  await button.onclick();assert.equal(posts,0);
  typed='wrong@gmail.com';await button.onclick();assert.equal(posts,0);assert.ok(notices.at(-1).error);
  typed='CUSTOMER@GMAIL.COM';await button.onclick();assert.equal(posts,1);assert.equal(refreshes,1);
  assert.ok(!document.querySelector('#desktop-users').textContent.includes('customer@gmail.com'));
  assert.equal(document.querySelector('#desktop-key').textContent,'');
  assert.equal(document.querySelector('#desktop-devices').textContent,'');
  assert.ok(notices.some(n=>!n.error&&n.message.includes('đăng ký lại')));
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) n FROM users WHERE email='customer@gmail.com'").get().n,0);
 }finally{Object.assign(globalThis,prior);env.sqlite.close();}
});

test('void license UI confirms the exact deletion and refreshes only after success',async()=>{
 const {document}=parseHTML('<div id="codes"><button data-delete-license="12345678-1234-1234-1234-123456789abc">Xóa license đã hủy</button></div>');
 const prior={confirm:globalThis.confirm};let accepted=false,fail=false,refreshes=0;const posts=[],notices=[];
 globalThis.confirm=()=>accepted;
 try {
  const root=document.querySelector('#codes');wireVoidLicenseDeletion(root,{
   post:async(path,body)=>{posts.push({path,body});if(fail)throw Error('ONLY_VOID_LICENSE_CAN_BE_DELETED');},
   notice:(message,error)=>notices.push({message,error}),onDeleted:async()=>{refreshes++;}
  });
  const button=root.querySelector('button');await button.onclick();assert.equal(posts.length,0);
  accepted=true;fail=true;await button.onclick();assert.equal(refreshes,0);assert.equal(button.disabled,false);
  assert.ok(notices.at(-1).error);assert.ok(notices.at(-1).message.startsWith('Chỉ xóa'));
  fail=false;await button.onclick();assert.equal(refreshes,1);
  assert.deepEqual(posts.at(-1),{path:'/api/admin/license-codes/12345678-1234-1234-1234-123456789abc/delete',body:{confirm:true}});
 }finally{Object.assign(globalThis,prior);}
});

test('complete admin page exposes admin desktop licensing and canceled-license filter, pagination and deletion',async()=>{
 const env=envSetup(),credentials={email:'owner@gmail.com',password:'A_Strong_Password_12345',confirm:'A_Strong_Password_12345'};
 await req(env,'/api/register','POST',credentials);env.sqlite.prepare("UPDATE users SET role='admin',status='approved' WHERE email=?").run(credentials.email);
 const login=await req(env,'/api/login','POST',credentials),cookie=login.cookie.split(';')[0];
 const customer=await req(env,'/api/client/registration/register','POST',{email:'customer@gmail.com'});
 assert.equal(customer.status,201);
 const user=env.sqlite.prepare("SELECT id FROM users WHERE email='customer@gmail.com'").get();
 const insert=env.sqlite.prepare("INSERT INTO license_codes(id,code_hash,code_last4,days,assigned_user_id,created_by,state,created_at,updated_at) VALUES(?,?,?,30,?,?,'void',1,1)");
 for(let i=0;i<55;i++)insert.run(crypto.randomUUID(),'page-'+i,'0000',user.id,login.data.user.id);
 const {window,document}=parseHTML('<html><body><nav id="nav"></nav><main id="app"></main></body></html>');
 const prior=Object.fromEntries(['document','window','location','fetch','FormData','confirm'].map(key=>[key,globalThis[key]]));
 const wait=async condition=>{for(let i=0;i<300;i++){if(condition())return;await new Promise(resolve=>setTimeout(resolve,5));}assert.fail('Admin page did not complete the expected update');};
 let releaseFilter=null,oldFilterReturned=false;
 Object.assign(globalThis,{document,window,location:{href:'https://vncode.example/admin'},confirm:()=>true,
  FormData:class {constructor(form){this.values=new Map([...form.querySelectorAll('[name]')].map(e=>[e.name,e.value||'']));}get(key){return this.values.get(key)||'';}has(key){return this.values.has(key);}},
  fetch:async(path,options={})=>{
   const r=await req(env,path,options.method||'GET',options.body?JSON.parse(options.body):null,cookie);
   const url=new URL(path,'https://vncode.example');
   if(url.pathname==='/api/admin/license-codes'&&url.searchParams.get('state')==='void'&&url.searchParams.get('page')==='0') {
    await new Promise(resolve=>{releaseFilter=resolve;});oldFilterReturned=true;
   }
   return new Response(JSON.stringify(r.data),{status:r.status,headers:{'content-type':'application/json'}});
  }
 });
 try {
  await import('../public/app.js?test='+crypto.randomUUID());
  await wait(()=>document.querySelector('#key-page')?.textContent.includes('55 license'));
  assert.ok(document.querySelector('[data-admin-desktop-license]'));
  const select=document.querySelector('#key-state');select.querySelector('[value="void"]').setAttribute('selected','');
  select.dispatchEvent(new window.Event('change'));await wait(()=>releaseFilter!==null);
  assert.equal(document.querySelector('#key-next').disabled,false);
  document.querySelector('#key-next').click();await wait(()=>document.querySelector('#key-page').textContent.includes('Trang 2'));
  assert.equal(document.querySelector('#key-list').querySelectorAll('[data-delete-license]').length,5);
  releaseFilter();await wait(()=>oldFilterReturned);await new Promise(resolve=>setTimeout(resolve,0));
  assert.ok(document.querySelector('#key-page').textContent.includes('Trang 2'));
  assert.equal(document.querySelector('#key-list').querySelectorAll('[data-delete-license]').length,5,'Late filter response must not replace the newer page');
  await document.querySelector('#key-list [data-delete-license]').onclick();
  assert.equal(document.querySelector('#key-list').querySelectorAll('[data-delete-license]').length,4);
  assert.ok(document.querySelector('#key-page').textContent.includes('54 license'));
  assert.equal(document.querySelector('#user-list [data-delete-user]').dataset.email,'customer@gmail.com');
  assert.equal(document.querySelector('#user-list [data-delete-user][data-email="owner@gmail.com"]'),null);
 }finally{releaseFilter?.();Object.assign(globalThis,prior);env.sqlite.close();}
});
