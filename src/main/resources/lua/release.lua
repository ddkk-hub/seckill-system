-- Only a known reservation can be released, at most once; never recreate stock.
if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('TYPE', KEYS[2]).ok ~= 'string' then return -2 end
local kind = redis.call('TYPE', KEYS[3]).ok
if kind == 'none' then return 0 end
if kind ~= 'hash' then return -3 end
local raw = redis.call('GET', KEYS[1])
local stock = tonumber(raw)
if not string.match(raw, '^%d+$') or not stock or stock > 2147483647 then return -3 end
if redis.call('HEXISTS', KEYS[3], ARGV[1]) == 0 then return 0 end
if stock >= 2147483647 then return -3 end
redis.call('INCR', KEYS[1])
redis.call('HDEL', KEYS[3], ARGV[1])
return 1
