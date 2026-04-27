param(
  [string]$BaseUrl = "http://localhost:1122",
  [long]$TicketItemId = 4,
  [int]$Stock = 1000,
  [string]$YearMonth = (Get-Date -Format "yyyyMM"),
  [string]$Strategy = "REDIS_LUA_WITH_COMPENSATION"
)

$headers = @{ "Content-Type" = "application/json" }

$resetBody = @{
  ticketItemId = $TicketItemId
  stock = $Stock
  yearMonth = $YearMonth
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri "$BaseUrl/admin/benchmarks/reset" -Headers $headers -Body $resetBody
Invoke-RestMethod -Method Post -Uri "$BaseUrl/admin/tickets/$TicketItemId/stock/warmup"

$orderBody = @{
  ticketItemId = $TicketItemId
  userId = 42
  quantity = 1
  strategy = $Strategy
  idempotencyKey = "smoke-$Strategy-$(Get-Date -Format yyyyMMddHHmmss)"
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri "$BaseUrl/orders" -Headers $headers -Body $orderBody
Invoke-RestMethod -Method Get -Uri "$BaseUrl/admin/benchmarks/consistency?ticketItemId=$TicketItemId&yearMonth=$YearMonth"
