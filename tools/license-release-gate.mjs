import assert from 'node:assert/strict';

// A preview must not make a licensing claim from local fixture tests alone.
export function validateLicenseReadiness(status,keys,evidence,version,sha) {
 assert.equal(status.licenseIntegration?.implemented,true,'License implementation is incomplete');
 assert.equal(status.licenseIntegration?.productionReady,true,'License production acceptance is outstanding');
 assert.ok(keys && typeof keys==='object' && !Array.isArray(keys));
 const ids=Object.keys(keys);assert.ok(ids.length>0,'A verified production public key must be pinned');
 for(const id of ids){assert.match(id,/^[A-Za-z0-9_-]{1,64}$/);assert.ok(!/test|fixture/i.test(id),'Fixture key cannot be published');assert.match(keys[id],/^[A-Za-z0-9_-]{43}$/);const raw=Buffer.from(keys[id],'base64url');assert.equal(raw.length,32);assert.equal(raw.toString('base64url'),keys[id]);}
 assert.match(sha,/^[a-f0-9]{40}$/);assert.equal(evidence.sourceCommit,sha,'License evidence must match the exact source');
 assert.equal(evidence.version,version);assert.equal(evidence.result,'passed');
 for(const field of ['windowsDpapiPassed','foreignWindowsIdentityRejected','backendGmailApprovalPassed','deviceLimitConcurrencyPassed','activationRetryPassed','signedOfflinePassed','revocationPassed','readonlyDataPreserved'])assert.equal(evidence[field],true,field);
 assert.ok(ids.includes(evidence.keyId));assert.equal(evidence.publicKey,keys[evidence.keyId],'Server pin must match the client pin');
 assert.match(evidence.backendSourceSha256,/^[a-f0-9]{64}$/);
}
