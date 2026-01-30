//! API module for the RSS aggregator server.
//! 
//! This module contains all HTTP handlers, error types, and request/response types.

mod error;
mod handlers;
mod types;

pub use handlers::*;
// Re-exported for tests
#[allow(unused_imports)]
pub use types::{AuthUser, LogQuery};
// Re-exported for webhook module
pub use types::{WebhookPayload, WebhookData, NewArticleEvent, FeedErrorEvent};
// Re-exported for tests
#[allow(unused_imports)]
pub use types::Claims;
