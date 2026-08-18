export const dynamic = 'force-dynamic';

export async function GET() {
  const encoder = new TextEncoder();
  let counter = 0;
  let prevTotalOrders = 0;

  const stream = new ReadableStream({
    async start(controller) {
      const interval = setInterval(async () => {
        counter++;
        let throughput = 0;
        let success = 0;
        let failed = 0;

        try {
          // Poll real metrics from Spring Boot Actuator if reachable
          const backendUrl = process.env.BACKEND_API_URL || 'http://localhost:1122';
          const res = await fetch(`${backendUrl}/actuator/prometheus`, {
            cache: 'no-store',
            signal: AbortSignal.timeout(800)
          });
          
          if (res.ok) {
            const text = await res.text();
            
            // Extract flashsale_orders_total metrics
            const successMatch = text.match(/flashsale_orders_total\{[^}]*result="success"[^}]*\}\s+([\d.]+)/);
            const failedMatch = text.match(/flashsale_orders_total\{[^}]*result="failed"[^}]*\}\s+([\d.]+)/);
            
            const currentSuccess = successMatch ? Math.floor(parseFloat(successMatch[1])) : 0;
            const currentFailed = failedMatch ? Math.floor(parseFloat(failedMatch[1])) : 0;
            const totalOrders = currentSuccess + currentFailed;
            
            throughput = Math.max(0, totalOrders - prevTotalOrders);
            prevTotalOrders = totalOrders;
            success = currentSuccess;
            failed = currentFailed;
          }
        } catch {
          // Keep stream alive with idle status if backend is starting or offline
          throughput = 0;
        }

        const data = {
          time: new Date().toISOString(),
          throughput,
          success,
          failed,
          elapsedMs: counter * 1000
        };

        try {
          controller.enqueue(
            encoder.encode(`data: ${JSON.stringify(data)}\n\n`)
          );
        } catch {
          clearInterval(interval);
        }

        if (counter > 120) {
          clearInterval(interval);
          try {
            controller.close();
          } catch {}
        }
      }, 1000);

      return () => {
        clearInterval(interval);
      };
    }
  });

  return new Response(stream, {
    headers: {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      'Connection': 'keep-alive',
    },
  });
}
