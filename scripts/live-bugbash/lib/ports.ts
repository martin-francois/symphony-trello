import {existsSync, readFileSync} from "node:fs";
import {createServer, type Socket} from "node:net";

const ATTEMPTS = 200;

/** A free loopback port that no connected workflow in `manifestPath` has reserved. */
export async function freePort(manifestPath: string | null = null, extraReserved: Iterable<number> = []): Promise<number> {
  const reserved = new Set<number>([...reservedPorts(manifestPath), ...extraReserved]);
  for (let attempt = 0; attempt < ATTEMPTS; attempt++) {
    const port = await ephemeralPort();
    if (!reserved.has(port)) {
      return port;
    }
  }
  throw new Error("no free unreserved port");
}

export function reservedPorts(manifestPath: string | null): number[] {
  if (manifestPath === null || !existsSync(manifestPath)) {
    return [];
  }
  const manifest = JSON.parse(readFileSync(manifestPath, "utf8")) as {boards?: Array<{serverPort?: number}>};
  return (manifest.boards ?? []).map((board) => board.serverPort).filter((port): port is number => typeof port === "number");
}

/**
 * Holds a port open until `release` is called, to simulate an occupied port. Health probes from the
 * product may connect to it, so release destroys open connections instead of waiting for them.
 */
export async function occupyPort(port: number): Promise<{release: () => Promise<void>}> {
  const sockets = new Set<Socket>();
  const server = createServer((socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", resolve);
  });
  return {
    release: () =>
      new Promise<void>((resolve) => {
        for (const socket of sockets) {
          socket.destroy();
        }
        server.close(() => resolve());
      }),
  };
}

function ephemeralPort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address !== null ? address.port : 0;
      server.close(() => resolve(port));
    });
  });
}
