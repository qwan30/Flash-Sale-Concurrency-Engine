export const dynamic = 'force-dynamic';

export async function GET() {
  const encoder = new TextEncoder();
  let counter = 0;

  const stream = new ReadableStream({
    async start(controller) {
      const interval = setInterval(() => {
        counter++;
        const data = {
          time: new Date().toISOString(),
          throughput: Math.floor(Math.random() * 500) + 100, // mock throughput
          success: Math.floor(Math.random() * 50) + 10,     // mock success
          failed: Math.floor(Math.random() * 5),           // mock failed
          elapsedMs: counter * 1000
        };

        try {
          controller.enqueue(
            encoder.encode(`data: ${JSON.stringify(data)}\n\n`)
          );
        } catch (err) {
          clearInterval(interval);
        }

        // stop mocking after some time
        if (counter > 60) {
          clearInterval(interval);
          try {
            controller.close();
          } catch (e) {}
        }
      }, 1000);

      // Handle stream abort
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
