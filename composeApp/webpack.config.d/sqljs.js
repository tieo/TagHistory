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

// The database worker loads sql.js's engine from /sql-wasm.wasm. Emit it from
// the installed sql.js package so the bundle always carries the engine that
// matches the JS glue; without it the server's index.html fallback answers
// the request and the worker fails to compile HTML as wasm.
config.plugins = config.plugins || [];
config.plugins.push({
    apply(compiler) {
        const { Compilation, sources } = compiler.webpack;
        compiler.hooks.thisCompilation.tap("EmitSqlJsWasm", (compilation) => {
            compilation.hooks.processAssets.tap(
                { name: "EmitSqlJsWasm", stage: Compilation.PROCESS_ASSETS_STAGE_ADDITIONAL },
                () => {
                    const file = require.resolve("sql.js/dist/sql-wasm.wasm");
                    compilation.emitAsset("sql-wasm.wasm", new sources.RawSource(require("fs").readFileSync(file)));
                },
            );
        });
    },
});
