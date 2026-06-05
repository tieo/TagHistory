// sql.js (pulled in transitively by SqlDelight's web-worker-driver via
// @cashapp/sqldelight-sqljs-worker) does a node-style `require('crypto')`
// for emscripten's nondeterministic byte source. The browser bundle does
// not need it — webpack 5 dropped its automatic node polyfill so the
// import has to be stubbed out explicitly.
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, {
    crypto: false,
    fs: false,
    path: false,
});
