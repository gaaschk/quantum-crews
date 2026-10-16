import path from "node:path";
import type { NextConfig } from "next";
const config: NextConfig = { poweredByHeader: false, output: "standalone", outputFileTracingRoot: path.join(process.cwd(), "../.."), async rewrites(){return [{source:"/api/:path*",destination:`${process.env.API_BASE_URL ?? "http://127.0.0.1:8100"}/api/:path*`}];} };
export default config;
