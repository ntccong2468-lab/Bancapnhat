// A separate Cloudflare route; all rights and state remain in the existing Portal.
const routes = new Map([
 ['GET',new Set(['/api/config','/api/client/registration/status'])],
 ['POST',new Set(['/api/client/registration/register','/api/client/registration/license',
  '/api/client/registration/verify','/api/client/license/endpoint-proof',
  '/api/client/license/challenge','/api/client/license/activate','/api/client/license/renew'])]
]);
export default {
 async fetch(request,env) {
  const url=new URL(request.url);
  if(!routes.get(request.method)?.has(url.pathname)||url.search||url.username||url.password)
   return Response.json({error:'NOT_FOUND'},{status:404,headers:{'cache-control':'no-store'}});
  if(!env.BACKEND?.fetch)return Response.json({error:'LICENSE_BACKEND_UNAVAILABLE'},{status:503});
  const headers=new Headers(request.headers);
  headers.delete('cookie');headers.delete('x-forwarded-for');headers.delete('forwarded');
  // Service binding keeps the actual backup URL for the issuer's signed origin.
  // CF-Connecting-IP is supplied by Cloudflare, not by a caller's forwarded headers.
  return env.BACKEND.fetch(new Request(request,{headers,redirect:'manual'}));
 }
};
