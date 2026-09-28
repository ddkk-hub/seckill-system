if redis.call('TYPE', KEYS[1]).ok ~= 'string' or redis.call('TYPE', KEYS[2]).ok ~= 'string' then return nil end
local raw = redis.call('GET', KEYS[1])
local stock = tonumber(raw)
if not string.match(raw, '^%d+$') or not stock or stock > 2147483647 then return nil end
-- Preserve JSON numbers (IDs and prices) without a Lua floating-point round trip.
return raw .. '\n' .. redis.call('GET', KEYS[2])
