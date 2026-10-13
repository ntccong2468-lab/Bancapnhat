// Only expired ephemeral credentials are removed. Users, policies, devices and audit
// records remain available for administration and entitlement reconciliation.
const TABLES=Object.freeze([
 ['sessions','token_hash'],['action_tokens','token_hash'],['rate_limits','key'],
 ['desktop_registrations','id'],['desktop_sessions','token_hash'],['desktop_challenges','id']
]);
export const CLEANUP_ROWS_PER_TABLE=500;

export async function cleanupExpired(env,{at=Math.floor(Date.now()/1000),limit=CLEANUP_ROWS_PER_TABLE}={}) {
 if(!Number.isSafeInteger(at)||at<0||!Number.isInteger(limit)||limit<1||limit>1000)throw new Error('INVALID_MAINTENANCE_BOUNDS');
 const operations=TABLES.map(([table,key])=>env.DB.prepare(
  `DELETE FROM ${table} WHERE ${key} IN (SELECT ${key} FROM ${table} WHERE expires_at<=? ORDER BY expires_at LIMIT ?)`
 ).bind(at,limit));
 const results=await env.DB.batch(operations);
 return Object.freeze({tables:TABLES.length,deleted:results.reduce((count,result)=>count+Number(result.meta?.changes||0),0)});
}
