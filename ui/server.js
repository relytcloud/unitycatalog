// Standalone Unity Catalog UI server.
//
// Serves the CRA production build (`yarn build` -> ./build) and proxies the
// Unity Catalog REST API, injecting a server-side bearer token so the browser
// needs no OAuth/IdP login. This is the built-bundle counterpart of the
// dev-only src/setupProxy.js (same token-injection trick, but for a real
// process that can run outside `react-scripts start`).
//
// Used two ways:
//   - local "binary package" mode:  `yarn build` then `node server.js`
//   - allinone one-container mode:   started as the 2nd process next to the
//     UC server by bin/start-uc-with-ui.sh (UC_ENABLE_UI=true)
//
// Env:
//   PORT           listen port                          (default 3000)
//   HOST           bind address                         (default 0.0.0.0)
//   UC_TARGET      Unity Catalog server base URL        (default http://localhost:8088)
//   UC_TOKEN_FILE  path to the UC admin token file (bearer injected into /api/*) — required
const path = require('path');
const fs = require('fs');
const express = require('express');
const { createProxyMiddleware } = require('http-proxy-middleware');

const PORT = process.env.PORT || 3000;
const HOST = process.env.HOST || '0.0.0.0';
const UC_TARGET = process.env.UC_TARGET || 'http://localhost:8088';
const TOKEN_FILE = process.env.UC_TOKEN_FILE;
const BUILD_DIR = path.join(__dirname, 'build');

function die(msg) {
  console.error(`[uc-ui] ${msg}`);
  process.exit(1);
}

if (!TOKEN_FILE) {
  die('UC_TOKEN_FILE is required (path to the UC admin token).');
}
if (!fs.existsSync(path.join(BUILD_DIR, 'index.html'))) {
  die(`UI build not found at ${BUILD_DIR}. Run "yarn build" first.`);
}
let token;
try {
  token = fs.readFileSync(TOKEN_FILE, 'utf8').trim();
} catch (err) {
  die(`cannot read UC_TOKEN_FILE (${TOKEN_FILE}): ${err.message}`);
}
if (!token) {
  die(`UC_TOKEN_FILE (${TOKEN_FILE}) is empty.`);
}

const app = express();

// Proxy the UC REST API first (before static/fallback), injecting the bearer
// token. No body parser runs ahead of this, so POST/PATCH bodies stream through
// untouched.
app.use(
  ['/api/1.0', '/api/2.1'],
  createProxyMiddleware({
    target: UC_TARGET,
    changeOrigin: true,
    onProxyReq: (proxyReq) => {
      proxyReq.setHeader('Authorization', 'Bearer ' + token);
    },
  }),
);

// Static assets, then SPA fallback to index.html for client-side routes.
app.use(express.static(BUILD_DIR));
app.get('*', (_req, res) => {
  res.sendFile(path.join(BUILD_DIR, 'index.html'));
});

app.listen(PORT, HOST, () => {
  console.log(
    `[uc-ui] serving ${BUILD_DIR} on http://${HOST}:${PORT} -> API ${UC_TARGET}`,
  );
});
