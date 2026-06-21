//! A minimal fixed-window rate limiter.
//!
//! Sized for the single-user deployment: one process, one in-memory counter,
//! no external store. Used to cap how many client error-beacon events
//! (`POST /v1/client-events`) the server will accept per window so a misbehaving
//! or hostile client cannot flood journald.

use std::sync::Mutex;
use std::time::{Duration, Instant};

struct Window {
    started_at: Instant,
    count: u32,
}

pub struct RateLimiter {
    inner: Mutex<Window>,
    max: u32,
    window: Duration,
}

impl RateLimiter {
    pub fn new(max: u32, window: Duration) -> Self {
        RateLimiter {
            inner: Mutex::new(Window {
                started_at: Instant::now(),
                count: 0,
            }),
            max,
            window,
        }
    }

    /// Reset the limiter to a fresh window, allowing the next `max` requests.
    #[cfg(test)]
    pub fn reset(&self) {
        let mut w = self.inner.lock().unwrap();
        w.started_at = Instant::now();
        w.count = 0;
    }

    /// Returns `true` if a request is allowed now (and counts it), `false` if the
    /// current window is exhausted. Resets the window once it has elapsed.
    pub fn allow(&self) -> bool {
        let mut w = self.inner.lock().unwrap();
        let now = Instant::now();
        if now.duration_since(w.started_at) >= self.window {
            w.started_at = now;
            w.count = 0;
        }
        if w.count < self.max {
            w.count += 1;
            true
        } else {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn allows_up_to_max_then_blocks_within_window() {
        let rl = RateLimiter::new(2, Duration::from_secs(60));
        assert!(rl.allow());
        assert!(rl.allow());
        assert!(!rl.allow(), "third call in the window must be rejected");
    }

    #[test]
    fn window_reset_allows_again() {
        let rl = RateLimiter::new(1, Duration::from_millis(1));
        assert!(rl.allow());
        assert!(!rl.allow());
        std::thread::sleep(Duration::from_millis(5));
        assert!(rl.allow(), "a fresh window should allow again");
    }
}
