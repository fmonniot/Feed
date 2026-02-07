# RSS Aggregator API Documentation

This document provides comprehensive documentation for the RSS Aggregator REST API. The API allows you to manage RSS feeds, articles, categories, and webhooks with JWT-based authentication.

## Table of Contents

- [Base URL](#base-url)
- [Authentication](#authentication)
- [Response Format](#response-format)
- [Error Handling](#error-handling)
- [API Endpoints](#api-endpoints)
  - [Health Check](#health-check)
  - [Authentication](#authentication-endpoints)
  - [Feeds](#feeds)
  - [Articles](#articles)
  - [Categories](#categories)
  - [Search](#search)
  - [Webhooks](#webhooks)
  - [Statistics](#statistics)
  - [Logs](#logs)
  - [OPML Import](#opml-import)
  - [Feed Health](#feed-health)

## Base URL

All API endpoints are prefixed with `/v1`:

```
http://localhost:3000/v1
```

## Authentication

The API uses JWT (JSON Web Token) authentication with Bearer tokens. Most endpoints require authentication.

### Getting Access Tokens

1. **Login** - Use the `/auth/login` endpoint with username and password to get both access and refresh tokens
2. **Refresh** - Use the `/auth/refresh` endpoint with a refresh token to get a new access token

### Token Types

- **Access Token**: Short-lived (15 minutes), used for API requests
- **Refresh Token**: Long-lived (90 days), used to get new access tokens

### Using Tokens

Include the access token in the `Authorization` header:

```
Authorization: Bearer <access_token>
```

## Response Format

All successful responses follow a consistent format:

```json
{
  "data": <response_data>,
  "meta": {
    "limit": 50,
    "offset": 0,
    "total": 100
  }
}
```

- `data`: The actual response data
- `meta`: Optional pagination metadata for list endpoints

## Error Handling

Errors return structured JSON responses with appropriate HTTP status codes:

```json
{
  "error": "error_type",
  "message": "Human-readable error message",
  "details": "Additional error details (optional)"
}
```

### Common Error Types

- `unauthorized` (401): Authentication failed or missing
- `not_found` (404): Resource not found
- `bad_request` (400): Invalid request parameters
- `database_error` (500): Database operation failed
- `internal_error` (500): Internal server error

---

## API Endpoints

### Health Check

#### GET /health

Check if the server and database are operational. This endpoint does not require authentication.

**Response:**
```json
{
  "status": "healthy",
  "database": "connected"
}
```

---

### Authentication Endpoints

#### POST /auth/login

Authenticate with username and password to receive access and refresh tokens.

**Request Body:**
```json
{
  "username": "admin",
  "password": "your_password"
}
```

**Response:**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "refresh_token": "dGhpcy1pcy1hLXJlZnJlc2gtdG9rZW4=",
  "token_type": "Bearer",
  "expires_in": 900,
  "username": "admin"
}
```

#### POST /auth/refresh

Refresh an access token using a valid refresh token.

**Request Body:**
```json
{
  "refresh_token": "dGhpcy1pcy1hLXJlZnJlc2gtdG9rZW4="
}
```

**Response:**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "token_type": "Bearer",
  "expires_in": 900
}
```

---

### Feeds

#### GET /feeds

Get all feeds with unread counts.

**Authentication:** Required

**Response:**
```json
{
  "data": [
    {
      "id": 1,
      "url": "https://example.com/feed.xml",
      "title": "Example Feed",
      "custom_title": null,
      "is_paused": false,
      "fetch_interval_minutes": 30,
      "error_count": 0,
      "last_fetched": 1640995200,
      "unread_count": 5,
      "category_id": null
    }
  ]
}
```

#### POST /feeds

Add a new RSS feed. The URL will be validated and the feed will be fetched to extract initial articles.

**Authentication:** Required

**Request Body:**
```json
{
  "url": "https://example.com/feed.xml"
}
```

**Response:**
```json
{
  "data": {
    "id": 123,
    "message": "Feed 'Example Feed' added successfully"
  }
}
```

#### GET /feeds/{feed_id}

Get details of a specific feed.

**Authentication:** Required

**Path Parameters:**
- `feed_id`: ID of the feed

**Response:**
```json
{
  "data": {
    "id": 1,
    "url": "https://example.com/feed.xml",
    "title": "Example Feed",
    "custom_title": null,
    "is_paused": false,
    "fetch_interval_minutes": 30,
    "error_count": 0,
    "last_fetched": 1640995200,
    "category_id": null
  }
}
```

#### PUT /feeds/{feed_id}

Update feed settings including custom title, fetch interval, and pause status.

**Authentication:** Required

**Path Parameters:**
- `feed_id`: ID of the feed

**Request Body:**
```json
{
  "custom_title": "My Custom Feed Name",
  "fetch_interval_minutes": 60,
  "is_paused": false
}
```

**Response:**
```json
{
  "data": {
    "updated": true
  }
}
```

#### DELETE /feeds/{feed_id}

Delete a feed and all its articles.

**Authentication:** Required

**Path Parameters:**
- `feed_id`: ID of the feed

**Response:** 204 No Content

#### GET /feeds/uncategorized

Get all feeds that are not assigned to any category.

**Authentication:** Required

**Response:** Same format as `/feeds`

#### POST /feeds/import/opml

Import feeds from an OPML file. Send the OPML XML content as plain text in the request body.

**Authentication:** Required

**Request Body:** OPML XML content as plain text

**Response:**
```json
{
  "data": {
    "total_feeds": 10,
    "imported": 8,
    "already_exists": 1,
    "failed": 1,
    "categories_created": 3,
    "feeds": [
      {
        "url": "https://example.com/feed.xml",
        "title": "Example Feed",
        "status": "imported",
        "error": null,
        "category": "Technology"
      }
    ]
  }
}
```

#### GET /feeds/health

Get feed health dashboard with status overview and per-feed details.

**Authentication:** Required

**Response:**
```json
{
  "data": {
    "summary": {
      "total_feeds": 50,
      "active_feeds": 45,
      "paused_feeds": 5,
      "feeds_with_errors": 2,
      "never_fetched": 1,
      "total_errors": 8
    },
    "feeds": [
      {
        "id": 1,
        "url": "https://example.com/feed.xml",
        "title": "Example Feed",
        "display_title": "Example Feed",
        "is_paused": false,
        "error_count": 0,
        "last_fetched": 1640995200,
        "last_fetched_ago": "2h ago",
        "fetch_interval_minutes": 30,
        "status": "healthy"
      }
    ]
  }
}
```

#### POST /feeds/{feed_id}/read

Mark all articles in a feed as read.

**Authentication:** Required

**Path Parameters:**
- `feed_id`: ID of the feed

**Response:**
```json
{
  "data": {
    "updated": 25
  }
}
```

#### PUT /feeds/{feed_id}/category

Assign a feed to a category or remove it from category.

**Authentication:** Required

**Path Parameters:**
- `feed_id`: ID of the feed

**Request Body:**
```json
{
  "category_id": 5
}
```
or
```json
{
  "category_id": null
}
```

**Response:**
```json
{
  "data": {
    "updated": true
  }
}
```

#### GET /feeds/{feed_id}/articles

Get articles from a specific feed.

**Authentication:** Required

**Path Parameters:**
- `feed_id`: ID of the feed

**Query Parameters:**
- `limit`: Number of articles to return (default: 50)
- `offset`: Number of articles to skip (default: 0)
- `since`: Unix timestamp - only articles after this time
- `until`: Unix timestamp - only articles before this time
- `is_read`: Filter by read status (true/false)
- `is_starred`: Filter by starred status (true/false)

**Response:**
```json
{
  "data": [
    {
      "id": 1,
      "feed_id": 1,
      "guid": "article-123",
      "title": "Article Title",
      "content": "Article content...",
      "link": "https://example.com/article",
      "author": "Author Name",
      "published": 1640995200,
      "is_read": false,
      "is_starred": false,
      "read_at": null,
      "starred_at": null
    }
  ],
  "meta": {
    "limit": 50,
    "offset": 0
  }
}
```

---

### Articles

#### GET /articles

Get articles with optional filtering and pagination.

**Authentication:** Required

**Query Parameters:**
- `limit`: Number of articles to return (default: 50)
- `offset`: Number of articles to skip (default: 0)
- `since`: Unix timestamp - only articles after this time
- `until`: Unix timestamp - only articles before this time
- `is_read`: Filter by read status (true/false)
- `is_starred`: Filter by starred status (true/false)

**Response:** Same format as `/feeds/{feed_id}/articles`

#### GET /articles/search

Search articles using full-text search with FTS5 syntax.

**Authentication:** Required

**Query Parameters:**
- `q`: Search query (supports FTS5 syntax)
- `limit`: Number of results to return (default: 50)
- `offset`: Number of results to skip (default: 0)
- `feed_id`: Optional - limit search to specific feed

**Search Syntax Examples:**
- `term1 term2`: Articles containing both terms
- `term1 OR term2`: Articles containing either term
- `"exact phrase"`: Articles containing the exact phrase
- `term*`: Prefix search (matches terms starting with "term")
- `NOT term`: Exclude articles containing term

**Response:**
```json
{
  "data": [
    {
      "id": 1,
      "feed_id": 1,
      "guid": "article-123",
      "title": "Article Title",
      "content": "Article content...",
      "link": "https://example.com/article",
      "author": "Author Name",
      "published": 1640995200,
      "is_read": false,
      "is_starred": false,
      "read_at": null,
      "starred_at": null,
      "rank": 0.95
    }
  ],
  "meta": {
    "limit": 50,
    "offset": 0
  }
}
```

#### POST /articles/read

Mark multiple articles as read or unread.

**Authentication:** Required

**Request Body:**
```json
{
  "article_ids": [1, 2, 3, 4],
  "is_read": true
}
```

**Response:**
```json
{
  "data": {
    "updated": 4
  }
}
```

#### POST /articles/read-all

Mark all articles as read.

**Authentication:** Required

**Response:**
```json
{
  "data": {
    "updated": 150
  }
}
```

#### GET /articles/unread-count

Get total count of unread articles.

**Authentication:** Required

**Response:**
```json
{
  "data": {
    "total_unread": 25
  }
}
```

#### GET /articles/starred

Get starred articles ordered by when they were starred (most recent first).

**Authentication:** Required

**Query Parameters:**
- `limit`: Number of articles to return (default: 50)
- `offset`: Number of articles to skip (default: 0)

**Response:** Same format as `/articles`

#### GET /articles/starred-count

Get total count of starred articles.

**Authentication:** Required

**Response:**
```json
{
  "data": {
    "total_starred": 10
  }
}
```

#### PUT /articles/{article_id}/read

Mark a single article as read or unread.

**Authentication:** Required

**Path Parameters:**
- `article_id`: ID of the article

**Request Body:**
```json
{
  "is_read": true
}
```

**Response:**
```json
{
  "data": {
    "updated": 1
  }
}
```

#### PUT /articles/{article_id}/star

Star or unstar a single article.

**Authentication:** Required

**Path Parameters:**
- `article_id`: ID of the article

**Request Body:**
```json
{
  "is_starred": true
}
```

**Response:**
```json
{
  "data": {
    "updated": true
  }
}
```

---

### Categories

#### GET /categories

Get all categories as a simple list.

**Authentication:** Required

**Response:**
```json
{
  "data": [
    {
      "id": 1,
      "name": "Technology",
      "position": 0
    },
    {
      "id": 2,
      "name": "News", 
      "position": 1
    }
  ]
}
```

#### GET /categories/with-feeds

Get all categories with their feeds organized hierarchically.

**Authentication:** Required

**Response:**
```json
{
  "data": {
    "categories": [
      {
        "id": 1,
        "name": "Technology",
        "position": 0,
        "feeds": [
          {
            "id": 1,
            "url": "https://techcrunch.com/feed",
            "title": "TechCrunch",
            "custom_title": null,
            "is_paused": false,
            "fetch_interval_minutes": 30,
            "error_count": 0,
            "last_fetched": 1640995200,
            "unread_count": 5,
            "category_id": 1
          }
        ]
      }
    ],
    "uncategorized": [
      {
        "id": 3,
        "url": "https://example.com/feed",
        "title": "Example Feed",
        "custom_title": null,
        "is_paused": false,
        "fetch_interval_minutes": 30,
        "error_count": 0,
        "last_fetched": 1640995200,
        "unread_count": 2,
        "category_id": null
      }
    ]
  }
}
```

#### POST /categories

Create a new category.

**Authentication:** Required

**Request Body:**
```json
{
  "name": "Technology"
}
```

**Response:**
```json
{
  "data": {
    "id": 5,
    "message": "Category 'Technology' created successfully"
  }
}
```

#### PUT /categories/{category_id}

Update a category's name.

**Authentication:** Required

**Path Parameters:**
- `category_id`: ID of the category

**Request Body:**
```json
{
  "name": "New Category Name"
}
```

**Response:** 204 No Content

#### DELETE /categories/{category_id}

Delete a category. Feeds in this category will become uncategorized.

**Authentication:** Required

**Path Parameters:**
- `category_id`: ID of the category

**Response:** 204 No Content

#### POST /categories/reorder

Reorder categories by updating their positions.

**Authentication:** Required

**Request Body:**
```json
{
  "positions": [
    {
      "category_id": 1,
      "position": 0
    },
    {
      "category_id": 2,
      "position": 1
    }
  ]
}
```

**Response:** 204 No Content

#### GET /categories/{category_id}/feeds

Get feeds in a specific category.

**Authentication:** Required

**Path Parameters:**
- `category_id`: ID of the category

**Response:** Same format as `/feeds`

---

### Webhooks

Webhooks allow you to receive HTTP notifications when certain events occur in the RSS aggregator.

#### GET /webhooks

Get all webhooks.

**Authentication:** Required

**Response:**
```json
{
  "data": [
    {
      "id": 1,
      "url": "https://your-app.com/webhook",
      "secret": "optional_secret",
      "events": "new_article,feed_error",
      "is_active": true,
      "created_at": 1640995200
    }
  ]
}
```

#### POST /webhooks

Create a new webhook.

**Authentication:** Required

**Request Body:**
```json
{
  "url": "https://your-app.com/webhook",
  "secret": "optional_secret_for_signature",
  "events": "new_article,feed_error"
}
```

**Events:**
- `new_article`: Fired when a new article is fetched
- `feed_error`: Fired when a feed fetch fails

**Response:**
```json
{
  "data": {
    "id": 2,
    "message": "Webhook created successfully"
  }
}
```

#### GET /webhooks/{webhook_id}

Get a single webhook.

**Authentication:** Required

**Path Parameters:**
- `webhook_id`: ID of the webhook

**Response:** Same format as individual webhook in `/webhooks`

#### PUT /webhooks/{webhook_id}

Update a webhook.

**Authentication:** Required

**Path Parameters:**
- `webhook_id`: ID of the webhook

**Request Body:**
```json
{
  "url": "https://your-app.com/webhook-updated",
  "secret": "new_secret",
  "events": "new_article",
  "is_active": true
}
```

**Response:**
```json
{
  "data": {
    "updated": true
  }
}
```

#### DELETE /webhooks/{webhook_id}

Delete a webhook.

**Authentication:** Required

**Path Parameters:**
- `webhook_id`: ID of the webhook

**Response:** 204 No Content

#### Webhook Payload Format

When a webhook is triggered, it receives a POST request with a JSON payload:

**New Article Event:**
```json
{
  "event": "new_article",
  "timestamp": 1640995200,
  "data": {
    "article_id": 123,
    "feed_id": 1,
    "feed_title": "Example Feed",
    "title": "Article Title",
    "link": "https://example.com/article",
    "author": "Author Name",
    "published": 1640995200
  }
}
```

**Feed Error Event:**
```json
{
  "event": "feed_error",
  "timestamp": 1640995200,
  "data": {
    "feed_id": 1,
    "feed_url": "https://example.com/feed.xml",
    "feed_title": "Example Feed",
    "error": "Failed to fetch feed: timeout",
    "error_count": 3
  }
}
```

#### Webhook Signature Verification

If a webhook has a secret configured, the request will include an `X-Webhook-Signature` header containing an HMAC-SHA256 signature of the request body. You can verify this signature using:

```
signature = HMAC-SHA256(secret, request_body)
```

---

### Statistics

#### GET /stats

Get overall statistics for the RSS aggregator.

**Authentication:** Required

**Response:**
```json
{
  "data": {
    "feeds": {
      "total": 50,
      "active": 45,
      "paused": 5,
      "with_errors": 2,
      "categories": 8
    },
    "articles": {
      "total": 5000,
      "unread": 125,
      "read": 4875,
      "starred": 25
    },
    "trends": {
      "articles_last_24h": 50,
      "articles_last_7d": 350,
      "articles_last_30d": 1500,
      "daily_articles": [
        {
          "date": "2022-01-01",
          "count": 45
        },
        {
          "date": "2022-01-02",
          "count": 52
        }
      ]
    }
  }
}
```

---

### Logs

#### GET /logs

Get recent log entries from the server log files.

**Authentication:** Required

**Query Parameters:**
- `lines`: Number of lines to return (default: 100, max: 1000)

**Response:** Plain text log entries

---

### Feed Health

#### GET /feeds/health

Get feed health dashboard with status overview and per-feed details.

**Authentication:** Required

**Response:** (Same as documented in Feeds section)

Feed status values:
- `healthy`: No errors, fetching successfully
- `warning`: Some errors (1-4 error count)
- `error`: Many errors (5+ error count)
- `paused`: Feed is paused
- `never_fetched`: Feed has never been successfully fetched

---

## Usage Examples

### Basic Workflow

1. **Authenticate:**
```bash
curl -X POST http://localhost:3000/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"your_password"}'
```

2. **Add a feed:**
```bash
curl -X POST http://localhost:3000/v1/feeds \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <access_token>" \
  -d '{"url":"https://feeds.bbci.co.uk/news/rss.xml"}'
```

3. **Get articles:**
```bash
curl -X GET "http://localhost:3000/v1/articles?limit=10&is_read=false" \
  -H "Authorization: Bearer <access_token>"
```

4. **Mark article as read:**
```bash
curl -X PUT http://localhost:3000/v1/articles/123/read \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <access_token>" \
  -d '{"is_read":true}'
```

### Search Example

```bash
curl -X GET "http://localhost:3000/v1/articles/search?q=technology%20OR%20AI&limit=20" \
  -H "Authorization: Bearer <access_token>"
```

### Webhook Example

```bash
curl -X POST http://localhost:3000/v1/webhooks \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <access_token>" \
  -d '{
    "url": "https://your-app.com/rss-webhook",
    "secret": "your_webhook_secret",
    "events": "new_article,feed_error"
  }'
```

---

## Rate Limiting and Performance

- Feed fetching is automatically scheduled based on each feed's `fetch_interval_minutes`
- Minimum fetch interval is 5 minutes
- Failed feeds are retried with exponential backoff
- Database connections are pooled for optimal performance
- Log files are automatically rotated to prevent disk space issues

---

## Security Considerations

- Always use HTTPS in production environments
- Webhook URLs should use HTTPS when possible
- Refresh tokens are stored securely in the database
- Passwords are hashed using Argon2
- JWT tokens have configurable expiration times
- Invalid tokens are immediately rejected

---

## Troubleshooting

### Common Issues

1. **"Invalid or expired token"** - Use the refresh endpoint to get a new access token
2. **"Failed to fetch or parse feed"** - Verify the feed URL is accessible and contains valid RSS/Atom content
3. **"Database health check failed"** - Check database connection and configuration
4. **"Feed not found"** - Verify the feed ID exists and you have permission to access it

### Debug Information

- Use `/logs` endpoint to check server logs
- Use `/feeds/health` to monitor feed status
- Check error counts on feeds to identify problematic sources
- Monitor webhook delivery logs for integration issues

---

## Version Information

This documentation covers API version 1.0. All endpoints are prefixed with `/v1`.
