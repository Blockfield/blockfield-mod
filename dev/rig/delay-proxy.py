"""TCP proxy 127.0.0.1:25600 -> 127.0.0.1:<port, default 25599> adding a one-way delay (ms) to every chunk, both directions.
python3 delay-proxy.py <one-way ms> [server port]"""

import asyncio
import sys

DELAY = float(sys.argv[1]) / 1000 if len(sys.argv) > 1 else 0.06
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 25599


async def pump(r, w):
    # Delay line: each chunk leaves DELAY after it arrived. Sleeping between read and write instead would queue
    # the delays behind each other and the lag would grow with traffic.
    loop = asyncio.get_running_loop()
    queue = asyncio.Queue()

    async def writer():
        while (item := await queue.get()) is not None:
            due, data = item
            await asyncio.sleep(max(0.0, due - loop.time()))
            w.write(data)
            await w.drain()

    task = asyncio.create_task(writer())
    try:
        while data := await r.read(65536):
            queue.put_nowait((loop.time() + DELAY, data))
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        queue.put_nowait(None)
        try:
            await task
        except ConnectionError:
            pass
        w.close()


async def handle(cr, cw):
    sr, sw = await asyncio.open_connection("127.0.0.1", PORT)
    await asyncio.gather(pump(cr, sw), pump(sr, cw))


async def main():
    srv = await asyncio.start_server(handle, "127.0.0.1", 25600)
    print("proxy up, one-way delay", DELAY, flush=True)
    async with srv:
        await srv.serve_forever()


asyncio.run(main())
