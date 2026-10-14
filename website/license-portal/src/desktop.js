import {mailReady} from './mail.js';
import {signingKey} from './signing-key.js';
import {licenseDiagnostics} from './license-diagnostics.js';
// Passwordless desktop registration; device proof and signed, bounded offline grants.
const utf8 = new TextEncoder();
const now = () => Math.floor(Date.now()/1000);
const hex = b => Array.from(new Uint8Array(b),x=>x.toString(16).padStart(2,'0')).join('');
const sha = async s => hex(await crypto.subtle.digest('SHA-256',utf8.encode(s)));
const b64 = b => btoa(String.fromCharCode(...new Uint8Array(b))).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,'');
function decode(s,size) {
 if(typeof s!=='string'||! /^[A-Za-z0-9_-]+$/.test(s)) throw new Error('INVALID_PROOF');
 const b=Uint8Array.from(atob(s.replaceAll('-','+').replaceAll('_','/')+'='.repeat((4-s.length%4)%4)),x=>x.charCodeAt(0));
 if(size && b.length!==size || b64(b)!==s) throw new Error('INVALID_PROOF');
 return b;
}
const token = () => b64(crypto.getRandomValues(new Uint8Array(32)));
const reply = (data,status=200) => new Response(JSON.stringify(data),{status,headers:{'content-type':'application/json; charset=utf-8','cache-control':'no-store'}});
const error = (code,status=400) => reply({error:code},status);
const bearer = r => (r.headers.get('authorization')||'').match(/^Bearer ([A-Za-z0-9_-]{43})$/)?.[1];
function policyValues(b) {
 if(!Number.isInteger(b.max_devices)||b.max_devices<1||b.max_devices>10 || !Number.isInteger(b.max_shops)||b.max_shops<1||b.max_shops>100 || !Number.isInteger(b.offline_hours)||b.offline_hours<0||b.offline_hours>168) throw new Error('INVALID_POLICY');
 return [b.max_devices,b.max_shops,b.offline_hours];
}
async function registration(env,credential) {
 return env.DB.prepare('SELECT * FROM desktop_registrations WHERE token_hash=? AND verified=1 AND expires_at>?').bind(await sha(credential),now()).first();
}
async function deviceSession(env,credential) {
 return env.DB.prepare(`SELECT d.* FROM desktop_sessions s JOIN license_devices d ON d.id=s.device_id WHERE s.token_hash=? AND s.expires_at>?`).bind(await sha(credential),now()).first();
}
async function lease(env,device,authority) {
 const current=await env.DB.prepare(`SELECT u.id account_id,u.email,u.role,u.status,u.email_verified,u.license_expires_at,
  p.max_devices,p.max_shops,p.offline_hours,p.revision,d.id device_id,d.key_hash,d.revoked
  FROM license_devices d JOIN users u ON u.id=d.user_id JOIN license_policy p ON p.user_id=u.id
  WHERE d.id=? AND d.user_id=?`).bind(device.id,device.user_id).first();
 if(!current||!['user','admin'].includes(current.role)||current.status!=='approved'||!current.license_expires_at||current.license_expires_at<=now())return error('SUBSCRIPTION_INACTIVE',403);
 if(current.revoked||current.key_hash!==device.key_hash)return error('DEVICE_REVOKED',403);
 const issuedAt=now(),expiresAt=Math.min(current.license_expires_at,issuedAt+(current.offline_hours||0)*3600);
 const payload={formatVersion:1,product:'vncode',accountId:current.account_id,deviceId:current.device_id,devicePublicKeyHash:current.key_hash,licenseRevision:current.revision,issuedAt,notBefore:issuedAt,expiresAt,subscriptionExpiresAt:current.license_expires_at,maxDevices:current.max_devices,maxShops:current.max_shops,offlineHours:current.offline_hours,permissions:['seller_api','shop_manage','gtin','kiz','listing_write','packing','print_new','gs1']};
 const bytes=utf8.encode(JSON.stringify(payload));
 const signature=b64(await crypto.subtle.sign('Ed25519',authority.key,bytes));
 // Recheck authority after signing. A simultaneous revoke, expiry or policy change cannot
 // turn the earlier snapshot into a newly returned grant.
 const saved=await env.DB.prepare(`UPDATE license_devices SET last_seen_at=?,last_lease_expires_at=MAX(COALESCE(last_lease_expires_at,0),?)
  WHERE id=? AND user_id=? AND key_hash=? AND revoked=0 AND EXISTS(
   SELECT 1 FROM users u JOIN license_policy p ON p.user_id=u.id
   WHERE u.id=? AND u.role IN ('user','admin') AND u.status='approved'
   AND u.license_expires_at=? AND u.license_expires_at>? AND p.revision=?)`)
  .bind(issuedAt,expiresAt,current.device_id,current.account_id,current.key_hash,current.account_id,current.license_expires_at,now(),current.revision).run();
 if(!saved.meta?.changes)return error('LICENSE_CHANGED',403);
 return {user:{id:current.account_id,email:current.email,status:current.status,active:true,expires_at:current.license_expires_at},device_id:current.device_id,lease:{key_id:authority.keyId,payload:b64(bytes),signature},online_valid_until:Math.min(current.license_expires_at,issuedAt+300)};
}
export async function desktopApi(request,env,h,admin=null) {
 const path=new URL(request.url).pathname,method=request.method;
 if(!path.startsWith('/api/client/registration/')&&!path.startsWith('/api/client/license/')&&!path.startsWith('/api/admin/desktop/')) return null;
 try {
 if(path.startsWith('/api/admin/desktop/')) {
  if(!admin || admin.role!=='admin')return error('ADMIN_REQUIRED',403);
  return await adminApi(request,env,h,admin);
 }
 const credential=bearer(request);
 if(method==='POST'&&path==='/api/client/registration/license') {
  const b=await h.bodyJson(request),code=h.normalizeLicenseCode(b.code);
  if(!code)return error('INVALID_LICENSE');
  if(!await h.rateLimit(env,'desktop-code-ip:'+h.clientIp(request),20,3600)||!await h.rateLimit(env,'desktop-code:'+await sha(code),20,3600))return error('RATE_LIMIT',429);
  const user=await env.DB.prepare(`SELECT u.id,u.email,u.status FROM license_codes c JOIN users u ON u.id=c.assigned_user_id
   WHERE c.code_hash=? AND u.role IN ('user','admin') AND u.status='approved' AND
   (c.state='unused' OR (c.state='redeemed' AND c.redeemed_by=u.id AND u.license_expires_at>?))`).bind(await sha(code),now()).first();
  if(!user)return error('INVALID_LICENSE',403);
  const id=crypto.randomUUID(),registration_token=token(),time=now();
  // Knowing the issued key identifies its account, but allocates no device or rights.
  // Activation still requires device proof and atomic capacity/code checks below.
  await env.DB.prepare(`INSERT INTO desktop_registrations(id,email,otp_hash,token_hash,verified,user_id,expires_at,created_at)
   VALUES(?,?,'',?,1,?,?,?)`).bind(id,user.email,await sha(registration_token),user.id,time+30*86400,time).run();
  return reply({registration_id:id,registration_token,email:user.email,status:user.status,expires_in:30*86400},201);
 }
 if(method==='POST'&&path==='/api/client/registration/register') {
  const b=await h.bodyJson(request),email=typeof b.email==='string'?b.email.trim().toLowerCase():'';
  if(!/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@gmail\.com$/.test(email)||email.length>254)return error('GMAIL_REQUIRED');
  if(!await h.rateLimit(env,'desktop-register-ip:'+h.clientIp(request),7,3600)||!await h.rateLimit(env,'desktop-register-email:'+await sha(email),3,3600))return error('RATE_LIMIT',429);
  const id=crypto.randomUUID(),userId=crypto.randomUUID(),registration_token=token(),time=now();
  // This credential can only read registration status and redeem an assigned key.
  // It cannot log into the website or renew a device. Gmail entry is NOT proof of
  // mailbox ownership: existing passwords, verification, approval and rights stay intact.
  await env.DB.batch([
   env.DB.prepare(`INSERT OR IGNORE INTO users(id,email,password_salt,password_hash,role,status,email_verified,created_at,updated_at) VALUES(?,?,?,?,'user','pending',0,?,?)`).bind(userId,email,token(),token(),time,time),
   env.DB.prepare(`INSERT INTO desktop_registrations(id,email,otp_hash,token_hash,verified,user_id,expires_at,created_at)
    SELECT ?,?,'',?,1,u.id,?,? FROM users u WHERE u.email=? AND u.role IN ('user','admin')`).bind(id,email,await sha(registration_token),time+30*86400,time,email),
   env.DB.prepare("INSERT OR IGNORE INTO license_policy(user_id) SELECT id FROM users WHERE email=? AND role IN ('user','admin')").bind(email)
  ]);
  const reg=await registration(env,registration_token);
  if(!reg)return error('ACCOUNT_NOT_APPROVED',403);
  const user=await env.DB.prepare('SELECT status FROM users WHERE id=?').bind(reg.user_id).first();
  await h.audit(env,null,reg.user_id,'desktop_registration_requested');
  return reply({registration_id:id,registration_token,email,status:user.status,expires_in:30*86400},201);
 }
 if(method==='POST'&&path==='/api/client/registration/start') {
  if(!mailReady(env))return error('EMAIL_NOT_CONFIGURED',503);
  const b=await h.bodyJson(request),email=typeof b.email==='string'?b.email.trim().toLowerCase():'';
  if(!/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@gmail\.com$/.test(email)||email.length>254)return error('GMAIL_REQUIRED');
  if(!await h.rateLimit(env,'desktop-register-ip:'+h.clientIp(request),7,3600)||!await h.rateLimit(env,'desktop-register-email:'+await sha(email),3,3600))return error('RATE_LIMIT',429);
  const id=crypto.randomUUID(),registration_token=token(),raw=new Uint32Array(1);let n;
  do {crypto.getRandomValues(raw);n=raw[0];}while(n>=4294000000);
  const otp=String(n%1000000).padStart(6,'0');
  await env.DB.prepare('INSERT INTO desktop_registrations(id,email,otp_hash,token_hash,expires_at,created_at) VALUES(?,?,?,?,?,?)').bind(id,email,await sha(id+':'+otp),await sha(registration_token),now()+600,now()).run();
  if(!await h.sendMail(env,email,'Mã xác minh VNCode',`Mã xác minh: ${otp}\nCó hiệu lực 10 phút. Không chia sẻ mã này.`)) {
   await env.DB.prepare('DELETE FROM desktop_registrations WHERE id=?').bind(id).run();return error('EMAIL_DELIVERY_FAILED',503);
  }
  return reply({registration_id:id,registration_token,expires_in:600},201);
 }
 if(!credential)return error('AUTH_REQUIRED',401);
 if(method==='POST'&&path==='/api/client/registration/verify') {
  const b=await h.bodyJson(request);
  if(!/^[0-9]{6}$/.test(b.code||''))return error('INVALID_CODE');
  const row=await env.DB.prepare('SELECT * FROM desktop_registrations WHERE token_hash=? AND verified=0 AND expires_at>? AND attempts<5').bind(await sha(credential),now()).first();
  if(!row)return error('REGISTRATION_EXPIRED',401);
  const attempt=await env.DB.prepare('UPDATE desktop_registrations SET attempts=attempts+1 WHERE id=? AND verified=0 AND expires_at>? AND attempts<5').bind(row.id,now()).run();
  if(!attempt.meta.changes)return error('REGISTRATION_EXPIRED',401);
  if(!h.constantTimeEqual(row.otp_hash,await sha(row.id+':'+b.code)))return error('INVALID_CODE');
  const claim=crypto.randomUUID(),salt=token(),hash=token();
  await env.DB.batch([
   env.DB.prepare('UPDATE desktop_registrations SET verified=1,expires_at=? WHERE id=? AND verified=0 AND expires_at>?').bind(now()+30*86400,row.id,now()),
   // Random unreachable password; existing web credentials/status/expiry are preserved.
   env.DB.prepare(`INSERT OR IGNORE INTO users(id,email,password_salt,password_hash,role,status,email_verified,created_at,updated_at) VALUES(?,?,?,?,'user','pending',1,?,?)`).bind(claim,row.email,salt,hash,now(),now()),
   env.DB.prepare('UPDATE users SET email_verified=1 WHERE email=?').bind(row.email),
   env.DB.prepare('UPDATE desktop_registrations SET user_id=(SELECT id FROM users WHERE email=?) WHERE id=? AND verified=1').bind(row.email,row.id),
   env.DB.prepare('INSERT OR IGNORE INTO license_policy(user_id) SELECT id FROM users WHERE email=?').bind(row.email)
  ]);
  const user=await env.DB.prepare('SELECT id,status FROM users WHERE email=?').bind(row.email).first();
  await h.audit(env,user.id,user.id,'desktop_registered');
  return reply({email:row.email,status:user.status,registration_expires_in:30*86400});
 }
 if(method==='GET'&&path==='/api/client/registration/status') {
  const reg=await registration(env,credential);if(!reg)return error('REGISTRATION_EXPIRED',401);
  const user=await env.DB.prepare('SELECT email,status,license_expires_at FROM users WHERE id=?').bind(reg.user_id).first();
  return reply({email:user.email,status:user.status,expires_at:user.license_expires_at});
 }
 if(method==='POST'&&path==='/api/client/license/challenge') {
  const b=await h.bodyJson(request);
  if(!['activate','renew'].includes(b.action))return error('INVALID_ACTION');
  decode(b.public_key,32);
  const principal=b.action==='activate'?await registration(env,credential):await deviceSession(env,credential);
  if(!principal)return error('AUTH_REQUIRED',401);
  if(b.action==='renew'&&(principal.revoked||principal.public_key!==b.public_key))return error('DEVICE_REVOKED',403);
  if(!await h.rateLimit(env,'desktop-challenge:'+await sha(credential),60,3600))return error('RATE_LIMIT',429);
  const id=crypto.randomUUID(),message=`vncode-device-v1\n${b.action}\n${id}\n${token()}`,expires_at=now()+120;
  await env.DB.prepare('INSERT INTO desktop_challenges(id,credential_hash,key_hash,action,message,expires_at) VALUES(?,?,?,?,?,?)').bind(id,await sha(credential),await sha(b.public_key),b.action,message,expires_at).run();
  return reply({challenge_id:id,message,expires_at});
 }
 if(method==='POST'&&['/api/client/license/activate','/api/client/license/renew'].includes(path)) {
  const action=path.endsWith('/activate')?'activate':'renew',b=await h.bodyJson(request);
  if(!await h.rateLimit(env,'desktop-license:'+await sha(credential),30,3600))return error('RATE_LIMIT',429);
  const key=await signingKey(env);decode(b.public_key,32);const sig=decode(b.signature,64);
  const challenge=await env.DB.prepare('SELECT * FROM desktop_challenges WHERE id=? AND credential_hash=? AND action=? AND key_hash=? AND used=0 AND expires_at>?').bind(b.challenge_id||'',await sha(credential),action,await sha(b.public_key),now()).first();
  if(!challenge)return error('CHALLENGE_EXPIRED_OR_USED',409);
  const pub=await crypto.subtle.importKey('raw',decode(b.public_key,32),{name:'Ed25519'},false,['verify']);
  if(!await crypto.subtle.verify('Ed25519',pub,sig,utf8.encode(challenge.message)))return error('INVALID_PROOF',403);
  const claim=crypto.randomUUID();
  const used=await env.DB.prepare('UPDATE desktop_challenges SET used=1,claim=? WHERE id=? AND used=0 AND expires_at>?').bind(claim,challenge.id,now()).run();
  if(!used.meta.changes)return error('CHALLENGE_EXPIRED_OR_USED',409);
  if(action==='renew') {
   const device=await deviceSession(env,credential);if(!device)return error('SESSION_EXPIRED',401);
   if(device.revoked||device.public_key!==b.public_key)return error('DEVICE_REVOKED',403);
   const out=await lease(env,device,key);return out instanceof Response?out:reply(out);
  }
  const reg=await registration(env,credential);if(!reg)return error('REGISTRATION_EXPIRED',401);
  const user=await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(reg.user_id).first();
  if(!['user','admin'].includes(user.role)||user.status!=='approved')return error('ACCOUNT_NOT_APPROVED',403);
  const code=h.normalizeLicenseCode(b.code);if(!code)return error('INVALID_LICENSE');
  const license=await env.DB.prepare('SELECT * FROM license_codes WHERE code_hash=? AND assigned_user_id=?').bind(await sha(code),user.id).first();
  if(!license||license.state==='void')return error('INVALID_LICENSE',403);
  if(license.state==='redeemed'&&license.redeemed_by!==user.id)return error('INVALID_LICENSE',403);
  if(license.state==='redeemed'&&user.license_expires_at<=now())return error('SUBSCRIPTION_INACTIVE',403);
  const id=crypto.randomUUID(),keyHash=await sha(b.public_key),time=now(),sessionToken=token(),redemption=crypto.randomUUID();
  const existing=await env.DB.prepare('SELECT * FROM license_devices WHERE user_id=? AND key_hash=?').bind(user.id,keyHash).first();
  if(existing?.revoked)return error('DEVICE_REVOKED',403);
  const deviceId=existing?.id||id;
  // D1 batch serializes capacity allocation and redemption. Every write is conditional
  // on the current approval/code/device state, never just the earlier read.
  await env.DB.batch([
   env.DB.prepare('INSERT OR IGNORE INTO license_policy(user_id) VALUES(?)').bind(user.id),
   env.DB.prepare(`INSERT OR IGNORE INTO license_devices(id,user_id,public_key,key_hash,name,created_at,last_seen_at)
    SELECT ?,?,?,?,?,?,? FROM users u JOIN license_codes c ON c.assigned_user_id=u.id JOIN license_policy p ON p.user_id=u.id
    WHERE u.id=? AND u.status='approved' AND c.id=? AND (c.state='unused' OR (c.state='redeemed' AND u.license_expires_at>?))
    AND (SELECT COUNT(*) FROM license_devices WHERE user_id=u.id AND revoked=0)<p.max_devices`).bind(deviceId,user.id,b.public_key,keyHash,String(b.device_name||'VNCode Windows').slice(0,80),time,time,user.id,license.id,time),
   env.DB.prepare(`INSERT OR IGNORE INTO license_redemptions(id,code_id,user_id,claimed_at) SELECT ?,c.id,?,? FROM license_codes c JOIN users u ON u.id=c.assigned_user_id
    WHERE c.id=? AND c.state='unused' AND u.status='approved' AND EXISTS(SELECT 1 FROM license_devices WHERE id=? AND user_id=u.id AND revoked=0)`).bind(redemption,user.id,time,license.id,deviceId),
   env.DB.prepare(`UPDATE users SET license_expires_at=MAX(COALESCE(license_expires_at,0),?)+(SELECT days*86400 FROM license_codes WHERE id=?),updated_at=?
    WHERE id=? AND status='approved' AND EXISTS(SELECT 1 FROM license_redemptions WHERE id=?)`).bind(time,license.id,time,user.id,redemption),
   env.DB.prepare(`UPDATE license_codes SET state='redeemed',redeemed_by=?,redeemed_at=?,updated_at=? WHERE id=? AND EXISTS(SELECT 1 FROM license_redemptions WHERE id=?)`).bind(user.id,time,time,license.id,redemption),
   env.DB.prepare(`INSERT INTO desktop_sessions(token_hash,device_id,expires_at) SELECT ?,d.id,? FROM license_devices d JOIN users u ON u.id=d.user_id JOIN license_codes c ON c.assigned_user_id=u.id WHERE d.id=? AND d.revoked=0 AND u.status='approved' AND u.license_expires_at>? AND c.id=? AND c.state='redeemed'`).bind(await sha(sessionToken),time+30*86400,deviceId,time,license.id)
  ]);
  const device=await deviceSession(env,sessionToken);
  if(!device)return error('DEVICE_LIMIT_OR_LICENSE_CHANGED',409);
  await h.audit(env,user.id,device.id,'desktop_activated');
  const out=await lease(env,device,key);return out instanceof Response?out:reply({...out,access_token:sessionToken,expires_in:30*86400});
 }
 return error('NOT_FOUND',404);
 } catch(e) {
  if(e.message==='SIGNING_NOT_CONFIGURED')return error(e.message,503);
  if(['INVALID_POLICY','INVALID_PROOF'].includes(e.message))return error(e.message);
  throw e;
 }
}

async function adminApi(request,env,h,admin) {
 const path=new URL(request.url).pathname,method=request.method;
 if(method==='GET'&&path==='/api/admin/desktop/diagnostics')return reply(await licenseDiagnostics(env));
 if(method==='GET'&&path==='/api/admin/desktop/requests') {
  const url=new URL(request.url),page=Math.min(1000,Math.max(0,Math.trunc(Number(url.searchParams.get('page'))||0))),status=url.searchParams.get('status')||'',search=(url.searchParams.get('search')||'').slice(0,254);
  if(status&&!['pending','approved','suspended'].includes(status))return error('INVALID_STATUS');
  const where=`u.role IN ('user','admin') AND (?='' OR u.status=?) AND instr(lower(u.email),lower(?))>0`;
  const rows=(await env.DB.prepare(`SELECT u.id,u.email,u.role,u.status,u.email_verified,u.license_expires_at,u.created_at,COALESCE(p.max_devices,1) max_devices,COALESCE(p.max_shops,1) max_shops,COALESCE(p.offline_hours,72) offline_hours,
   (SELECT COUNT(*) FROM license_devices d WHERE d.user_id=u.id AND d.revoked=0) device_count FROM users u LEFT JOIN license_policy p ON p.user_id=u.id WHERE ${where} ORDER BY u.created_at DESC,u.id LIMIT 50 OFFSET ?`).bind(status,status,search,page*50).all()).results;
  const count=await env.DB.prepare(`SELECT COUNT(*) n FROM users u WHERE ${where}`).bind(status,status,search).first();
  return reply({users:rows,total:count.n,page});
 }
 if(method==='GET'&&path==='/api/admin/desktop/settings'){
  let signing_ready=false;try{await signingKey(env);signing_ready=true;}catch{}
  return reply({mail_ready:mailReady(env),signing_ready,signing_key_present:!!env.LICENSE_SIGNING_KEY,key_id:env.LICENSE_KEY_ID||null,registration_mode:'gmail_admin',registration_requires_otp:false,default_max_devices:1,default_max_shops:1,default_offline_hours:72});
 }
 const match=path.match(/^\/api\/admin\/desktop\/users\/([a-f0-9-]{36})\/(approve-issue|policy|devices)$/);
 if(match) {
  const id=match[1],action=match[2],user=await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(id).first();
  if(!user||!['user','admin'].includes(user.role))return error('USER_NOT_FOUND',404);
  if(action==='devices'&&method==='GET')return reply({devices:(await env.DB.prepare('SELECT id,name,revoked,created_at,last_seen_at,last_lease_expires_at FROM license_devices WHERE user_id=? ORDER BY created_at DESC LIMIT 100').bind(id).all()).results});
  if(method!=='POST')return error('NOT_FOUND',404);
  const b=await h.bodyJson(request),values=policyValues(b);
  if(user.status==='suspended')return error('ACCOUNT_SUSPENDED',403);
  if(action==='approve-issue'&&(!Number.isInteger(b.days)||b.days<1||b.days>3650))return error('INVALID_DAYS');
  if(action==='approve-issue'&&!/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@gmail\.com$/.test(user.email))return error('GMAIL_REQUIRED');
  const active=await env.DB.prepare('SELECT COUNT(*) n FROM license_devices WHERE user_id=? AND revoked=0').bind(id).first();
  if(values[0]<active.n)return error('REVOKE_DEVICES_BEFORE_REDUCING_LIMIT',409);
  const writes=[env.DB.prepare(`INSERT INTO license_policy(user_id,max_devices,max_shops,offline_hours) VALUES(?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET max_devices=excluded.max_devices,max_shops=excluded.max_shops,offline_hours=excluded.offline_hours,revision=revision+1`).bind(id,...values)];
  let code;
  if(action==='approve-issue') {
   if(!await h.rateLimit(env,'desktop-issue:'+admin.id,100,3600))return error('RATE_LIMIT',429);
   code=h.newLicenseCode();
   writes.push(env.DB.prepare("UPDATE users SET status='approved',updated_at=? WHERE id=? AND status<>'suspended'").bind(now(),id));
   writes.push(env.DB.prepare(`INSERT INTO license_codes(id,code_hash,code_last4,days,assigned_user_id,created_by,state,created_at,updated_at) VALUES(?,?,?,?,?,?,'unused',?,?)`).bind(crypto.randomUUID(),await sha(code),code.slice(-4),b.days,id,admin.id,now(),now()));
  }
  writes.push(env.DB.prepare('INSERT INTO audit_log(id,actor_id,target_id,action,detail,created_at) VALUES(?,?,?,?,?,?)').bind(crypto.randomUUID(),admin.id,id,'desktop_'+action,JSON.stringify({max_devices:values[0],max_shops:values[1],offline_hours:values[2],days:b.days||null}),now()));
  await env.DB.batch(writes);
  // Full key is returned once for the owner to deliver; never stored in plaintext.
  let email_sent=false;
  if(code&&b.send_email===true)email_sent=await h.sendMail(env,user.email,'Khóa license VNCode',`Đăng ký của bạn đã được duyệt.\nKhóa license: ${code}\nNhập khóa trong mục License của VNCode. Thời hạn ${b.days} ngày tính từ khi kích hoạt.`);
  return reply({ok:true,...(code?{code,email_sent}: {})});
 }
 const revoke=path.match(/^\/api\/admin\/desktop\/devices\/([a-f0-9-]{36})\/revoke$/);
 if(revoke&&method==='POST') {
  const d=await env.DB.prepare('SELECT * FROM license_devices WHERE id=?').bind(revoke[1]).first();if(!d)return error('DEVICE_NOT_FOUND',404);
  await env.DB.batch([
   env.DB.prepare('UPDATE license_devices SET revoked=1 WHERE id=?').bind(d.id),
   env.DB.prepare('UPDATE license_policy SET revision=revision+1 WHERE user_id=?').bind(d.user_id),
   env.DB.prepare('INSERT INTO audit_log(id,actor_id,target_id,action,detail,created_at) VALUES(?,?,?,?,?,?)').bind(crypto.randomUUID(),admin.id,d.id,'device_revoked','',now())
  ]);
  return reply({ok:true,last_offline_expires_at:d.last_lease_expires_at});
 }
 return error('NOT_FOUND',404);
}
