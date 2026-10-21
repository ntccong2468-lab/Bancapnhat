import {test} from 'node:test';
import assert from 'node:assert/strict';
import {parseHTML} from 'linkedom';
import {mountDesktopAdmin} from '../public/desktop-admin.js';
const flush=()=>new Promise(resolve=>setTimeout(resolve,20));
test('Admin renders pending Gmail, issues a key, exposes delivery failure and revokes devices',async()=>{
 const {document,Event}=parseHTML('<html><body><main><h1>Admin</h1><section>Existing</section></main></body></html>');
 const prior={document:globalThis.document,FormData:globalThis.FormData,confirm:globalThis.confirm};
 globalThis.document=document;globalThis.confirm=()=>true;
 globalThis.FormData=class {constructor(form){this.values=new Map();for(const e of form.querySelectorAll('[name]')){if(e.type==='checkbox'){if(e.hasAttribute('checked'))this.values.set(e.name,'on');}else this.values.set(e.name,e.value??e.getAttribute('value')??'');}}get(k){return this.values.get(k)||'';}has(k){return this.values.has(k);}};
 const posts=[],id='12345678-1234-1234-1234-123456789abc',deviceId='12345678-1234-1234-1234-123456789def';let revoked=false;
 try {
  const call=async path=>{
   if(path.endsWith('/settings'))return {mail_ready:true,signing_ready:false,signing_key_present:true};
   if(path.endsWith('/diagnostics'))return {signing_key_valid:false,signature_matches_client:false,key_id_matches:true,database_ready:true,schema:[],activation_configuration_ready:false};
   if(path.includes('/requests?'))return {users:[{id,email:'client@gmail.com',status:'pending',email_verified:0,license_expires_at:null,max_devices:1,max_shops:2,offline_hours:72,device_count:0}],total:1};
   if(path.endsWith('/devices'))return {devices:[{id:deviceId,name:'<img src=x onerror=alert(1)>',revoked,last_seen_at:1,last_lease_expires_at:2}]};
   throw new Error('Unexpected request');
  };
  const post=async(path,body)=>{posts.push({path,body});if(path.endsWith('/revoke')){revoked=true;return {ok:true};}return {code:'VNC-TEST',email_sent:false};};
  const esc=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
  await mountDesktopAdmin(document.querySelector('main'),{call,post,escapeHtml:esc,humanDate:String,notice:()=>{}});
  assert.ok(document.querySelector('#desktop-settings').textContent.includes('Có Secret nhưng khóa không hợp lệ'));
  await document.querySelector('#desktop-license-diagnose').onclick();
  assert.ok(document.querySelector('#desktop-license-diagnostics').textContent.includes('Khóa ký hợp lệ: Không'));
  assert.ok(document.querySelector('#desktop-license-diagnostics').textContent.includes('Chưa đủ cấu hình'));
  assert.equal(document.querySelector('#desktop-license-diagnose').disabled,false);
  assert.equal(posts.length,0,'Read-only diagnostics must not issue licenses');
  assert.ok(document.querySelector('#desktop-users').textContent.includes('client@gmail.com'));
  const form=document.querySelector('[data-user]'),button=form.querySelector('[value="approve-issue"]');form.querySelector('[name="send_email"]').setAttribute('checked','');
  assert.equal(button.hasAttribute('disabled'),false,'Owner can approve Gmail without OTP');
  const event=new Event('submit',{bubbles:true,cancelable:true});event.submitter=button;form.dispatchEvent(event);await flush();
  assert.equal(posts[0].path,`/api/admin/desktop/users/${id}/approve-issue`);assert.equal(posts[0].body.send_email,true);assert.equal(document.querySelector('#desktop-key input').value,'VNC-TEST');assert.ok(document.querySelector('#desktop-key').textContent.includes('chưa thành công'));
  await document.querySelector('[data-devices]').onclick();assert.equal(document.querySelector('#desktop-devices img'),null);
  await document.querySelector('[data-revoke]').onclick();assert.equal(posts.at(-1).path,`/api/admin/desktop/devices/${deviceId}/revoke`);assert.ok(document.querySelector('#desktop-devices').textContent.includes('Đã thu hồi'));
 }finally{Object.assign(globalThis,prior);}
});
