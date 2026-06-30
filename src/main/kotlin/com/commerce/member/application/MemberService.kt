package com.commerce.member.application

import com.commerce.common.exception.BusinessException
import com.commerce.common.exception.ErrorCode
import com.commerce.config.JwtTokenProvider
import com.commerce.member.domain.Member
import com.commerce.member.domain.MemberRole
import com.commerce.member.domain.event.MemberSuspendedEvent
import com.commerce.member.domain.event.MemberWithdrawnEvent
import com.commerce.member.infrastructure.MemberJpaRepository
import com.commerce.member.interfaces.dto.LoginRequest
import com.commerce.member.interfaces.dto.RegisterMemberRequest
import com.commerce.member.interfaces.dto.TokenResponse
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class MemberService(
    private val memberRepository: MemberJpaRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @Transactional
    fun register(request: RegisterMemberRequest): Member {
        if (memberRepository.existsByEmail(request.email))
            throw BusinessException(ErrorCode.INVALID_INPUT, "이미 사용 중인 이메일입니다")

        val member = Member(
            email = request.email,
            name = request.name,
            password = passwordEncoder.encode(request.password),
        )
        member.activate()
        return memberRepository.save(member)
    }

    fun login(request: LoginRequest): TokenResponse {
        val member = memberRepository.findByEmail(request.email)
            ?: throw BusinessException(ErrorCode.ENTITY_NOT_FOUND, "회원을 찾을 수 없습니다")

        if (!passwordEncoder.matches(request.password, member.password))
            throw BusinessException(ErrorCode.INVALID_INPUT, "비밀번호가 일치하지 않습니다")

        if (!member.isActive())
            throw BusinessException(ErrorCode.MEMBER_NOT_ACTIVE)

        val token = jwtTokenProvider.generateToken(member.id, member.role.name)
        return TokenResponse(accessToken = token, memberId = member.id, role = member.role.name)
    }

    fun getById(id: Long): Member =
        memberRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.ENTITY_NOT_FOUND) }

    @Transactional
    fun suspend(memberId: Long): Member {
        val member = getById(memberId)
        member.suspend()
        eventPublisher.publishEvent(MemberSuspendedEvent(member.id))
        return member
    }

    @Transactional
    fun unsuspend(memberId: Long): Member {
        val member = getById(memberId)
        member.unsuspend()
        return member
    }

    @Transactional
    fun withdraw(memberId: Long): Member {
        val member = getById(memberId)
        member.withdraw()
        eventPublisher.publishEvent(MemberWithdrawnEvent(member.id))
        return member
    }

    @Transactional
    fun promoteToMerchantOwner(memberId: Long): Member {
        val member = getById(memberId)
        // USER만 승격한다 — ADMIN을 MERCHANT_OWNER로 강등하거나 권한을 임의 변경하지 않는다.
        if (member.role == MemberRole.USER) member.role = MemberRole.MERCHANT_OWNER
        return member
    }
}
