// Run on the owner's trusted machine. Never include the output directory in a ZIP/repository.
import {generateKeyPairSync} from 'node:crypto';
import {mkdirSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
const directory=process.argv[2];if(!directory)throw new Error('Provide a NEW directory outside the project: node tools/create-signing-key.mjs <directory>');
const destination=resolve(directory);mkdirSync(destination,{mode:0o700});
const {publicKey,privateKey}=generateKeyPairSync('ed25519');
writeFileSync(resolve(destination,'LICENSE_SIGNING_KEY.txt'),privateKey.export({type:'pkcs8',format:'der'}).toString('base64url'),{mode:0o600,flag:'wx'});
writeFileSync(resolve(destination,'LICENSE_PUBLIC_KEY.txt'),publicKey.export({type:'spki',format:'der'}).subarray(-32).toString('base64url'),{mode:0o644,flag:'wx'});
console.log('Created key files in the chosen directory. Keep the private file secret; embed only the public key in VNCode.');
