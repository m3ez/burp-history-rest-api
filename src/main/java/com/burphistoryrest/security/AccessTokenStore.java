/*
 * Author: Supakiad S. (m3ez) - E-CQURITY (Thailand)
 * Profile: http://x.com/supakiad_mee
 */
package com.burphistoryrest.security;

import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Preferences;
import com.burphistoryrest.config.ApiSettings;
import com.burphistoryrest.util.Json;
import com.burphistoryrest.util.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Project-scoped multi-client token registry. Secrets are stored only as salted SHA-256 hashes. */
public final class AccessTokenStore {
    private static final String PROJECT_KEY = "burp_history_rest.access_tokens_v1";
    private static final String LEGACY_TOKEN_KEY = "burp_history_rest.token";
    private static final String FALLBACK_KEY = "burp_history_rest.access_tokens_fallback_v1";
    private static final int MAX_TOKENS = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PersistedObject projectData;
    private final Preferences preferences;
    private final Logging logging;
    private final List<TokenRecord> records = new ArrayList<>();
    private GeneratedToken bootstrapToken;

    public AccessTokenStore(PersistedObject projectData, Preferences preferences, Logging logging) {
        this.projectData = projectData;
        this.preferences = preferences;
        this.logging = logging;
        load();
    }

    public synchronized Optional<GeneratedToken> bootstrapToken() {
        return Optional.ofNullable(bootstrapToken);
    }

    public String storageMode() {
        return projectData == null ? "user-preferences-fallback" : "project";
    }

    public boolean projectScoped() {
        return projectData != null;
    }

    public synchronized List<TokenInfo> list() {
        Instant now = Instant.now();
        return records.stream().map(record -> record.info(now)).toList();
    }

    public synchronized GeneratedToken create(String label, Set<AccessScope> scopes, Instant expiresAt) {
        if (records.size() >= MAX_TOKENS) {
            throw new IllegalStateException("Maximum token count reached (" + MAX_TOKENS + ")");
        }
        String safeLabel = validateLabel(label);
        Set<AccessScope> safeScopes = scopes == null || scopes.isEmpty()
                ? EnumSet.of(AccessScope.HISTORY_READ)
                : EnumSet.copyOf(scopes);
        String secret = ApiSettings.generateToken();
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        TokenRecord record = new TokenRecord(
                UUID.randomUUID().toString(),
                safeLabel,
                safeScopes,
                Instant.now(),
                expiresAt,
                true,
                salt,
                digest(salt, secret)
        );
        records.add(record);
        save();
        return new GeneratedToken(record.id, record.label, secret, record.scopes, record.createdAt, record.expiresAt);
    }

    public synchronized GeneratedToken createDefaultReadToken(String label) {
        return create(label, EnumSet.of(
                AccessScope.HISTORY_READ,
                AccessScope.HISTORY_EVENTS,
                AccessScope.METRICS_READ
        ), null);
    }

    public synchronized GeneratedToken createRawToken(String label) {
        return create(label, EnumSet.allOf(AccessScope.class), Instant.now().plus(90, ChronoUnit.DAYS));
    }

    public synchronized boolean revoke(String id) {
        boolean changed = records.removeIf(record -> record.id.equals(id));
        if (changed) save();
        return changed;
    }

    public synchronized Optional<ApiPrincipal> authenticate(String suppliedSecret) {
        if (suppliedSecret == null || suppliedSecret.length() < 24 || suppliedSecret.length() > 512) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        ApiPrincipal matched = null;
        // Evaluate every token to keep timing less dependent on the matching position.
        for (TokenRecord record : records) {
            byte[] candidate = digest(record.salt, suppliedSecret);
            boolean equal = MessageDigest.isEqual(record.hash, candidate);
            if (equal && record.enabled && !record.expired(now)) {
                matched = record.principal();
            }
        }
        return Optional.ofNullable(matched);
    }

    private void load() {
        records.clear();
        String serialized = null;
        if (projectData != null) {
            try {
                serialized = projectData.getString(PROJECT_KEY);
            } catch (RuntimeException exception) {
                logging.logToError("Unable to load project-scoped REST API tokens", exception);
            }
        } else if (preferences != null) {
            try {
                serialized = preferences.getString(FALLBACK_KEY);
            } catch (RuntimeException exception) {
                logging.logToError("Unable to load fallback REST API tokens", exception);
            }
        }
        if (serialized != null && !serialized.isBlank()) {
            try {
                parse(serialized);
            } catch (RuntimeException exception) {
                records.clear();
                logging.logToError("REST API token store was invalid and has been reset", exception);
            }
        }
        if (!records.isEmpty()) return;

        String legacy = null;
        try {
            legacy = preferences == null ? null : preferences.getString(LEGACY_TOKEN_KEY);
        } catch (RuntimeException ignored) {
            // A fresh installation may not have preferences available.
        }
        if (legacy != null && legacy.length() >= 24 && !legacy.equals("migrated-to-project-token-store")) {
            bootstrapToken = importSecret("migrated-default", legacy, EnumSet.allOf(AccessScope.class), null);
            try {
                preferences.setString(LEGACY_TOKEN_KEY, "migrated-to-project-token-store");
            } catch (RuntimeException ignored) {
                // Migration is already complete in memory/project data.
            }
        } else {
            bootstrapToken = create("default-admin", EnumSet.allOf(AccessScope.class), null);
        }
    }

    private GeneratedToken importSecret(String label, String secret, Set<AccessScope> scopes, Instant expiresAt) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        TokenRecord record = new TokenRecord(
                UUID.randomUUID().toString(), label, scopes, Instant.now(), expiresAt, true,
                salt, digest(salt, secret)
        );
        records.add(record);
        save();
        return new GeneratedToken(record.id, label, secret, scopes, record.createdAt, expiresAt);
    }

    @SuppressWarnings("unchecked")
    private void parse(String serialized) {
        Object parsed = JsonParser.parse(serialized);
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("tokens") instanceof List<?> tokens)) {
            throw new IllegalArgumentException("Invalid token store format");
        }
        for (Object value : tokens) {
            if (!(value instanceof Map<?, ?> map)) continue;
            String id = String.valueOf(map.get("id"));
            String label = String.valueOf(map.get("label"));
            String scopesValue = String.valueOf(map.get("scopes"));
            Set<AccessScope> scopes = EnumSet.noneOf(AccessScope.class);
            for (String scope : scopesValue.split(",")) scopes.add(AccessScope.parse(scope));
            Instant createdAt = Instant.ofEpochMilli(((Number) map.get("createdAt")).longValue());
            Object expiresValue = map.get("expiresAt");
            Instant expiresAt = expiresValue instanceof Number number && number.longValue() > 0
                    ? Instant.ofEpochMilli(number.longValue()) : null;
            boolean enabled = !(map.get("enabled") instanceof Boolean b) || b;
            byte[] salt = Base64.getUrlDecoder().decode(String.valueOf(map.get("salt")));
            byte[] hash = Base64.getUrlDecoder().decode(String.valueOf(map.get("hash")));
            records.add(new TokenRecord(id, validateLabel(label), scopes, createdAt, expiresAt, enabled, salt, hash));
            if (records.size() >= MAX_TOKENS) break;
        }
    }

    private void save() {
        List<Map<String, Object>> tokens = new ArrayList<>();
        for (TokenRecord record : records) {
            Map<String, Object> token = new LinkedHashMap<>();
            token.put("id", record.id);
            token.put("label", record.label);
            token.put("scopes", record.scopes.stream().map(AccessScope::wireName).sorted().collect(java.util.stream.Collectors.joining(",")));
            token.put("createdAt", record.createdAt.toEpochMilli());
            token.put("expiresAt", record.expiresAt == null ? 0L : record.expiresAt.toEpochMilli());
            token.put("enabled", record.enabled);
            token.put("salt", Base64.getUrlEncoder().withoutPadding().encodeToString(record.salt));
            token.put("hash", Base64.getUrlEncoder().withoutPadding().encodeToString(record.hash));
            tokens.add(token);
        }
        String serialized = Json.stringify(Map.of("version", 1, "tokens", tokens));
        if (projectData != null) {
            projectData.setString(PROJECT_KEY, serialized);
            return;
        }
        if (preferences != null) {
            preferences.setString(FALLBACK_KEY, serialized);
        }
    }

    private static String validateLabel(String label) {
        String safe = label == null ? "client" : label.trim();
        if (safe.isEmpty() || safe.length() > 80 || safe.indexOf('\r') >= 0 || safe.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Token label must contain 1 to 80 characters without newlines");
        }
        return safe;
    }

    private static byte[] digest(byte[] salt, String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("burp-history-rest-token-v1\0".getBytes(StandardCharsets.UTF_8));
            digest.update(salt);
            digest.update(secret.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record TokenRecord(
            String id,
            String label,
            Set<AccessScope> scopes,
            Instant createdAt,
            Instant expiresAt,
            boolean enabled,
            byte[] salt,
            byte[] hash
    ) {
        private TokenRecord {
            scopes = Set.copyOf(scopes);
            salt = salt.clone();
            hash = hash.clone();
        }

        boolean expired(Instant now) {
            return expiresAt != null && !expiresAt.isAfter(now);
        }

        ApiPrincipal principal() {
            return new ApiPrincipal(id, label, scopes, expiresAt);
        }

        TokenInfo info(Instant now) {
            return new TokenInfo(id, label, scopes, createdAt, expiresAt, enabled, expired(now));
        }
    }

    public record GeneratedToken(
            String id,
            String label,
            String secret,
            Set<AccessScope> scopes,
            Instant createdAt,
            Instant expiresAt
    ) {
        public GeneratedToken {
            scopes = Set.copyOf(scopes);
        }
    }

    public record TokenInfo(
            String id,
            String label,
            Set<AccessScope> scopes,
            Instant createdAt,
            Instant expiresAt,
            boolean enabled,
            boolean expired
    ) {
        public TokenInfo {
            scopes = Set.copyOf(scopes);
        }
    }
}
