import { describe, expect, it } from 'vitest'
import type { Edge, Node } from '@vue-flow/core'
import { autoLayout, createsCycle } from './index'

const nodes: Node[] = [
  { id: 'a', position: { x: 0, y: 0 } },
  { id: 'b', position: { x: 0, y: 0 } },
  { id: 'c', position: { x: 0, y: 0 } },
]
const edges: Edge[] = [{ id: 'ab', source: 'a', target: 'b' }]

describe('workflow graph', () => {
  it('blocks a connection that closes a cycle', () => {
    expect(createsCycle(nodes, edges, 'b', 'a')).toBe(true)
    expect(createsCycle(nodes, edges, 'b', 'c')).toBe(false)
  })

  it('lays successors to the right of predecessors', () => {
    const laidOut = autoLayout(nodes, edges)
    expect(laidOut.find(node => node.id === 'b')!.position.x).toBeGreaterThan(laidOut.find(node => node.id === 'a')!.position.x)
  })
})
