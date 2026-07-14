// Silence webpack's asset/entrypoint size-limit hints.
//
// Kotlin/JS emits a single large bundle (the stdlib, coroutines, ktor and our
// own code all compile into one entrypoint), so it always exceeds webpack's
// default 244 KiB recommendation and prints two "size limit" warnings on every
// production build. The threshold is tuned for hand-written JS apps and isn't
// actionable for a Kotlin/JS output short of code-splitting the runtime, so we
// disable the hint rather than let a non-actionable warning stay in the build
// output. Bundle size is instead tracked via the content-hashed CDN caching in
// cache-busting.js.
config.performance = config.performance || {};
config.performance.hints = false;
