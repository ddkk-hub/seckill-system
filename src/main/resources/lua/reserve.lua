-- Check types and values BEFORE mutation: Lua errors do not roll back writes.
if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('TYPE', KEYS[2]).ok ~= 'string' then return -2 end
local kind = redis.call('TYPE', KEYS[3]).ok
if kind ~= 'none' and kind ~= 'hash' then return -3 end
local raw = redis.call('GET', KEYS[1])
local stock = tonumber(raw)
if not string.match(raw, '^%d+$') or not stock or stock > 2147483647 then return -3 end
if redis.call('HEXISTS', KEYS[3], ARGV[1]) == 1 then return -3 end
if stock == 0 then return 0 end
redis.call('HSET', KEYS[3], ARGV[1], 'PENDING')
redis.call('DECR', KEYS[1])
return 1
