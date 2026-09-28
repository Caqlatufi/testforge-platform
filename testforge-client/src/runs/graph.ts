import type { Edge, Node } from "@vue-flow/core";

export function withVirtualTopology(nodes: Node[], edges: Edge[]): { nodes: Node[]; edges: Edge[] } {
  if (!nodes.length) return { nodes, edges };
  const incoming = new Map(nodes.map(node => [node.id, 0]));
  const outgoing = new Map(nodes.map(node => [node.id, 0]));
  for (const edge of edges) {
    incoming.set(edge.target, (incoming.get(edge.target) ?? 0) + 1);
    outgoing.set(edge.source, (outgoing.get(edge.source) ?? 0) + 1);
  }
  const entries = nodes.filter(node => incoming.get(node.id) === 0);
  const exits = nodes.filter(node => outgoing.get(node.id) === 0);
  const xs = nodes.map(node => node.position.x), ys = nodes.map(node => node.position.y);
  const minY = Math.min(...ys), maxY = Math.max(...ys);
  const centerX = (Math.min(...xs) + Math.max(...xs)) / 2;
  const virtualNodes: Node[] = [
    { id: "virtual:start", position: { x: centerX, y: minY - 150 }, data: { label: "开始\n调度入口" }, class: "virtual-node virtual-start" },
    { id: "virtual:summary", position: { x: centerX, y: maxY + 170 }, data: { label: "汇总\n等待全部出口" }, class: "virtual-node virtual-summary" },
    { id: "virtual:finish", position: { x: centerX, y: maxY + 310 }, data: { label: "完成\n生成结果" }, class: "virtual-node virtual-finish" },
  ];
  const virtual = (source: string, target: string): Edge => ({
    id: `virtual:${source}->${target}`,
    source,
    target,
    class: "virtual-edge",
    data: { displayOnly: true },
  });
  return {
    nodes: [...nodes, ...virtualNodes],
    edges: [
      ...edges,
      ...entries.map(node => virtual("virtual:start", node.id)),
      ...exits.map(node => virtual(node.id, "virtual:summary")),
      virtual("virtual:summary", "virtual:finish"),
    ],
  };
}
