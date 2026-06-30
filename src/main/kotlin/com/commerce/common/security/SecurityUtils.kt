package com.commerce.common.security

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import org.springframework.security.core.context.SecurityContextHolder

/**
 * 신규 엔드포인트(Plan 2~4)는 요청 body의 memberId를 신뢰하지 않고
 * 인증 principal에서 신원을 도출한다. principal은 JwtAuthenticationFilter가
 * 세팅한 회원 ID(Long)이다.
 */
object SecurityUtils {

    fun currentMemberId(): Long {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        if (principal !is Long) throw BusinessException(ErrorCode.UNAUTHORIZED)
        return principal
    }

    fun currentMemberIdOrNull(): Long? =
        SecurityContextHolder.getContext().authentication?.principal as? Long

    /** 현재 인증 주체가 ADMIN 역할을 보유하는지 여부(JwtAuthenticationFilter가 ROLE_<role> 권한으로 주입). */
    fun isAdmin(): Boolean =
        SecurityContextHolder.getContext().authentication
            ?.authorities?.any { it.authority == "ROLE_ADMIN" } ?: false
}
