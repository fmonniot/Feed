// Content-hash the production JS bundle and its code-split chunks.
//
// The site is served behind a CDN (Cloudflare). With a fixed `feed-web.js` name,
// a new deploy reuses the same URL and the CDN/browser keeps serving the stale
// bundle (old logo, old UI, old CLIENT_VERSION). Content-hashed filenames change
// on every content change, so each deploy yields new immutable URLs that the CDN
// fetches fresh, while old ones can stay cached harmlessly.
//
// JS must be hashed by webpack (not in a post-build step) because the entry bundle
// references its split chunks by name internally — webpack rewrites those refs to
// match the hashed chunk filenames. CSS and the index.html reference are handled by
// the Gradle `fingerprintWebDistribution` task.
//
// The dev server keeps the plain `feed-web.js` name (mode !== "production") so
// live-reload and the dev proxy stay predictable.
if (config.mode === "production") {
    config.output = config.output || {};
    config.output.filename = "feed-web.[contenthash].js";
    config.output.chunkFilename = "[name].[contenthash].js";
}
