import type { Edge, Node } from '@vue-flow/core'
import type { FeatureModule } from '../common'

export const workflowsFeature: FeatureModule = {
  id: 'workflows',
  title: 'Workflow 编排',
  description: '编辑、校验并发布可执行 DAG 快照。',
}

export type WorkflowNodeType = 'CASE' | 'SUITE' | 'FIXTURE' | 'SUBFLOW'
export type WorkflowNodeData = {
  label: string
  type: WorkflowNodeType
  referenceId: string
  referenceVersion: number
  required: boolean
  timeoutSeconds: number
  parameterOverrides: string
}

export interface WorkflowView {
  id: string
  projectId: string
  targetId: string
  name: string
  draftRevision: number
  latestVersion: number
  createdAt?: string
  updatedAt?: string
  draft: {
    nodes: Array<{ id: string; type: WorkflowNodeType; referenceId: string; referenceVersion: number; required: boolean; timeoutSeconds: number; parameterOverrides: Record<string, unknown>; positionX: number; positionY: number }>
    edges: Array<{ predecessorNodeId: string; successorNodeId: string; condition: 'ON_SUCCESS' | 'ON_COMPLETION' }>
  }
}

export function createsCycle(nodes: Node[], edges: Edge[], source: string, target: string): boolean {
  if (source === target) return true
  const outgoing = new Map<string, string[]>()
  for (const node of nodes) outgoing.set(node.id, [])
  for (const edge of edges) outgoing.get(edge.source)?.push(edge.target)
  outgoing.get(source)?.push(target)
  const stack = [target], visited = new Set<string>()
  while (stack.length) {
    const current = stack.pop()!
    if (current === source) return true
    if (visited.has(current)) continue
    visited.add(current)
    stack.push(...(outgoing.get(current) ?? []))
  }
  return false
}

export function autoLayout(nodes: Node[], edges: Edge[]): Node[] {
  const indegree = new Map(nodes.map(node => [node.id, 0]))
  for (const edge of edges) indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1)
  const levels = new Map<string, number>(), queue = nodes.filter(node => indegree.get(node.id) === 0).map(node => node.id)
  while (queue.length) {
    const id = queue.shift()!, level = levels.get(id) ?? 0
    for (const edge of edges.filter(item => item.source === id)) {
      levels.set(edge.target, Math.max(levels.get(edge.target) ?? 0, level + 1))
      indegree.set(edge.target, (indegree.get(edge.target) ?? 1) - 1)
      if (indegree.get(edge.target) === 0) queue.push(edge.target)
    }
  }
  const row = new Map<number, number>()
  return nodes.map(node => {
    const level = levels.get(node.id) ?? 0, index = row.get(level) ?? 0
    row.set(level, index + 1)
    return { ...node, position: { x: 80 + level * 260, y: 60 + index * 130 } }
  })
}
