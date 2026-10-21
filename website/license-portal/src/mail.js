// Resend supports delivery to customer Gmail after sender-domain verification.
export const mailReady = env => !!env.RESEND_API_KEY || !!env.EMAIL;
export async function sendMail(env,to,subject,text) {
 try {
  if(env.RESEND_API_KEY) {
   const response=await fetch('https://api.resend.com/emails',{method:'POST',redirect:'error',signal:AbortSignal.timeout(10000),headers:{authorization:'Bearer '+env.RESEND_API_KEY,'content-type':'application/json'},body:JSON.stringify({from:env.EMAIL_FROM||'no-reply@vncode.id.vn',to:[to],subject,text})});
   // Never log provider bodies, addresses, OTPs or license keys.
   await response.body?.cancel();return response.ok;
  }
  // Preserve the supplied project's existing email adapter. Cloudflare binding
  // compatibility/destination permissions must be checked before enabling it.
  if(env.EMAIL){await env.EMAIL.send({from:env.EMAIL_FROM||'no-reply@vncode.id.vn',to,subject,text});return true;}
 }catch {console.error('Mail delivery failed');}
 return false;
}
