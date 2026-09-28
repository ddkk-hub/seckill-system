-- All buckets must allow the request; denied requests consume no tokens.
-- Each key is a hash; ARGV contains rate/second and capacity pairs.
local clock=redis.call('TIME')
local now=tonumber(clock[1])*1000+math.floor(tonumber(clock[2])/1000)
local tokens={}
local stamps={}
local retry=0
for i,key in ipairs(KEYS) do
    local kind=redis.call('TYPE',key).ok
    if kind~='none' and kind~='hash' then return -1 end
    local rate=tonumber(ARGV[i*2-1])
    local capacity=tonumber(ARGV[i*2])
    local saved=redis.call('HMGET',key,'tokens','time')
    local previous=tonumber(saved[1])
    local stamp=tonumber(saved[2])
    if kind=='hash' and (not previous or not stamp or previous<0) then return -1 end
    stamps[i]=math.max(now,stamp or now)
    tokens[i]=math.min(capacity,(previous or capacity)+math.max(0,now-(stamp or now))*rate/1000)
    if tokens[i]<1 then retry=math.max(retry,math.max(0,(stamp or now)-now)+math.ceil((1-tokens[i])*1000/rate)) end
end
if retry>0 then return retry end
for i,key in ipairs(KEYS) do
    local rate=tonumber(ARGV[i*2-1])
    local capacity=tonumber(ARGV[i*2])
    redis.call('HSET',key,'tokens',tostring(tokens[i]-1),'time',tostring(stamps[i]))
    redis.call('PEXPIRE',key,math.ceil(capacity*1000/rate)+60000)
end
return 0
