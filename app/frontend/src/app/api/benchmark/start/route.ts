import { NextRequest, NextResponse } from 'next/server';
import { spawn } from 'child_process';
import path from 'path';

const ALLOWED_STRATEGIES = [
  'PESSIMISTIC_LOCK',
  'OPTIMISTIC_LOCK',
  'REDIS_LUA_ONLY',
  'REDIS_LUA_WITH_COMPENSATION'
];

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const { strategy, threads, totalRequests } = body;

    if (!ALLOWED_STRATEGIES.includes(strategy)) {
      return NextResponse.json({ error: 'Invalid strategy' }, { status: 400 });
    }

    const safeThreads = parseInt(threads, 10);
    const safeTotalRequests = parseInt(totalRequests, 10);

    if (isNaN(safeThreads) || safeThreads <= 0 || safeThreads > 1000) {
      return NextResponse.json({ error: 'Invalid threads' }, { status: 400 });
    }

    if (isNaN(safeTotalRequests) || safeTotalRequests <= 0 || safeTotalRequests > 100000) {
      return NextResponse.json({ error: 'Invalid totalRequests' }, { status: 400 });
    }

    const scriptPath = path.resolve(process.cwd(), '../../benchmark/run-jmeter.ps1');

    const ps = spawn('powershell.exe', [
      '-NoProfile',
      '-ExecutionPolicy', 'Bypass',
      '-File', scriptPath,
      '-Strategy', strategy,
      '-Threads', safeThreads.toString(),
      '-TotalRequests', safeTotalRequests.toString()
    ]);

    ps.stdout.on('data', (data) => {
      console.log(`JMeter stdout: ${data}`);
    });

    ps.stderr.on('data', (data) => {
      console.error(`JMeter stderr: ${data}`);
    });

    ps.on('close', (code) => {
      console.log(`JMeter process exited with code ${code}`);
    });

    return NextResponse.json({ message: 'Benchmark started successfully' });
  } catch (error) {
    console.error('Error starting benchmark:', error);
    return NextResponse.json({ error: 'Failed to start benchmark' }, { status: 500 });
  }
}
