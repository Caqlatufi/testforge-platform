import { describe, expect, it, vi } from 'vitest'
import { RunEventClient, type EventSourceLike } from './live'

class FakeEventSource implements EventSourceLike {
  onopen: ((event: Event) => void) | null = null; onerror: ((event: Event) => void) | null = null; closed = false
  listeners = new Map<string, (event: MessageEvent) => void>()
  addEventListener(type: string, listener: (event: MessageEvent) => void) { this.listeners.set(type, listener) }
  close() { this.closed = true }
  emit(type: string, value: unknown) { this.listeners.get(type)?.({ data: JSON.stringify(value) } as MessageEvent) }
}
describe('RunEventClient', () => {
  it('applies SSE snapshots and corrects missed state after disconnect', async () => {
    const source = new FakeEventSource(), apply = vi.fn(), states: string[] = []
    const resync = vi.fn().mockResolvedValue({ version: 3, state: 'SUCCEEDED' })
    const client = new RunEventClient('run-1', apply, resync, state => states.push(state), () => source)
    client.connect(); source.emit('snapshot', { version: 1, state: 'RUNNING' }); source.onerror?.(new Event('error'))
    await Promise.resolve(); await Promise.resolve()
    expect(apply).toHaveBeenCalledWith({ version: 1, state: 'RUNNING' })
    expect(resync).toHaveBeenCalledOnce()
    expect(apply).toHaveBeenLastCalledWith({ version: 3, state: 'SUCCEEDED' })
    expect(states).toContain('reconnecting')
    client.close(); expect(source.closed).toBe(true)
  })
})
