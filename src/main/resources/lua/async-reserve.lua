-- KEYS: stock, product metadata, pending reservations, request ticket.
if redis.call('TYPE',KEYS[1]).ok ~= 'string' or redis.call('TYPE',KEYS[2]).ok ~= 'string' then return -2 end
local kind=redis.call('TYPE',KEYS[3]).ok
if kind ~= 'none' and kind ~= 'hash' then return -3 end
if redis.call('EXISTS',KEYS[4]) == 1 then return -3 end
local raw=redis.call('GET',KEYS[1])
local stock=tonumber(raw)
if not stock or stock < 0 or stock > 2147483647 or tostring(stock) ~= raw then return -3 end
if redis.call('HEXISTS',KEYS[3],ARGV[1]) == 1 then return -3 end
if stock == 0 then return 0 end
redis.call('HSET',KEYS[4],'payload',ARGV[2],'state','PENDING')
redis.call('HSET',KEYS[3],ARGV[1],'PENDING')
redis.call('DECR',KEYS[1])
return 1
