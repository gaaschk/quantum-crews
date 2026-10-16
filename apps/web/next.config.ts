import path from "node:path";
import type { NextConfig } from "next";
const config: NextConfig = { poweredByHeader: false, output: "standalone", outputFileTracingRoot: path.join(process.cwd(), "../.."), async headers(){return [{source:"/:path*",headers:[{key:"X-Content-Type-Options",value:"nosniff"},{key:"X-Frame-Options",value:"DENY"},{key:"Referrer-Policy",value:"no-referrer"},{key:"Content-Security-Policy",value:"frame-ancestors 'none'; base-uri 'self'; form-action 'self'"}]}];}, async rewrites(){return [{source:"/api/:path*",destination:`${process.env.API_BASE_URL ?? "http://127.0.0.1:8100"}/api/:path*`}];} };
export default config;
