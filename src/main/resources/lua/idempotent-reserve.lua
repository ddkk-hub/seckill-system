-- KEYS: stock, product metadata, pending, request ticket, permanent idempotency marker.
-- ARGV: requestId, immutable message JSON, fingerprint (userId:productId).
local markerType=redis.call('TYPE',KEYS[5]).ok
if markerType~='none' and markerType~='string' then return -3 end
local fingerprint=redis.call('GET',KEYS[5])
if fingerprint then
    if fingerprint~=ARGV[3] then return -4 end
    return 2
end
if redis.call('TYPE',KEYS[1]).ok~='string' or redis.call('TYPE',KEYS[2]).ok~='string' then return -2 end
local kind=redis.call('TYPE',KEYS[3]).ok
if kind~='none' and kind~='hash' then return -3 end
if redis.call('EXISTS',KEYS[4])==1 then return -3 end
local raw=redis.call('GET',KEYS[1])
local stock=tonumber(raw)
if not stock or stock<0 or stock>2147483647 or tostring(stock)~=raw then return -3 end
if redis.call('HEXISTS',KEYS[3],ARGV[1])==1 then return -3 end
if stock==0 then return 0 end
-- No expiry: expiring this marker would allow the same operation to reserve again.
redis.call('SET',KEYS[5],ARGV[3])
redis.call('HSET',KEYS[4],'payload',ARGV[2],'state','PENDING')
redis.call('HSET',KEYS[3],ARGV[1],'PENDING')
redis.call('DECR',KEYS[1])
return 1
