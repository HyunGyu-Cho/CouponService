-- 쿠폰 발급의 중복 확인과 재고 감소를 하나의 원자 연산으로 묶는다.
-- Redis 는 스크립트 실행 중 다른 명령을 끼워 넣지 않으므로,
-- "확인하고 감소시키는" 사이에 다른 요청이 들어올 틈이 없다.
--
-- KEYS[1] = coupon:{couponId}:stock   (문자열, 잔여 수량)
-- KEYS[2] = coupon:{couponId}:issued  (Set, 발급받은 userId)
-- ARGV[1] = userId
--
-- 반환값
--   -1  이미 발급받은 사용자
--   -2  매진
--   -3  아직 초기화되지 않음 (오류가 아니라 초기화 신호)
--   0 이상  발급 승인, 값은 감소 후 잔여 수량

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

local stock = redis.call('GET', KEYS[1])

if stock == false then
    return -3
end

if tonumber(stock) <= 0 then
    return -2
end

redis.call('SADD', KEYS[2], ARGV[1])

return redis.call('DECR', KEYS[1])
