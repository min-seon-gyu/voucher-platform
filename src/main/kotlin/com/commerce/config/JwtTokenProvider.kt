package com.commerce.config

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.util.*
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret:#{null}}") private val rawSecret: String?,
    @Value("\${jwt.expiration:86400000}") private val expirationMs: Long,
    private val env: Environment,
) {
    companion object {
        private val log = LoggerFactory.getLogger(JwtTokenProvider::class.java)
        const val DEV_SECRET = "local-dev-only-secret-key-minimum-256-bits-long-do-not-use-in-production!!"
    }

    private val key: SecretKey = Keys.hmacShaKeyFor((rawSecret ?: DEV_SECRET).toByteArray())

    @PostConstruct
    fun validateSecret() {
        val isProd = env.activeProfiles.contains("prod")
        val usingDevSecret = rawSecret.isNullOrBlank() || rawSecret == DEV_SECRET
        if (isProd && usingDevSecret) {
            throw IllegalStateException("JWT_SECRET must be set when running with the prod profile")
        }
        if (!isProd && usingDevSecret) {
            log.warn("⚠️  dev-only JWT secret in use — DO NOT use in production")
        }
    }

    fun generateToken(memberId: Long, role: String): String {
        val now = Date()
        return Jwts.builder()
            .subject(memberId.toString())
            .claim("role", role)
            .issuedAt(now)
            .expiration(Date(now.time + expirationMs))
            .signWith(key)
            .compact()
    }

    fun getMemberIdFromToken(token: String): Long {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        return claims.subject.toLong()
    }

    fun getRoleFromToken(token: String): String {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        return claims.get("role", String::class.java) ?: "USER"
    }

    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token)
            true
        } catch (e: Exception) {
            false
        }
    }
}
