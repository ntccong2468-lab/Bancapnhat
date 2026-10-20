// Explicit administrative deletion. Related credentials are removed in one D1 batch.
const reply = (data,status=200) => new Response(JSON.stringify(data),{status,headers:{'content-type':'application/json; charset=utf-8','cache-control':'no-store'}});
const fail = (error,status=400) => reply({error},status);
const now = () => Math.floor(Date.now()/1000);
const sha = async value => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value))),b=>b.toString(16).padStart(2,'0')).join('');

export async function adminCleanup(request,env,{bodyJson},admin) {
 const path=new URL(request.url).pathname;
 const codeRoute=path.match(/^\/api\/admin\/license-codes\/([a-f0-9-]{36})\/delete$/);
 const userRoute=path.match(/^\/api\/admin\/users\/([a-f0-9-]{36})\/delete$/);
 if(request.method!=='POST'||!codeRoute&&!userRoute)return null;
 if(admin?.role!=='admin')return fail('ADMIN_REQUIRED',403);
 const body=await bodyJson(request),time=now();
 if(codeRoute) {
  if(body.confirm!==true)return fail('DELETE_CONFIRMATION_REQUIRED');
  const id=codeRoute[1];
  const eligible="id=? AND state='void' AND NOT EXISTS(SELECT 1 FROM license_redemptions WHERE code_id=license_codes.id)";
  const results=await env.DB.batch([
   env.DB.prepare(`INSERT INTO audit_log(id,actor_id,target_id,action,detail,created_at)
    SELECT ?,?,id,'license_code_deleted','void license',? FROM license_codes WHERE ${eligible}`).bind(crypto.randomUUID(),admin.id,time,id),
   env.DB.prepare(`DELETE FROM license_codes WHERE ${eligible}`).bind(id)
  ]);
  if(results[1]?.meta?.changes!==1)return fail('ONLY_VOID_LICENSE_CAN_BE_DELETED',409);
  return reply({ok:true});
 }
 const id=userRoute[1],user=await env.DB.prepare('SELECT id,email,role FROM users WHERE id=?').bind(id).first();
 if(!user)return fail('ACCOUNT_NOT_FOUND',404);
 if(user.role!=='user'||id===admin.id)return fail('ADMIN_ACCOUNT_CANNOT_BE_DELETED',403);
 if(typeof body.confirm_email!=='string'||body.confirm_email.trim().toLowerCase()!==user.email.toLowerCase())return fail('EMAIL_CONFIRMATION_REQUIRED');
 // Historical administrators may own codes assigned to other customers. Do not
 // erase another customer's license to satisfy a foreign-key reference.
 const foreign=await env.DB.prepare('SELECT id FROM license_codes WHERE (created_by=? OR redeemed_by=?) AND assigned_user_id<>? LIMIT 1').bind(id,id,id).first();
 if(foreign)return fail('ACCOUNT_HAS_FOREIGN_LICENSE_HISTORY',409);
 const offline=await env.DB.prepare('SELECT MAX(last_lease_expires_at) AS expiry FROM license_devices WHERE user_id=?').bind(id).first();
 const emailHash=await sha(user.email.toLowerCase());
 const guard="EXISTS(SELECT 1 FROM users WHERE id=? AND role='user' AND email=?)";
 const guarded=(sql,...values)=>env.DB.prepare(sql+` AND ${guard}`).bind(...values,id,user.email);
 const results=await env.DB.batch([
  // The predicates are rechecked inside the transaction, including after an
  // overlapping registration or activation. The final user deletion is last.
  guarded(`DELETE FROM desktop_challenges WHERE (credential_hash IN (SELECT token_hash FROM desktop_registrations WHERE user_id=? OR email=?) OR credential_hash IN (SELECT s.token_hash FROM desktop_sessions s JOIN license_devices d ON d.id=s.device_id WHERE d.user_id=?))`,id,user.email,id),
  guarded(`DELETE FROM rate_limits WHERE (key=? OR key=? OR key IN (SELECT 'desktop-challenge:'||token_hash FROM desktop_registrations WHERE user_id=? OR email=?) OR key IN (SELECT 'desktop-license:'||token_hash FROM desktop_registrations WHERE user_id=? OR email=?) OR key IN (SELECT 'desktop-challenge:'||s.token_hash FROM desktop_sessions s JOIN license_devices d ON d.id=s.device_id WHERE d.user_id=?) OR key IN (SELECT 'desktop-license:'||s.token_hash FROM desktop_sessions s JOIN license_devices d ON d.id=s.device_id WHERE d.user_id=?) OR key IN (SELECT 'desktop-code:'||code_hash FROM license_codes WHERE assigned_user_id=?))`,'desktop-register-email:'+emailHash,'license-redeem:'+id,id,user.email,id,user.email,id,id,id),
  guarded('DELETE FROM desktop_sessions WHERE device_id IN (SELECT id FROM license_devices WHERE user_id=?)',id),
  guarded('DELETE FROM desktop_registrations WHERE (user_id=? OR email=?)',id,user.email),
  guarded('DELETE FROM license_devices WHERE user_id=?',id),
  guarded('DELETE FROM license_policy WHERE user_id=?',id),
  guarded('DELETE FROM license_redemptions WHERE user_id=?',id),
  guarded('DELETE FROM license_codes WHERE assigned_user_id=?',id),
  guarded('DELETE FROM sessions WHERE user_id=?',id),
  guarded('DELETE FROM action_tokens WHERE user_id=?',id),
  env.DB.prepare(`INSERT INTO audit_log(id,actor_id,target_id,action,detail,created_at) SELECT ?,?,id,'account_deleted','account and related licenses removed',? FROM users WHERE id=? AND role='user' AND email=?`).bind(crypto.randomUUID(),admin.id,time,id,user.email),
  env.DB.prepare("DELETE FROM users WHERE id=? AND role='user' AND email=?").bind(id,user.email)
 ]);
 if(results.at(-1)?.meta?.changes!==1)return fail('ACCOUNT_CHANGED',409);
 return reply({ok:true,registration_reset:true,last_offline_expires_at:offline?.expiry||null});
}
