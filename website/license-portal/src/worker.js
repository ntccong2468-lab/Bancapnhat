// Keep test-only exports out of the Cloudflare Worker entrypoint.
import worker from './index.js';
export default worker;
