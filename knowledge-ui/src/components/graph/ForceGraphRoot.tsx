import { createElement, type ReactElement } from 'react'
import ForceGraph2D, {
  type ForceGraphProps,
  type LinkObject as ForceGraphLinkObject,
  type NodeObject as ForceGraphNodeObject
} from 'react-force-graph-2d'
import type { GraphLink, GraphNode } from '../../graph/types'

type ForceGraphNode = ForceGraphNodeObject<GraphNode>
type ForceGraphLink = ForceGraphLinkObject<GraphNode, GraphLink>
type ForceGraphComponent = (props: ForceGraphProps<GraphNode, GraphLink>) => ReactElement

const ForceGraph2DComponent = ForceGraph2D as unknown as ForceGraphComponent

export function ForceGraphRoot(props: {
  nodes: GraphNode[]
  links: GraphLink[]
  onNodeHover: (id: string | null) => void
  onNodeClick: (id: string) => void
}) {
  return createElement(ForceGraph2DComponent, {
    graphData: { nodes: props.nodes, links: props.links },
    nodeLabel: 'label',
    nodeAutoColorBy: 'realm',
    linkColor: (link: ForceGraphLink) => link.color,
    onNodeHover: (node: ForceGraphNode | null) =>
      props.onNodeHover(typeof node?.id === 'string' ? node.id : null),
    onNodeClick: (node: ForceGraphNode) => {
      if (typeof node.id === 'string') props.onNodeClick(node.id)
    }
  })
}
