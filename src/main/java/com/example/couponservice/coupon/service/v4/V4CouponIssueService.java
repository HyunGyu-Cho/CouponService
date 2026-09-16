package com.example.couponservice.coupon.service.v4;

import com.example.couponservice.coupon.entity.Coupon;
import com.example.couponservice.coupon.entity.CouponIssue;
import com.example.couponservice.coupon.exception.CouponErrorCode;
import com.example.couponservice.coupon.exception.CouponException;
import com.example.couponservice.coupon.repository.CouponIssueRepository;
import com.example.couponservice.coupon.repository.CouponRepository;
import com.example.couponservice.coupon.service.CouponIssueUseCase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V4: Redis 원자 연산 발급.
 * 중복 확인, 재고 확인, 재고 감소, 발급 사용자 등록을 Lua 스크립트 하나로 묶어
 * 발급 경로에서 coupon 행 갱신을 없앤다. V3까지 병목이던 그 행이다.
 *
 * <pre>
 * 쿠폰 조회와 발급 기간 확인 (DB 일반 조회, 잠금 없음)
 * -&gt; Lua 스크립트 (중복 확인 -&gt; 재고 확인 -&gt; 재고 감소 -&gt; 사용자 등록)
 * -&gt; 미초기화면 DB에서 Redis 상태를 복원하고 한 번만 재시도
 * -&gt; CouponIssue 저장 (즉시 flush)
 * -&gt; 저장 실패 시 실패 원인에 따라 Redis 보상
 * </pre>
 *
 * 실시간 재고는 Redis가 들고 있으므로 coupon.remaining_quantity는 발급 경로에서 갱신하지 않는다.
 * 격리 수준은 DB 기본값을 쓴다. V3가 READ COMMITTED로 낮춘 이유였던 "읽은 뒤 바뀐 행의 UPDATE"가
 * V4에는 없기 때문이다. 설계 근거와 한계는 docs/v4/development-guide.md에 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "coupon.issue",
        name = "version",
        havingValue = "v4"
)
public class V4CouponIssueService implements CouponIssueUseCase {

    private static final long DUPLICATE_ISSUE = -1L;
    private static final long SOLD_OUT = -2L;
    private static final long NOT_INITIALIZED = -3L;

    private static final int ISSUED_RESTORE_BATCH_SIZE = 1_000;

    private final CouponRepository couponRepository;
    private final CouponIssueRepository couponIssueRepository;
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> issueCouponScript;

    @Override
    @Transactional
    public CouponIssue issueCoupon(
            Long couponId,
            Long userId
    ) {
        LocalDateTime issuedAt = LocalDateTime.now();

        Coupon coupon = getCouponById(couponId);
        coupon.validateIssuablePeriod(issuedAt);

        long issueResult = issueInRedis(couponId, userId);

        if (issueResult == NOT_INITIALIZED) {
            initializeRedisState(coupon);
            issueResult = issueInRedis(couponId, userId);
        }

        validateIssueApproved(issueResult, couponId);

        return saveCouponIssue(coupon, userId, issuedAt);
    }

    private Coupon getCouponById(Long couponId) {
        return couponRepository.findById(couponId)
                .orElseThrow(() ->
                        new CouponException(CouponErrorCode.COUPON_NOT_FOUND)
                );
    }

    private long issueInRedis(
            Long couponId,
            Long userId
    ) {
        Long issueResult = redisTemplate.execute(
                issueCouponScript,
                List.of(V4RedisKeys.toStockKey(couponId), V4RedisKeys.toIssuedKey(couponId)),
                String.valueOf(userId)
        );

        if (issueResult == null) {
            throw new IllegalStateException(
                    "Redis 발급 스크립트가 값을 반환하지 않았습니다. couponId=" + couponId
            );
        }

        return issueResult;
    }

    /**
     * 비어 있는 Redis 상태를 DB에서 복원한다. Lua가 -3을 반환한 요청이 수행한다.
     * 두 요청이 동시에 수행해도 SADD는 여러 번 해도 결과가 같고 SET NX는 한쪽만 성공한다.
     */
    private void initializeRedisState(Coupon coupon) {
        List<Long> issuedUserIds =
                couponIssueRepository.findUserIdsByCouponId(coupon.getId());

        restoreIssuedUsers(coupon.getId(), issuedUserIds);

        int stock = Math.max(
                coupon.getTotalQuantity() - issuedUserIds.size(),
                0
        );

        // stock 키를 반드시 마지막에 만든다.
        // Lua는 이 키가 있어야 발급을 승인하므로, 키의 존재 자체가 "issued 복원 완료" 표시가 된다.
        // 먼저 만들면 issued가 비어 있는 틈에 기존 발급자가 중복 검사를 통과한다.
        redisTemplate.opsForValue().setIfAbsent(
                V4RedisKeys.toStockKey(coupon.getId()),
                String.valueOf(stock)
        );
    }

    private void restoreIssuedUsers(
            Long couponId,
            List<Long> issuedUserIds
    ) {
        String issuedKey = V4RedisKeys.toIssuedKey(couponId);

        // 발급 이력이 쌓인 쿠폰은 한 번에 보내면 명령 하나가 지나치게 커진다.
        for (int start = 0; start < issuedUserIds.size(); start += ISSUED_RESTORE_BATCH_SIZE) {
            int end = Math.min(
                    start + ISSUED_RESTORE_BATCH_SIZE,
                    issuedUserIds.size()
            );

            String[] userIds = issuedUserIds.subList(start, end)
                    .stream()
                    .map(String::valueOf)
                    .toArray(String[]::new);

            redisTemplate.opsForSet().add(issuedKey, userIds);
        }
    }

    private void validateIssueApproved(
            long issueResult,
            Long couponId
    ) {
        if (issueResult == DUPLICATE_ISSUE) {
            throw new CouponException(CouponErrorCode.DUPLICATE_ISSUE);
        }

        if (issueResult == SOLD_OUT) {
            throw new CouponException(CouponErrorCode.SOLD_OUT);
        }

        // 초기화를 마치고 재시도했는데도 미초기화면 Redis 상태를 만들지 못한 것이다.
        if (issueResult == NOT_INITIALIZED) {
            throw new IllegalStateException(
                    "Redis 재고 초기화에 실패했습니다. couponId=" + couponId
            );
        }
    }

    /**
     * Redis가 승인한 발급을 DB에 남긴다. 실패하면 원인에 따라 Redis를 되돌린다.
     * 되돌려야 하는 것은 "Redis가 승인했는데 DB에 반영되지 않은 것"뿐이다.
     */
    private CouponIssue saveCouponIssue(
            Coupon coupon,
            Long userId,
            LocalDateTime issuedAt
    ) {
        try {
            CouponIssue couponIssue = CouponIssue.create(coupon, userId, issuedAt);

            // saveAndFlush로 INSERT를 이 메서드 안에서 실행한다.
            // save만 하면 INSERT가 커밋 시점으로 밀려 UNIQUE 위반을 여기서 잡지 못하고,
            // 아래 보상이 아예 실행되지 않는다.
            return couponIssueRepository.saveAndFlush(couponIssue);
        } catch (DataIntegrityViolationException e) {
            // 그 사용자의 발급 행이 이미 있다. 발급 수는 늘지 않았는데 재고만 줄었으므로 재고만 되돌린다.
            // issued에서 지우면 안 된다. 실제 보유자가 사라져 재시도마다 다시 승인받고 다시 실패한다.
            increaseStock(coupon.getId());

            log.warn(
                    "Redis 승인 뒤 UNIQUE 위반. Redis issued 집합이 DB보다 뒤처졌다. couponId={}, userId={}",
                    coupon.getId(),
                    userId
            );

            throw new CouponException(CouponErrorCode.DUPLICATE_ISSUE);
        } catch (RuntimeException e) {
            // 발급 행이 남지 않았으므로 승인 자체를 무효로 되돌린다.
            increaseStock(coupon.getId());
            removeIssuedUser(coupon.getId(), userId);

            throw e;
        }
    }

    // INCR로 되돌리는 것은 stock 키가 사라지지 않는다는 전제 위에 있다.
    // 키가 없으면 INCR이 1부터 새로 만들어 재고가 어긋나므로 Redis는 noeviction으로 띄운다.
    private void increaseStock(Long couponId) {
        redisTemplate.opsForValue().increment(V4RedisKeys.toStockKey(couponId));
    }

    private void removeIssuedUser(
            Long couponId,
            Long userId
    ) {
        redisTemplate.opsForSet().remove(
                V4RedisKeys.toIssuedKey(couponId),
                String.valueOf(userId)
        );
    }
}
