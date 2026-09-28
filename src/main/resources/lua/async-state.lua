-- Never downgrade a completed ticket when a delayed publisher callback arrives.
if redis.call('TYPE',KEYS[1]).ok ~= 'hash' then return 0 end
if redis.call('HGET',KEYS[1],'state') == 'SUCCESS' then return 0 end
redis.call('HSET',KEYS[1],'state',ARGV[1])
return 1
