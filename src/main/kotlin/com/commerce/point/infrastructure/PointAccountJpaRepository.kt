package com.commerce.point.infrastructure

import com.commerce.point.domain.PointAccount
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

interface PointAccountJpaRepository : JpaRepository<PointAccount, Long> {

    fun findByMemberId(memberId: Long): PointAccount?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PointAccount p WHERE p.memberId = :memberId")
    fun findByMemberIdForUpdate(@Param("memberId") memberId: Long): PointAccount?

    @Query("SELECT COALESCE(SUM(p.balance), 0.0) FROM PointAccount p")
    fun sumAllBalances(): BigDecimal

    // 정합성 회귀 테스트/운영 보정용 — 캐시 잔액을 직접 덮어쓴다(@Transactional 내에서만 호출).
    @Transactional
    @Modifying
    @Query("UPDATE PointAccount p SET p.balance = :balance WHERE p.memberId = :memberId")
    fun overwriteBalance(@Param("memberId") memberId: Long, @Param("balance") balance: BigDecimal)
}
