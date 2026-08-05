import type { NextConfig } from "next";

// NEXT_API_PROXY_DESTINATION is a server-side-only env var (no NEXT_PUBLIC_ prefix).
// Set it to the backend base URL in production Vercel environment variables:
//   NEXT_API_PROXY_DESTINATION=https://api.briefy.store
//
// When set, all /api/* requests from the browser are transparently proxied through
// the Vercel edge to the backend. From the browser's perspective every cookie is
// first-party (briefy-psi.vercel.app), which eliminates Safari ITP cross-site
// cookie blocking for both the OAuth state cookie and the JWT auth cookie.
//
// In local development leave NEXT_API_PROXY_DESTINATION unset; the existing
// NEXT_PUBLIC_API_BASE_URL=http://localhost:8080 continues to work as-is because
// the login buttons and api.ts use absolute URLs that bypass the rewrite entirely.
const PROXY_DESTINATION = process.env.NEXT_API_PROXY_DESTINATION;

const nextConfig: NextConfig = {
  output: "standalone",
  ...(PROXY_DESTINATION && {
    async rewrites() {
      return [
        {
          source: "/api/:path*",
          destination: `${PROXY_DESTINATION}/api/:path*`,
        },
      ];
    },
  }),
};

export default nextConfig;
