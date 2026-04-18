export const HEX_ANGLE_OFFSET = Math.PI / 6  // flat-top orientation rotates 30°

export interface HexWall {
  index: number       // 0..5
  angle: number       // centre angle in radians
  centerX: number
  centerZ: number
  rotationY: number   // Y rotation so plane normal faces origin
  edgeLength: number
}

export interface HexWallSet {
  apothem: number
  edgeLength: number
  walls: HexWall[]
}

/**
 * For a flat-top hex prism of circumradius `radius`, return the 6 inner-wall
 * positions and rotations so a PlaneGeometry placed at wall.{centerX, centerZ}
 * with rotationY = wall.rotationY faces the origin with its +Z normal.
 */
export function hexWallPositions(radius: number): HexWallSet {
  const apothem = radius * Math.cos(Math.PI / 6)
  const edgeLength = radius
  const walls: HexWall[] = []
  for (let i = 0; i < 6; i++) {
    const angle = i * (Math.PI / 3)
    walls.push({
      index: i,
      angle,
      centerX: Math.cos(angle) * apothem,
      centerZ: Math.sin(angle) * apothem,
      rotationY: -angle - Math.PI / 2,
      edgeLength,
    })
  }
  return { apothem, edgeLength, walls }
}
