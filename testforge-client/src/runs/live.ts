export type ConnectionState = 'connecting' | 'live' | 'reconnecting' | 'closed'

export interface EventSourceLike {
  onopen: ((event: Event) => void) | null
  onerror: ((event: Event) => void) | null
  addEventListener(type: string, listener: (event: MessageEvent) => void): void
  close(): void
}

export class RunEventClient<T> {
  private source: EventSourceLike | null = null
  constructor(private readonly runId: string, private readonly apply: (snapshot: T) => void,
    private readonly resync: () => Promise<T>, private readonly state: (value: ConnectionState) => void,
    private readonly create: (url: string) => EventSourceLike = url => new EventSource(url)) {}
  connect() {
    this.close(); this.state('connecting')
    const source = this.create(`/api/v1/runs/${this.runId}/events`); this.source = source
    const consume = (event: MessageEvent) => this.apply(JSON.parse(event.data) as T)
    for (const name of ['snapshot', 'update', 'resync']) source.addEventListener(name, consume)
    source.addEventListener('execution-event', () => { void this.correctSnapshot() })
    source.onopen = () => { this.state('live'); void this.correctSnapshot() }
    source.onerror = () => { this.state('reconnecting'); void this.correctSnapshot() }
  }
  close() { this.source?.close(); this.source = null; this.state('closed') }
  private async correctSnapshot() { this.apply(await this.resync()) }
}
