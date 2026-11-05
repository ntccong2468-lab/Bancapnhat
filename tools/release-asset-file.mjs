import assert from 'node:assert/strict';
import {constants} from 'node:fs';
import {open} from 'node:fs/promises';

// Check and read the same open file; never re-open a path after checking it.
// Publishers extract into a private directory and allowlist every asset name.
export async function readRegularAsset(file){
  const handle=await open(file,constants.O_RDONLY|constants.O_NOFOLLOW);
  try{assert.ok((await handle.stat()).isFile(),'Release asset must be a regular file');return await handle.readFile();}
  finally{await handle.close();}
}
