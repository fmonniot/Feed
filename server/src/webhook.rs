//! Webhook delivery system for sending notifications to registered endpoints.

use anyhow::Result;
use chrono::Utc;
use hmac::{Hmac, Mac};
use sha2::Sha256;
use tracing::{error, info};

use crate::api::{WebhookPayload, WebhookData, NewArticleEvent, FeedErrorEvent};
use crate::db::{Database, Webhook};

type HmacSha256 = Hmac<Sha256>;

/// Webhook dispatcher for sending events to registered endpoints.
pub struct WebhookDispatcher {
    client: reqwest::Client,
}

impl WebhookDispatcher {
    pub fn new() -> Result<Self, reqwest::Error> {
        let client = reqwest::Client::builder()
            .user_agent("RSSAggregator-Webhook/1.0")
            .timeout(std::time::Duration::from_secs(10))
            .build()?;
        Ok(WebhookDispatcher { client })
    }

    /// Send a new article event to all webhooks subscribed to "new_article".
    pub async fn notify_new_article(
        &self,
        db: &Database,
        article_id: i64,
        feed_id: i64,
        feed_title: Option<String>,
        title: Option<String>,
        link: Option<String>,
        author: Option<String>,
        published: Option<i64>,
    ) {
        let webhooks = match db.get_webhooks_for_event("new_article").await {
            Ok(w) => w,
            Err(e) => {
                error!("Failed to get webhooks for new_article event: {}", e);
                return;
            }
        };

        if webhooks.is_empty() {
            return;
        }

        let payload = WebhookPayload {
            event: "new_article".to_string(),
            timestamp: Utc::now().timestamp(),
            data: WebhookData::NewArticle(NewArticleEvent {
                article_id,
                feed_id,
                feed_title,
                title,
                link,
                author,
                published,
            }),
        };

        for webhook in webhooks {
            self.send_webhook(&webhook, &payload).await;
        }
    }

    /// Send a feed error event to all webhooks subscribed to "feed_error".
    pub async fn notify_feed_error(
        &self,
        db: &Database,
        feed_id: i64,
        feed_url: String,
        feed_title: Option<String>,
        error: String,
        error_count: i64,
    ) {
        let webhooks = match db.get_webhooks_for_event("feed_error").await {
            Ok(w) => w,
            Err(e) => {
                error!("Failed to get webhooks for feed_error event: {}", e);
                return;
            }
        };

        if webhooks.is_empty() {
            return;
        }

        let payload = WebhookPayload {
            event: "feed_error".to_string(),
            timestamp: Utc::now().timestamp(),
            data: WebhookData::FeedError(FeedErrorEvent {
                feed_id,
                feed_url,
                feed_title,
                error,
                error_count,
            }),
        };

        for webhook in webhooks {
            self.send_webhook(&webhook, &payload).await;
        }
    }

    /// Send a webhook payload to a single endpoint.
    async fn send_webhook(&self, webhook: &Webhook, payload: &WebhookPayload) {
        let body = match serde_json::to_string(payload) {
            Ok(b) => b,
            Err(e) => {
                error!("Failed to serialize webhook payload: {}", e);
                return;
            }
        };

        let mut request = self.client
            .post(&webhook.url)
            .header("Content-Type", "application/json")
            .header("X-Webhook-Event", &payload.event);

        // Add HMAC-SHA256 signature if secret is configured
        if let Some(ref secret) = webhook.secret {
            if let Ok(mut mac) = HmacSha256::new_from_slice(secret.as_bytes()) {
                mac.update(body.as_bytes());
                let signature = hex::encode(mac.finalize().into_bytes());
                request = request.header("X-Webhook-Signature", format!("sha256={}", signature));
            }
        }

        match request.body(body).send().await {
            Ok(response) => {
                if response.status().is_success() {
                    info!(
                        "Webhook delivered successfully: {} -> {} ({})",
                        payload.event,
                        webhook.url,
                        response.status()
                    );
                } else {
                    error!(
                        "Webhook delivery failed: {} -> {} ({})",
                        payload.event,
                        webhook.url,
                        response.status()
                    );
                }
            }
            Err(e) => {
                error!(
                    "Webhook delivery error: {} -> {} - {}",
                    payload.event,
                    webhook.url,
                    e
                );
            }
        }
    }
}
