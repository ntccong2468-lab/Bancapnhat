import {sendMail,mailReady} from './mail.js';
import {desktopApi} from './desktop.js';
import {adminCleanup} from './admin-cleanup.js';
import {cleanupExpired} from './maintenance.js';
// VNCode Accounts — Cloudflare Workers + D1. Account/entitlement MVP.
const enc = new TextEncoder();
const SESSION_SECONDS = 7 * 24 * 3600;
// Cloudflare Workers caps each Web Crypto PBKDF2 deriveBits invocation at 100,000 rounds.
// Using more than 100,000 causes registration and password hashing to fail with HTTP 500
// on deployed Workers, even when Node.js local tests pass.
// This is a compatibility value for the current MVP; use a stronger managed identity
// solution before allowing public registration (see README security note).
const ITERATIONS = 100000;
const now = () => Math.floor(Date.now() / 1000);
const randomToken = () => b64url(crypto.getRandomValues(new Uint8Array(32)));
const newLicenseCode = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const value = Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('').toUpperCase();
  return `VNC-${value.match(/.{1,4}/g).join('-')}`;
};
const normalizeLicenseCode = (value) => typeof value === 'string' && /^VNC(?:-[A-F0-9]{4}){8}$/i.test(value.trim()) ? value.trim().toUpperCase() : null;
const hex = (bytes) => Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, '0')).join('');
const b64url = (bytes) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
const unb64url = (val) => Uint8Array.from(atob(val.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - val.length % 4) % 4)), c => c.charCodeAt(0));
const sha = async (s) => hex(await crypto.subtle.digest('SHA-256', enc.encode(s)));

async function hashPassword(password, salt = randomToken()) {
  const key = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits({name:'PBKDF2', salt:unb64url(salt), iterations:ITERATIONS, hash:'SHA-256'}, key, 256);
  return { salt, hash: hex(bits) };
}
function constantTimeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
async function verifyPassword(password, salt, hash) {
  return constantTimeEqual((await hashPassword(password, salt)).hash, hash);
}
function json(data, status=200, headers={}) {
  return new Response(JSON.stringify(data), {status, headers:{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff', ...headers}});
}
function fail(message, status=400) { return json({error:message},status); }
function clientIp(request) { return request.headers.get('CF-Connecting-IP') || 'local'; }
function requireOrigin(request) {
  const origin = request.headers.get('Origin');
  return !origin || origin === new URL(request.url).origin;
}
function getCookie(request, key) {
  const item = (request.headers.get('cookie')||'').split(';').map(x=>x.trim()).find(s=>s.startsWith(key+'='));
  return item ? item.slice(key.length+1) : '';
}
function cookieName(request) {return new URL(request.url).hostname === 'localhost' || new URL(request.url).hostname === '127.0.0.1' ? 'vncode_session' : '__Host-vncode_session';}
function sessionCookie(request, token, maxAge=SESSION_SECONDS) {
  const dev = new URL(request.url).hostname === 'localhost' || new URL(request.url).hostname === '127.0.0.1';
  return `${cookieName(request)}=${token}; Path=/; HttpOnly; SameSite=Strict; ${dev?'':'Secure; '}Max-Age=${maxAge}`;
}
async function bodyJson(request) {
  if (Number(request.headers.get('content-length')||0) > 12000) throw new Error('Dữ liệu quá lớn');
  const body = await request.text();
  if (body.length > 12000) throw new Error('Dữ liệu quá lớn');
  try {const value=JSON.parse(body);if(!value||Array.isArray(value)||typeof value!=='object')throw new Error();return value;} catch {throw new Error('INVALID_JSON');}
}
function emailOf(v) {return typeof v==='string' ? v.trim().toLowerCase() : '';}
function emailValid(s){return s.length<=254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s);}
function passwordValid(p){return typeof p === 'string' && p.length >=14 && p.length <=128;}
async function rateLimit(env, key, limit, windowSeconds) {
  const t=now(); const until=t+windowSeconds;
  await env.DB.prepare(`INSERT INTO rate_limits(key,count,expires_at) VALUES(?,1,?)
    ON CONFLICT(key) DO UPDATE SET count=CASE WHEN expires_at<? THEN 1 ELSE count+1 END,
    expires_at=CASE WHEN expires_at<? THEN excluded.expires_at ELSE expires_at END`).bind(key,until,t,t).run();
  const row=await env.DB.prepare('SELECT count FROM rate_limits WHERE key=?').bind(key).first();
  return (row?.count||0)<=limit;
}
async function turnstileOk(env, request, token) {
  if (!env.TURNSTILE_SECRET) return true; // Enable in production (see README).
  if (!token || typeof token!=='string') return false;
  try {
    const data = new FormData(); data.set('secret',env.TURNSTILE_SECRET); data.set('response',token); data.set('remoteip',clientIp(request));
    const r=await fetch('https://challenges.cloudflare.com/turnstile/v0/siteverify',{method:'POST',body:data});
    const x=await r.json(); return x.success===true;
  } catch {return false;}
}
function entitlement(user, env){
  return {active:user.role==='admin' || (user.status==='approved' && (env.REQUIRE_EMAIL_VERIFICATION!=='1'||user.email_verified===1) && Number(user.license_expires_at||0)>now()), expires_at:user.license_expires_at||null};
}
function safeUser(row,env){
  return {id:row.id,email:row.email,role:row.role,status:row.status,email_verified:!!row.email_verified,created_at:row.created_at,...entitlement(row,env)};
}
async function auth(request,env) {
  let token=''; const bearer=request.headers.get('authorization')||'';
  if(bearer.startsWith('Bearer ')) token=bearer.slice(7).trim();
  else token=getCookie(request,cookieName(request));
  if (!token||token.length>200) return null;
  const user=await env.DB.prepare(`SELECT u.* FROM sessions s JOIN users u ON s.user_id=u.id
    WHERE s.token_hash=? AND s.expires_at>?`).bind(await sha(token),now()).first();
  return user||null;
}
async function newSession(env,user) {
  const token=randomToken();
  await env.DB.prepare('INSERT INTO sessions(token_hash,user_id,expires_at,created_at) VALUES(?,?,?,?)').bind(await sha(token),user.id,now()+SESSION_SECONDS,now()).run();
  return token;
}
async function audit(env,actor,target,action,detail='') {
  await env.DB.prepare('INSERT INTO audit_log(id,actor_id,target_id,action,detail,created_at) VALUES(?,?,?,?,?,?)').bind(crypto.randomUUID(),actor,target,action,detail,now()).run();
}
async function createActionToken(env,userId,purpose,expiresSeconds){
  const token=randomToken();
  await env.DB.prepare('DELETE FROM action_tokens WHERE user_id=? AND purpose=?').bind(userId,purpose).run();
  await env.DB.prepare('INSERT INTO action_tokens(token_hash,user_id,purpose,expires_at,created_at) VALUES(?,?,?,?,?)').bind(await sha(token),userId,purpose,now()+expiresSeconds,now()).run();
  return token;
}
async function issueVerifyEmail(env,user){
  if(!mailReady(env)) return false;
  const token=await createActionToken(env,user.id,'verify',24*3600);
  const base=env.PUBLIC_BASE_URL||'https://vncode.id.vn';
  return sendMail(env,user.email,'Xác minh email VNCode',`Xin chào!\nXác minh email VNCode: ${base}/?verify=${encodeURIComponent(token)}\nLiên kết có hiệu lực 24 giờ.`);
}

async function api(request,env) {
  const url=new URL(request.url), path=url.pathname, method=request.method;
  if(method!=='GET' && method!=='HEAD' && !requireOrigin(request)) return fail('Nguồn yêu cầu không hợp lệ',403);
  const desktop=await desktopApi(request,env,{bodyJson,rateLimit,clientIp,sendMail,constantTimeEqual,audit,normalizeLicenseCode,newLicenseCode},path.startsWith('/api/admin/desktop/')?await auth(request,env):null);
  if(desktop)return desktop;
  if (method==='GET' && path==='/api/health') return json({ok:true,service:'VNCode Accounts'});
  if (method==='GET' && path==='/api/config') return json({turnstile_site_key:env.TURNSTILE_SITE_KEY||'',email_verification_required:env.REQUIRE_EMAIL_VERIFICATION==='1',mail_ready:mailReady(env),desktop_registration_mode:'gmail_admin',portal_version:'0.3.5'});

  if(method==='POST' && path==='/api/register'){
    if(!await rateLimit(env,'register:'+clientIp(request),7,3600)) return fail('Thử lại sau',429);
    const b=await bodyJson(request); const email=emailOf(b.email);
    if(!emailValid(email)||!passwordValid(b.password)||b.password!==b.confirm) return fail('Email hoặc mật khẩu không hợp lệ (ít nhất 14 ký tự)');
    if(!await turnstileOk(env,request,b.turnstile)) return fail('Xác thực chống bot không hợp lệ');
    if(env.REQUIRE_EMAIL_VERIFICATION==='1' && !mailReady(env)) return fail('Chưa cấu hình gửi email xác minh',503);
    const hashed=await hashPassword(b.password); const id=crypto.randomUUID();
    try {
      await env.DB.prepare(`INSERT INTO users(id,email,password_salt,password_hash,role,status,email_verified,created_at,updated_at)
        VALUES(?,?,?,?,'user','pending',0,?,?)`).bind(id,email,hashed.salt,hashed.hash,now(),now()).run();
    } catch(e) {if(String(e).includes('UNIQUE'))return fail('Email đã được đăng ký',409);throw e;}
    if(mailReady(env)) await issueVerifyEmail(env,{id,email});
    await audit(env,id,id,'register');
    return json({ok:true,message:'Đăng ký thành công. Tài khoản cần được quản trị viên duyệt.'},201);
  }

  if(method==='POST' && (path==='/api/login'||path==='/api/client/login')){
    const b=await bodyJson(request);const email=emailOf(b.email),password=b.password;
    if(!await rateLimit(env,'login-ip:'+clientIp(request),30,3600)||!await rateLimit(env,'login-user:'+await sha(email),12,3600))return fail('Thử lại sau',429);
    if(!emailValid(email)||typeof password!=='string'||password.length>128)return fail('Sai email hoặc mật khẩu',401);
    if(path==='/api/login' && !await turnstileOk(env,request,b.turnstile))return fail('Xác thực chống bot không hợp lệ',403);
    // The Windows client uses the separate API endpoint, protected by database-backed login rate limits.
    const user=await env.DB.prepare('SELECT * FROM users WHERE email=?').bind(email).first();
    if(!user||!await verifyPassword(password,user.password_salt,user.password_hash))return fail('Sai email hoặc mật khẩu',401);
    if(user.status==='suspended')return fail('Tài khoản bị tạm khóa',403);
    const token=await newSession(env,user);
    await audit(env,user.id,user.id,'login');
    if(path==='/api/client/login')return json({access_token:token,token_type:'Bearer',expires_in:SESSION_SECONDS,user:safeUser(user,env)});
    return json({user:safeUser(user,env)},200,{'set-cookie':sessionCookie(request,token)});
  }

  if(method==='POST' && path==='/api/email/verify'){
    const b=await bodyJson(request);if(typeof b.token!=='string'||b.token.length>200)return fail('Liên kết không hợp lệ');
    const row=await env.DB.prepare("SELECT * FROM action_tokens WHERE token_hash=? AND purpose='verify' AND expires_at>?").bind(await sha(b.token),now()).first();
    if(!row)return fail('Liên kết đã hết hạn hoặc không tồn tại',400);
    await env.DB.batch([
      env.DB.prepare('UPDATE users SET email_verified=1,updated_at=? WHERE id=?').bind(now(),row.user_id),
      env.DB.prepare('DELETE FROM action_tokens WHERE token_hash=?').bind(row.token_hash)
    ]);
    return json({ok:true,message:'Email đã xác minh.'});
  }

  if(method==='POST' && path==='/api/password/forgot'){
    if(!mailReady(env))return fail('Chức năng gửi email chưa được cấu hình',503);
    if(!await rateLimit(env,'forgot:'+clientIp(request),5,3600))return fail('Thử lại sau',429);
    const b=await bodyJson(request);const email=emailOf(b.email);
    const user=emailValid(email)?await env.DB.prepare('SELECT id,email FROM users WHERE email=?').bind(email).first():null;
    if(user){
      const token=await createActionToken(env,user.id,'reset',3600);
      await sendMail(env,email,'Đặt lại mật khẩu VNCode',`Đặt lại mật khẩu trong 1 giờ: ${env.PUBLIC_BASE_URL||'https://vncode.id.vn'}/?reset=${encodeURIComponent(token)}`);
    }
    return json({ok:true,message:'Nếu tài khoản tồn tại, email khôi phục sẽ được gửi.'});
  }
  if(method==='POST' && path==='/api/password/reset'){
    const b=await bodyJson(request);
    if(typeof b.token!=='string'||b.token.length>200||!passwordValid(b.password)||b.password!==b.confirm)return fail('Dữ liệu không hợp lệ');
    const row=await env.DB.prepare("SELECT * FROM action_tokens WHERE token_hash=? AND purpose='reset' AND expires_at>?").bind(await sha(b.token),now()).first();
    if(!row)return fail('Liên kết hết hạn hoặc không tồn tại');
    const hp=await hashPassword(b.password);
    await env.DB.batch([
      env.DB.prepare('UPDATE users SET password_salt=?,password_hash=?,updated_at=? WHERE id=?').bind(hp.salt,hp.hash,now(),row.user_id),
      env.DB.prepare('DELETE FROM sessions WHERE user_id=?').bind(row.user_id),
      env.DB.prepare('DELETE FROM action_tokens WHERE user_id=?').bind(row.user_id)
    ]);
    await audit(env,row.user_id,row.user_id,'password_reset');
    return json({ok:true,message:'Mật khẩu đã được đổi. Vui lòng đăng nhập lại.'});
  }

  const user=await auth(request,env);
  if(!user)return fail('Bạn chưa đăng nhập',401);
  if(method==='GET' && (path==='/api/me'||path==='/api/client/me'))return json({user:safeUser(user,env)});
  if(method==='GET' && path==='/api/license'){
    const history=(await env.DB.prepare(`SELECT c.days,c.code_last4,r.claimed_at FROM license_redemptions r
      JOIN license_codes c ON c.id=r.code_id WHERE r.user_id=? ORDER BY r.claimed_at DESC LIMIT 20`).bind(user.id).all()).results;
    return json({user:safeUser(user,env),history});
  }
  if(method==='POST' && path==='/api/license/redeem'){
    if(user.role==='admin')return fail('Tài khoản Admin không cần nhập mã license',403);
    if(user.status!=='approved')return fail('Tài khoản chưa được duyệt hoặc đang bị khóa',403);
    if(!await rateLimit(env,'license-redeem:'+user.id,10,3600))return fail('Bạn đã thử quá nhiều lần. Hãy thử sau.',429);
    const b=await bodyJson(request),code=normalizeLicenseCode(b.code);
    if(!code)return fail('Định dạng mã license không hợp lệ',400);
    const claim=crypto.randomUUID(),time=now(),digest=await sha(code);
    // Atomic: only an unused code assigned to this account can be consumed.
    // The unique code_id in license_redemptions prevents double redemption.
    // All statements in the D1 batch are one rollback-safe transaction.
    const result=await env.DB.batch([
      env.DB.prepare(`INSERT OR IGNORE INTO license_redemptions(id,code_id,user_id,claimed_at)
        SELECT ?,c.id,?,? FROM license_codes c JOIN users u ON u.id=?
        WHERE c.code_hash=? AND c.state='unused' AND c.assigned_user_id=?
          AND u.status='approved' AND u.role='user'`).bind(claim,user.id,time,user.id,digest,user.id),
      env.DB.prepare(`UPDATE users SET license_expires_at=MAX(COALESCE(license_expires_at,0),?)+(
          SELECT c.days*86400 FROM license_codes c JOIN license_redemptions r ON r.code_id=c.id WHERE r.id=?
        ),updated_at=? WHERE id=? AND EXISTS (
          SELECT 1 FROM license_redemptions WHERE id=? AND user_id=?)`).bind(time,claim,time,user.id,claim,user.id),
      env.DB.prepare(`UPDATE license_codes SET state='redeemed',redeemed_by=?,redeemed_at=?,updated_at=?
        WHERE id=(SELECT code_id FROM license_redemptions WHERE id=?) AND state='unused'`).bind(user.id,time,time,claim),
      env.DB.prepare(`INSERT INTO audit_log(id,actor_id,target_id,action,detail,created_at)
        SELECT ?,?,?, 'license_redeemed', c.code_last4, ?
        FROM license_redemptions r JOIN license_codes c ON c.id=r.code_id WHERE r.id=?`).bind(crypto.randomUUID(),user.id,user.id,time,claim)
    ]);
    if(Number(result[0]?.meta?.changes||0)!==1)return fail('Mã không hợp lệ, không thuộc tài khoản này hoặc đã được sử dụng',409);
    const current=await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(user.id).first();
    return json({ok:true,message:'Kích hoạt license thành công',user:safeUser(current,env)});
  }
  if(method==='POST' && path==='/api/logout'){
    const token=(request.headers.get('authorization')||'').startsWith('Bearer ')?request.headers.get('authorization').slice(7).trim():getCookie(request,cookieName(request));
    await env.DB.prepare('DELETE FROM sessions WHERE token_hash=?').bind(await sha(token)).run();
    return json({ok:true},200,{'set-cookie':sessionCookie(request,'',0)});
  }
  if(method==='POST' && path==='/api/email/resend'){
    if(!mailReady(env))return fail('Chưa bật gửi email',503);
    if(!await rateLimit(env,'resend:'+user.id,3,3600))return fail('Thử lại sau',429);
    if(user.email_verified)return json({ok:true,message:'Email đã xác minh.'});
    const sent=await issueVerifyEmail(env,user);
    return sent?json({ok:true,message:'Đã gửi liên kết xác minh.'}):fail('Không gửi được email',503);
  }

  if(user.role!=='admin')return fail('Bạn không có quyền quản trị',403);
  const cleaned=await adminCleanup(request,env,{bodyJson},user);
  if(cleaned)return cleaned;
  if(method==='GET' && path==='/api/admin/users'){
    const limit=50; const page=Math.min(1000,Math.max(0,Number(url.searchParams.get('page')||0)||0));
    const rows=(await env.DB.prepare('SELECT id,email,role,status,email_verified,license_expires_at,created_at FROM users ORDER BY created_at DESC LIMIT ? OFFSET ?').bind(limit,page*limit).all()).results;
    const total=await env.DB.prepare('SELECT COUNT(*) as n FROM users').first();
    return json({users:rows.map(row=>safeUser(row,env)),total:total?.n||0,page});
  }
  if(method==='GET' && path==='/api/admin/audit'){
    const rows=(await env.DB.prepare('SELECT id,actor_id,target_id,action,detail,created_at FROM audit_log ORDER BY created_at DESC LIMIT 40').all()).results;
    return json({events:rows});
  }
  if(method==='GET' && path==='/api/admin/license-codes'){
    const page=Math.min(1000,Math.max(0,Number(url.searchParams.get('page')||0)||0));
    const state=url.searchParams.get('state')||'';
    if(state&&!['unused','redeemed','void'].includes(state))return fail('Trạng thái license không hợp lệ',400);
    const rows=(await env.DB.prepare(`SELECT c.id,c.code_last4,c.days,c.state,c.created_at,c.redeemed_at,
      u.email AS assigned_email,r.email AS redeemed_email FROM license_codes c
      JOIN users u ON u.id=c.assigned_user_id LEFT JOIN users r ON r.id=c.redeemed_by
      WHERE (?='' OR c.state=?) ORDER BY c.created_at DESC,c.id DESC LIMIT 50 OFFSET ?`).bind(state,state,page*50).all()).results;
    const count=await env.DB.prepare("SELECT COUNT(*) AS n FROM license_codes WHERE (?='' OR state=?)").bind(state,state).first();
    return json({codes:rows,total:count?.n||0,page});
  }
  if(method==='POST' && path==='/api/admin/license-codes'){
    if(!await rateLimit(env,'license-issue:'+user.id,100,3600))return fail('Đã vượt giới hạn tạo mã',429);
    const b=await bodyJson(request),days=b.days,assignedId=b.user_id;
    if(!Number.isInteger(days)||days<1||days>3650)return fail('Số ngày phải từ 1–3650',400);
    if(typeof assignedId!=='string'||! /^[a-f0-9-]{36}$/.test(assignedId))return fail('Tài khoản nhận không hợp lệ',400);
    const target=await env.DB.prepare('SELECT id,role,status FROM users WHERE id=?').bind(assignedId).first();
    if(!target||!['user','admin'].includes(target.role))return fail('Không tìm thấy tài khoản nhận license',400);
    if(target.status!=='approved')return fail('Hãy phê duyệt tài khoản trước khi cấp mã',403);
    const code=newLicenseCode(),time=now();
    await env.DB.batch([
      env.DB.prepare(`INSERT INTO license_codes(id,code_hash,code_last4,days,assigned_user_id,
        created_by,state,created_at,updated_at) VALUES(?,?,?,?,?,?,'unused',?,?)`)
        .bind(crypto.randomUUID(),await sha(code),code.slice(-4),days,assignedId,user.id,time,time),
      env.DB.prepare(`INSERT INTO audit_log(id,actor_id,target_id,action,detail,created_at)
        VALUES(?,?,?,'license_code_issued',?,?)`).bind(crypto.randomUUID(),user.id,assignedId,`${days} days`,time)
    ]);
    return json({ok:true,code,message:'Mã chỉ được hiển thị một lần. Hãy chuyển riêng cho đúng khách hàng.'},201);
  }
  const voidCode=path.match(/^\/api\/admin\/license-codes\/([a-f0-9-]{36})\/void$/);
  if(method==='POST' && voidCode){
    const id=voidCode[1];
    const result=await env.DB.prepare("UPDATE license_codes SET state='void',updated_at=? WHERE id=? AND state='unused'").bind(now(),id).run();
    if(Number(result.meta?.changes||0)!==1)return fail('Mã không tồn tại hoặc đã được sử dụng/hủy',409);
    await audit(env,user.id,id,'license_code_void');
    return json({ok:true});
  }
  const revoke=path.match(/^\/api\/admin\/users\/([a-f0-9-]{36})\/license\/revoke$/);
  if(method==='POST' && revoke){
    const id=revoke[1];
    const target=await env.DB.prepare('SELECT id,role FROM users WHERE id=?').bind(id).first();
    if(!target)return fail('Không tìm thấy tài khoản',404);
    if(target.role==='admin')return fail('Không thể thu hồi quyền Admin qua chức năng này',403);
    await env.DB.batch([
      env.DB.prepare('UPDATE users SET license_expires_at=NULL,updated_at=? WHERE id=?').bind(now(),id),
      env.DB.prepare("UPDATE license_codes SET state='void',updated_at=? WHERE assigned_user_id=? AND state='unused'").bind(now(),id),
      env.DB.prepare('DELETE FROM sessions WHERE user_id=?').bind(id),
      env.DB.prepare('UPDATE license_devices SET revoked=1 WHERE user_id=?').bind(id),
      env.DB.prepare('UPDATE license_policy SET revision=revision+1 WHERE user_id=?').bind(id),
      env.DB.prepare("INSERT INTO audit_log(id,actor_id,target_id,action,detail,created_at) VALUES(?,?,?,'license_revoked','unused codes voided',?)").bind(crypto.randomUUID(),user.id,id,now())
    ]);
    return json({ok:true});
  }
  const m=path.match(/^\/api\/admin\/users\/([a-f0-9-]{36})\/(status|grant|verify)$/);
  if(method==='POST' && m){
    const id=m[1],action=m[2], b=await bodyJson(request);
    const target=await env.DB.prepare('SELECT * FROM users WHERE id=?').bind(id).first();
    if(!target)return fail('Không tìm thấy tài khoản',404);
    if(action==='status'){
      if(!['pending','approved','suspended'].includes(b.status))return fail('Trạng thái không hợp lệ');
      if(target.role==='admin' || target.id===user.id)return fail('Không thể thay đổi trạng thái Admin',403);
      await env.DB.prepare('UPDATE users SET status=?,updated_at=? WHERE id=?').bind(b.status,now(),id).run();
      if(b.status==='suspended')await env.DB.batch([env.DB.prepare('DELETE FROM sessions WHERE user_id=?').bind(id),env.DB.prepare('DELETE FROM desktop_sessions WHERE device_id IN (SELECT id FROM license_devices WHERE user_id=?)').bind(id)]);
      await audit(env,user.id,id,'change_status',b.status);
      return json({ok:true});
    }
    if(action==='grant'){
      const days=Number(b.days);
      if(!Number.isInteger(days)||days<1||days>3650)return fail('Số ngày từ 1 đến 3650');
      const expires=Math.max(now(),Number(target.license_expires_at||0))+days*86400;
      await env.DB.prepare('UPDATE users SET license_expires_at=?,updated_at=? WHERE id=?').bind(expires,now(),id).run();
      await audit(env,user.id,id,'grant_days',String(days));
      return json({ok:true,expires_at:expires});
    }
    if(action==='verify'){
      await env.DB.prepare('UPDATE users SET email_verified=1,updated_at=? WHERE id=?').bind(now(),id).run();
      await audit(env,user.id,id,'manual_verify_email');
      return json({ok:true});
    }
  }
  return fail('Đường dẫn không tồn tại',404);
}

export default {
  async fetch(request,env){
    const path=new URL(request.url).pathname;
    if(!path.startsWith('/api/'))return env.ASSETS.fetch(request);
    try {return await api(request,env);} catch(e){if(e.message==='INVALID_JSON')return fail('Dữ liệu JSON không hợp lệ',400);console.error('VNCode API error');return fail('Lỗi máy chủ. Vui lòng thử lại.',500);}
  },
  async scheduled(_event,env,ctx){
    const cleanup=cleanupExpired(env).catch(()=>{console.error('VNCode expired credential maintenance failed');});
    ctx.waitUntil(cleanup);
    await cleanup;
  }
};
export {hashPassword,verifyPassword,entitlement,constantTimeEqual,ITERATIONS};
