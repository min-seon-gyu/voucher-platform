package com.commerce.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val requestTraceFilter: RequestTraceFilter,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // 공개 엔드포인트
                it.requestMatchers("/api/v1/members/register", "/api/v1/members/login").permitAll()
                it.requestMatchers("/actuator/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()

                // ── 관리자(ADMIN) 전용 — 특권 운영 엔드포인트 ──────────────────────────
                // 구체 매처가 anyRequest()보다 먼저 평가된다. GET 조회는 매처에서 제외해 공개 유지.
                it.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")               // 원장 admin
                it.requestMatchers("/api/v1/settlements/**").hasRole("ADMIN")          // 정산 계산/확정/이의/지급
                it.requestMatchers(HttpMethod.POST, "/api/v1/regions").hasRole("ADMIN")
                it.requestMatchers(HttpMethod.PUT, "/api/v1/regions/**").hasRole("ADMIN")
                it.requestMatchers(
                    HttpMethod.POST, "/api/v1/regions/*/suspend", "/api/v1/regions/*/activate",
                ).hasRole("ADMIN")
                it.requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/sellers/*/approve", "/api/v1/sellers/*/reject",
                    "/api/v1/sellers/*/suspend", "/api/v1/sellers/*/unsuspend", "/api/v1/sellers/*/terminate",
                ).hasRole("ADMIN")
                it.requestMatchers(
                    HttpMethod.POST,
                    "/api/v1/members/*/suspend", "/api/v1/members/*/unsuspend", "/api/v1/members/*/withdraw",
                ).hasRole("ADMIN")
                // 프로모션 생성은 플랫폼 출연 캠페인 → ADMIN 전용(쿠폰 발급 /{id}/coupons는 회원 인증으로 별도).
                it.requestMatchers(HttpMethod.POST, "/api/v1/promotions").hasRole("ADMIN")

                // ── 인증 필요(역할 무관) ─────────────────────────────────────────────
                it.requestMatchers("/api/v1/me").authenticated()
                // 판매자 등록: 인증 필수 + 컨트롤러에서 ownerId를 JWT 주체로 강제(본인 판매자만).
                it.requestMatchers(HttpMethod.POST, "/api/v1/sellers").authenticated()
                // 상품 등록/판매개시: 인증 필수 + 서비스에서 판매자 소유주(JWT 주체) 강제. GET 조회는 공개.
                it.requestMatchers(HttpMethod.POST, "/api/v1/products", "/api/v1/products/*/on-sale").authenticated()
                // 장바구니·주문: 인증 필수 — 항상 JWT 주체의 카트/주문만 조작.
                it.requestMatchers("/api/v1/cart", "/api/v1/cart/**", "/api/v1/orders", "/api/v1/orders/**").authenticated()
                // 그 외(판매자 등록/조회, 회원 조회, 지자체 조회, 프로모션/포인트/쿠폰)는
                // 공개이거나 컨트롤러 레벨 SecurityUtils가 본인 자원 인가를 강제한다.
                it.anyRequest().permitAll()
            }
            // 미인증 접근 시 403 대신 401 반환.
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
            // 필터 체인: 요청 추적(TraceId/MDC) → JWT 인증 순으로 앞단에 배치한다.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(requestTraceFilter, JwtAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
