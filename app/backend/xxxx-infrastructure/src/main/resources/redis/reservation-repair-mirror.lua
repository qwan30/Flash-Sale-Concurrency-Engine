local fence_version = tonumber(ARGV[1])
local initial = tonumber(ARGV[2])
local available = tonumber(ARGV[3])
local reserved = tonumber(ARGV[4])
local confirmed = tonumber(ARGV[5])
local disposition = ARGV[6]
local ttl_seconds = tonumber(ARGV[7]) or 604800

local existing_state = redis.call('HGET', KEYS[2], 'state')
if existing_state == 'REPAIRED' then
    return 'REPLAYED'
end

if fence_version == nil or initial == nil or available == nil or reserved == nil or confirmed == nil
        or disposition == nil or string.len(disposition) == 0 or string.len(disposition) > 64 then
    return 'CONFLICT'
end
if initial < 0 or available < 0 or reserved < 0 or confirmed < 0
        or available > initial or initial ~= available + reserved + confirmed then
    return 'CONFLICT'
end

if redis.call('HGET', KEYS[1], 'admission_state') ~= 'CLOSED' then
    return 'REPAIR_REQUIRED'
end
if tonumber(redis.call('HGET', KEYS[1], 'fence')) ~= fence_version then
    return 'STALE_FENCE'
end

redis.call('HSET', KEYS[1],
        'initial', initial,
        'available', available,
        'reserved', reserved,
        'confirmed', confirmed)
redis.call('HSET', KEYS[2],
        'state', 'REPAIRED',
        'disposition', disposition,
        'stock_after', available,
        'fence', fence_version)
redis.call('EXPIRE', KEYS[2], ttl_seconds)
return 'REPAIRED'
