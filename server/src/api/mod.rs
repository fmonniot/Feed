//! API module for the RSS aggregator server.
//!
//! This module contains all HTTP handlers, error types, and request/response types.

mod error;
mod handlers;
mod types;

pub use handlers::*;
// Re-exported for tests
#[allow(unused_imports)]
pub use types::AuthUser;
// Re-exported for webhook module
pub use types::{FeedErrorEvent, NewArticleEvent, WebhookData, WebhookPayload};
// Re-exported for tests
#[allow(unused_imports)]
pub use types::Claims;
// Re-exported for tests
#[allow(unused_imports)]
pub use types::RetentionResponse;
