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
  width: number
  height: number
  focusedId: string | null
  hoveredId: string | null
  onNodeHover: (id: string | null) => void
  onNodeClick: (id: string) => void
}) {
  return createElement(ForceGraph2DComponent, {
    graphData: { nodes: props.nodes, links: props.links },
    width: props.width,
    height: props.height,
    nodeLabel: 'label',
    nodeColor: 'color',
    nodeCanvasObject: (node: ForceGraphNode, ctx: CanvasRenderingContext2D) => {
      const isFocused = node.id === props.focusedId
      const isHovered = node.id === props.hoveredId
      const radius = isFocused ? 8 : isHovered ? 6 : 4

      ctx.beginPath()
      ctx.arc(node.x ?? 0, node.y ?? 0, radius, 0, 2 * Math.PI)
      ctx.fillStyle = node.color ?? '#888888'
      ctx.fill()
    },
    linkColor: (link: ForceGraphLink) => link.color,
    onNodeHover: (node: ForceGraphNode | null) =>
      props.onNodeHover(typeof node?.id === 'string' ? node.id : null),
    onNodeClick: (node: ForceGraphNode) => {
      if (typeof node.id === 'string') props.onNodeClick(node.id)
    }
  })
}
