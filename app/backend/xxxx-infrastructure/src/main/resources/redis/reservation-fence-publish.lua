local fence_version = tonumber(ARGV[1])
local admission_state = ARGV[2]

if fence_version == nil or (admission_state ~= 'OPEN' and admission_state ~= 'DRAINING' and admission_state ~= 'CLOSED') then
    return 'CONFLICT'
end

local current_fence = tonumber(redis.call('HGET', KEYS[1], 'fence'))
if current_fence == nil or fence_version <= current_fence then
    return 'STALE_FENCE'
end

redis.call('HSET', KEYS[1], 'fence', fence_version, 'admission_state', admission_state)
return 'PUBLISHED'
