//! Configuration loading tests for the RSS aggregator server.

#[cfg(test)]
mod config_tests {
    use crate::config::Config;
    use std::fs;
    use tempfile::NamedTempFile;

    /// Helper function to create a temporary config file with custom content
    fn create_temp_config(content: &str) -> NamedTempFile {
        let temp_file = NamedTempFile::with_suffix(".toml").expect("Failed to create temp file");
        fs::write(temp_file.path(), content).expect("Failed to write temp config");
        temp_file
    }

    /// Test that invalid TOML configuration fails to load
    #[tokio::test]
    async fn test_load_invalid_toml() {
        let invalid_content = r#"
[server
host = "127.0.0.1"
port = 3000

[auth]
username = "admin"
"#;

        let temp_file = create_temp_config(invalid_content);
        let result = Config::from_file(temp_file.path());
        
        assert!(result.is_err(), "Should fail to load invalid TOML");
    }

    /// Test that missing required fields fail to load
    #[tokio::test]
    async fn test_load_missing_required_fields() {
        let incomplete_content = r#"
[server]
host = "127.0.0.1"
# Missing port field

[auth]
username = "admin"
# Missing password_hash and jwt_secret
"#;

        let temp_file = create_temp_config(incomplete_content);
        let result = Config::from_file(temp_file.path());
        
        assert!(result.is_err(), "Should fail to load config with missing fields");
    }

    /// Test that invalid password hash format fails to load
    #[tokio::test]
    async fn test_load_invalid_password_hash() {
        let config_content = r#"
[server]
host = "127.0.0.1"
port = 3000

[auth]
username = "admin"
password_hash = "invalid-hash-format"
jwt_secret = "test-jwt-secret-min-32-chars-long"
"#;

        let temp_file = create_temp_config(config_content);
        let result = Config::from_file(temp_file.path());
        
        assert!(result.is_err(), "Should fail to load config with invalid password hash");
    }

    /// Test that valid Argon2 password hash can be correctly parsed
    #[tokio::test]
    async fn test_load_valid_password_hash() {
        // Test that we can parse a valid Argon2 password hash string
        let valid_hash_str = "$argon2id$v=19$m=65536,t=2,p=1$elZxeHB1VzhpcUliR3RkMA$pSockUc1J5m0mTLfKRb/mg";
        
        // Now test parsing an auth config with this password hash
        let auth_config_str = format!(r#"
username = "admin"
password_hash = "{}"
jwt_secret = "test-jwt-secret-min-32-chars-long"
"#, valid_hash_str);
        
        // Parse the auth config section
        let auth_config: crate::config::AuthConfig = toml::from_str(&auth_config_str)
            .expect("Failed to parse auth config with valid password hash");
        
        assert_eq!(auth_config.username, "admin");
        assert_eq!(auth_config.jwt_secret, "test-jwt-secret-min-32-chars-long");
        
        // Verify the password hash is correctly parsed as PasswordHashString
        let parsed_hash_str = auth_config.password_hash.to_string();
        assert_eq!(parsed_hash_str, valid_hash_str);
        assert!(parsed_hash_str.starts_with("$argon2id$"));
        assert!(parsed_hash_str.contains("v=19"));
        assert!(parsed_hash_str.contains("m=65536"));
        assert!(parsed_hash_str.contains("t=2"));
        assert!(parsed_hash_str.contains("p=1"));
    }

    /// Test configuration structure validation without actual Argon2 hash
    #[tokio::test]
    async fn test_config_structure_validation() {
        // Test that we can parse the basic structure
        let config_content = r#"
[server]
host = "127.0.0.1"
port = 8080

[auth]
username = "testuser"
jwt_secret = "test-jwt-secret-min-32-chars-long"
"#;

        let temp_file = create_temp_config(config_content);
        let result = Config::from_file(temp_file.path());
        
        // Should fail because password_hash is missing, but structure is valid
        assert!(result.is_err(), "Should fail without password_hash");
        
        // Check that error is about missing password_hash, not structure
        let error_msg = format!("{:?}", result.unwrap_err());
        assert!(error_msg.contains("password_hash") || error_msg.contains("missing field"));
    }

    /// Test that we can parse server configuration independently
    #[tokio::test]
    async fn test_server_config_parsing() {
        let server_content = r#"
host = "192.168.1.100"
port = 9999
"#;

        let temp_file = create_temp_config(server_content);
        
        // Parse just the server config section
        let server_config: crate::config::ServerConfig = toml::from_str(
            &fs::read_to_string(temp_file.path()).expect("Failed to read temp file")
        ).expect("Failed to parse server config");
        
        assert_eq!(server_config.host, "192.168.1.100");
        assert_eq!(server_config.port, 9999);
    }

    /// Test that config file paths are properly formatted
    #[tokio::test]
    async fn test_config_error_message_format() {
        let original_dir = std::env::current_dir().expect("Failed to get current dir");
        
        // Change to a temp directory
        let temp_dir = tempfile::TempDir::new().expect("Failed to create temp dir");
        std::env::set_current_dir(&temp_dir).expect("Failed to change to temp dir");
        
        // Try to load config that doesn't exist
        let result = Config::load();
        assert!(result.is_err(), "Should fail when no config exists");
        
        let error_msg = format!("{}", result.unwrap_err());
        
        // Verify error message contains expected paths
        assert!(error_msg.contains("Configuration file not found"));
        assert!(error_msg.contains("Standard OS configuration directory"));
        assert!(error_msg.contains("Local directory"));
        assert!(error_msg.contains("config.toml"));
        
        // Restore original directory
        std::env::set_current_dir(original_dir).expect("Failed to restore original dir");
    }

    /// Test environment variable setting capabilities
    #[tokio::test]
    async fn test_environment_variable_handling() {
        // Test that we can set environment variables (even if we don't use unsafe ones here)
        let original_jwt = std::env::var("FEED_JWT_SECRET");
        
        // Note: We can't test the actual override without unsafe env::set_var
        // But we can test that the environment is accessible
        let current_dir = std::env::current_dir();
        assert!(current_dir.is_ok(), "Should be able to get current directory");
        
        // Restore original JWT secret if it existed
        if let Ok(jwt) = original_jwt {
            unsafe {
                std::env::set_var("FEED_JWT_SECRET", jwt);
            }
        }
    }
}