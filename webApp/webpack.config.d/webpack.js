config.resolve.modules.push("kotlin");
// Fonts and images imported from CSS (the Lucide icon font) become assets.
config.module.rules.push({ test: /\.(woff2?|ttf|eot)(\?.*)?$/, type: "asset/resource" });
config.module.rules.push({ test: /\.(jpe?g|png|gif|svg)$/i, type: "asset/resource" });
config.performance = { assetFilter: (file) => !file.endsWith(".js") && !file.endsWith(".wasm") };
