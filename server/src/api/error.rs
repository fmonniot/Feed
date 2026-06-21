//! API error types for the RSS aggregator server.

use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde::Serialize;
use tracing::error;

/// Unified error type for API handlers with structured error responses.
#[derive(Debug)]
pub enum ApiError {
    /// Authentication failed (invalid credentials or token)
    Unauthorized(String),
    /// Resource not found
    NotFound(String),
    /// Invalid request parameters
    BadRequest(String),
    /// Resource conflict (e.g. duplicate unique value)
    Conflict(String),
    /// Too many requests (rate limited)
    TooManyRequests(String),
    /// Database operation failed
    Database(sqlx::Error),
    /// Internal server error
    Internal(String),
}

/// Structured error response returned to clients.
#[derive(Serialize)]
pub struct ErrorResponse {
    pub error: String,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub details: Option<String>,
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let (status, error_response) = match self {
            ApiError::Unauthorized(msg) => (
                StatusCode::UNAUTHORIZED,
                ErrorResponse {
                    error: "unauthorized".to_string(),
                    message: msg,
                    details: None,
                },
            ),
            ApiError::NotFound(msg) => (
                StatusCode::NOT_FOUND,
                ErrorResponse {
                    error: "not_found".to_string(),
                    message: msg,
                    details: None,
                },
            ),
            ApiError::BadRequest(msg) => (
                StatusCode::BAD_REQUEST,
                ErrorResponse {
                    error: "bad_request".to_string(),
                    message: msg,
                    details: None,
                },
            ),
            ApiError::Conflict(msg) => (
                StatusCode::CONFLICT,
                ErrorResponse {
                    error: "conflict".to_string(),
                    message: msg,
                    details: None,
                },
            ),
            ApiError::TooManyRequests(msg) => (
                StatusCode::TOO_MANY_REQUESTS,
                ErrorResponse {
                    error: "too_many_requests".to_string(),
                    message: msg,
                    details: None,
                },
            ),
            ApiError::Database(err) => {
                error!("Database error: {}", err);
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    ErrorResponse {
                        error: "database_error".to_string(),
                        message: "A database error occurred".to_string(),
                        details: Some(err.to_string()),
                    },
                )
            }
            ApiError::Internal(msg) => {
                error!("Internal error: {}", msg);
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    ErrorResponse {
                        error: "internal_error".to_string(),
                        message: msg,
                        details: None,
                    },
                )
            }
        };

        (status, Json(error_response)).into_response()
    }
}

impl From<sqlx::Error> for ApiError {
    fn from(err: sqlx::Error) -> Self {
        ApiError::Database(err)
    }
}

impl From<jsonwebtoken::errors::Error> for ApiError {
    fn from(_err: jsonwebtoken::errors::Error) -> Self {
        ApiError::Internal("Failed to process authentication token".to_string())
    }
}
