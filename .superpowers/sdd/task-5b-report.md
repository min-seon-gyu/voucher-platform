# Task 5b — Review Fix Report

## Fix 1: Cross-member 403 authorization test

**File created:** `src/test/kotlin/com/commerce/integration/PointControllerAuthorizationTest.kt`

Four integration tests using `IntegrationTestSupport` + `@AutoConfigureMockMvc`, minting JWTs via `JwtTokenProvider.generateToken(memberId, role)`:

| Test | Scenario | Expected | Result |
|------|----------|----------|--------|
| `cross-member access returns 403 ACCESS_DENIED` | Auth as memberId=9001, request `/members/9002/points` | 403 + `code=ACCESS_DENIED` | PASS |
| `no token returns 401 UNAUTHORIZED` | No `Authorization` header | 401 | PASS |
| `own points returns 200 with balance and history` | Auth as 9100, seeds `PointAccount(balance=500)` + `PointTransaction(EARN,500)`, request `/members/9100/points` | 200 + balance/history | PASS |
| `own points returns 404 when account does not exist` | Auth as 9999 (no seeded account) | 404 + `code=POINT_ACCOUNT_NOT_FOUND` | PASS |

The cross-member guard (`SecurityUtils.currentMemberId() != memberId → ACCESS_DENIED`) is verified — guard is NOT broken.

## Fix 2: Stable date formatting

**File modified:** `src/main/kotlin/com/commerce/point/interfaces/dto/PointResponse.kt`

Changed `t.createdAt.toString()` → `t.createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)`.

`ISO_LOCAL_DATE_TIME` always produces `2026-06-30T02:52:58.373` (never drops trailing zero seconds), making the API contract stable.

## Test results

- `PointControllerAuthorizationTest`: **4/4 PASS**
- Full suite: 112 PASS, 3 FAIL (pre-existing: `PointEarnIntegrationTest`, `PointReconciliationTest`, `VoucherExpiryTest` — all pass in isolation; DB-ordering issue unrelated to this task)

## Files changed

- `src/main/kotlin/com/commerce/point/interfaces/dto/PointResponse.kt`
- `src/test/kotlin/com/commerce/integration/PointControllerAuthorizationTest.kt`
