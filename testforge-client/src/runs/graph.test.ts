import { describe, expect, it } from "vitest";
import { withVirtualTopology } from "./graph";

describe("withVirtualTopology", () => {
  it("explains independent nodes as a parallel fan-out and fan-in without changing real edges", () => {
    const topology = withVirtualTopology([
      { id: "case-1", position: { x: 0, y: 0 }, data: { label: "正常命中" } },
      { id: "case-2", position: { x: 0, y: 120 }, data: { label: "方向偏离" } },
    ], []);

    expect(topology.nodes.map(node => node.id)).toEqual([
      "case-1", "case-2", "virtual:start", "virtual:summary", "virtual:finish",
    ]);
    expect(topology.edges).toHaveLength(5);
    expect(topology.edges.every(edge => edge.class === "virtual-edge")).toBe(true);
    expect(topology.edges.map(edge => `${edge.source}->${edge.target}`)).toContain("virtual:start->case-1");
    expect(topology.edges.map(edge => `${edge.source}->${edge.target}`)).toContain("case-2->virtual:summary");
  });

  it("keeps saved dependencies solid and only decorates graph entries and exits", () => {
    const topology = withVirtualTopology([
      { id: "a", position: { x: 0, y: 0 }, data: {} },
      { id: "b", position: { x: 200, y: 0 }, data: {} },
    ], [{ id: "real", source: "a", target: "b" }]);

    expect(topology.edges.find(edge => edge.id === "real")?.class).toBeUndefined();
    expect(topology.edges.filter(edge => edge.class === "virtual-edge")).toHaveLength(3);
  });
});
