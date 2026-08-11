local fence_version = tonumber(ARGV[1])
local admission_state = ARGV[2]

if fence_version == nil or (admission_state ~= 'OPEN' and admission_state ~= 'DRAINING' and admission_state ~= 'CLOSED') then
    return 'CONFLICT'
end

local current_fence = tonumber(redis.call('HGET', KEYS[1], 'fence'))
local current_state = redis.call('HGET', KEYS[1], 'admission_state')
if current_fence == fence_version and current_state == 'DRAINING' and admission_state == 'CLOSED' then
    redis.call('HSET', KEYS[1], 'admission_state', 'CLOSED')
    return 'PUBLISHED'
end
if current_fence == fence_version and current_state == 'CLOSED' and admission_state == 'DRAINING' then
    return 'REPLAYED'
end
if current_fence == fence_version and current_state == 'CLOSED' and admission_state == 'OPEN' then
    redis.call('HSET', KEYS[1], 'admission_state', 'OPEN')
    return 'PUBLISHED'
end
if current_fence == nil or fence_version < current_fence then
    return 'STALE_FENCE'
end

if fence_version == current_fence then
    if current_state == admission_state then
        return 'REPLAYED'
    end
    return 'STALE_FENCE'
end

if admission_state ~= 'DRAINING' then
    return 'CONFLICT'
end

redis.call('HSET', KEYS[1], 'fence', fence_version, 'admission_state', admission_state)
return 'PUBLISHED'
