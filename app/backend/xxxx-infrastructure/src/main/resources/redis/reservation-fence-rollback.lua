local previous_fence = tonumber(ARGV[1])
local fenced_version = tonumber(ARGV[2])

if previous_fence == nil or fenced_version == nil or fenced_version <= previous_fence then
    return 'CONFLICT'
end

local current_fence = tonumber(redis.call('HGET', KEYS[1], 'fence'))
local current_state = redis.call('HGET', KEYS[1], 'admission_state')
if current_fence == previous_fence and current_state == 'OPEN' then
    return 'REPLAYED'
end
if current_fence ~= fenced_version or current_state ~= 'DRAINING' then
    return 'CONFLICT'
end

redis.call('HSET', KEYS[1], 'fence', previous_fence, 'admission_state', 'OPEN')
return 'ROLLED_BACK'
