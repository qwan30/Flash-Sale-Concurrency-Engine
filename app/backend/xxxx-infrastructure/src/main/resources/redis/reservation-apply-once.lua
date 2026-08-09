local quantity = tonumber(ARGV[1])
local ticket_item_id = ARGV[2]
local fence_version = tonumber(ARGV[3])
local ttl_seconds = tonumber(ARGV[4]) or 604800

if quantity == nil or quantity < 1 or quantity > 4 or fence_version == nil then
    return 'CONFLICT'
end

local existing_state = redis.call('HGET', KEYS[2], 'state')
if existing_state then
    local same_operation = redis.call('HGET', KEYS[2], 'ticket_item_id') == ticket_item_id
            and tonumber(redis.call('HGET', KEYS[2], 'quantity')) == quantity
            and tonumber(redis.call('HGET', KEYS[2], 'fence')) == fence_version
    if not same_operation then
        return 'CONFLICT'
    end

    local stock_after = redis.call('HGET', KEYS[2], 'stock_after')
    if existing_state == 'APPLIED' or existing_state == 'SOLD_OUT' then
        return 'REPLAYED:' .. stock_after
    end
    if existing_state == 'STALE_FENCE' then
        return 'STALE_FENCE'
    end
    return 'CONFLICT'
end

local admission_state = redis.call('HGET', KEYS[1], 'admission_state')
local current_fence = tonumber(redis.call('HGET', KEYS[1], 'fence'))
if admission_state ~= 'OPEN' or current_fence ~= fence_version then
    redis.call('HSET', KEYS[2],
            'state', 'STALE_FENCE',
            'ticket_item_id', ticket_item_id,
            'quantity', quantity,
            'fence', fence_version)
    redis.call('EXPIRE', KEYS[2], ttl_seconds)
    return 'STALE_FENCE'
end

local available = tonumber(redis.call('HGET', KEYS[1], 'available'))
if available == nil then
    return 'CONFLICT'
end
if available < quantity then
    redis.call('HSET', KEYS[2],
            'state', 'SOLD_OUT',
            'stock_after', available,
            'ticket_item_id', ticket_item_id,
            'quantity', quantity,
            'fence', fence_version)
    redis.call('EXPIRE', KEYS[2], ttl_seconds)
    return 'SOLD_OUT:' .. available
end

local stock_after = available - quantity
redis.call('HSET', KEYS[1], 'available', stock_after)
redis.call('HSET', KEYS[2],
        'state', 'APPLIED',
        'stock_after', stock_after,
        'ticket_item_id', ticket_item_id,
        'quantity', quantity,
        'fence', fence_version)
redis.call('EXPIRE', KEYS[2], ttl_seconds)
return 'APPLIED:' .. stock_after
