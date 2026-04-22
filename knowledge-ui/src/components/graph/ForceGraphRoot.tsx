import { createElement } from 'react'
import ForceGraph2D from 'react-force-graph-2d'
import type { GraphLink, GraphNode } from '../../graph/types'

export function ForceGraphRoot(props: {
  nodes: GraphNode[]
  links: GraphLink[]
  onNodeHover: (id: string | null) => void
  onNodeClick: (id: string) => void
}) {
  return createElement(ForceGraph2D, {
    graphData: { nodes: props.nodes, links: props.links },
    nodeLabel: 'label',
    nodeAutoColorBy: 'realm',
    linkColor: (link: GraphLink) => link.color,
    onNodeHover: (node: GraphNode | null) => props.onNodeHover(node?.id ?? null),
    onNodeClick: (node: GraphNode) => props.onNodeClick(node.id)
  })
}
