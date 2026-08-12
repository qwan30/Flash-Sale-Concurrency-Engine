local delta = tonumber(ARGV[1])
local ticket_item_id = ARGV[2]
local fence_version = tonumber(ARGV[3])
local ttl_seconds = tonumber(ARGV[4]) or 604800

if delta == nil or delta == 0 or fence_version == nil then
    return 'CONFLICT'
end

local operation_state = redis.call('HGET', KEYS[2], 'state')
if not operation_state then
    return 'CONFLICT'
end

if operation_state then
    local stored_ticket_item_id = redis.call('HGET', KEYS[2], 'ticket_item_id')
    local stored_fence_version = tonumber(redis.call('HGET', KEYS[2], 'fence'))
    if operation_state == 'MIRRORED' then
        local same_mirror = stored_ticket_item_id == ticket_item_id
                and tonumber(redis.call('HGET', KEYS[2], 'delta')) == delta
                and stored_fence_version == fence_version
        if same_mirror then
            return 'REPLAYED:' .. redis.call('HGET', KEYS[2], 'stock_after')
        end
        return 'CONFLICT'
    end
    if operation_state == 'APPLIED' then
        local same_create = delta > 0
                and stored_ticket_item_id == ticket_item_id
                and tonumber(redis.call('HGET', KEYS[2], 'quantity')) == delta
                and stored_fence_version == fence_version
        if not same_create then
            return 'CONFLICT'
        end

        local admission_state = redis.call('HGET', KEYS[1], 'admission_state')
        local current_fence = tonumber(redis.call('HGET', KEYS[1], 'fence'))
        if admission_state ~= 'OPEN' or current_fence ~= fence_version then
            return 'STALE_FENCE'
        end

        local available = tonumber(redis.call('HGET', KEYS[1], 'available'))
        local initial = tonumber(redis.call('HGET', KEYS[1], 'initial'))
        if available == nil or initial == nil then
            return 'CONFLICT'
        end

        local stock_after = available + delta
        if stock_after < 0 or stock_after > initial then
            return 'CONFLICT'
        end

        redis.call('HSET', KEYS[1], 'available', stock_after)
        redis.call('HSET', KEYS[2],
                'state', 'MIRRORED',
                'stock_after', stock_after,
                'ticket_item_id', ticket_item_id,
                'quantity', math.abs(delta),
                'delta', delta,
                'fence', fence_version)
        redis.call('EXPIRE', KEYS[2], ttl_seconds)
        return 'MIRRORED:' .. stock_after
    end
    return 'CONFLICT'
end
