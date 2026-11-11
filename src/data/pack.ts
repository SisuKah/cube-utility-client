// Milestone 1: static data that mirrors pack/pack.yml, so the screens can be built.
// Milestone 3 replaces all of this with the signed manifest.

export const pack = {
  version: '2026.11.10-1',
  minecraft: '26.3',
  fabricLoader: '0.20.1',
  java: 25,
  whatsNew: 'Cube Utility 1.7.0',
  whatsNewMore: 3
} as const

export interface OwnMod {
  id: string
  mono: string
  name: string
  version: string
  description: string
  updatedFrom?: string
}

export const ownMods: OwnMod[] = [
  {
    id: 'cubeutility',
    mono: 'CU',
    name: 'Cube Utility',
    version: '1.7.0',
    description: 'All-in-one CubeCraft toolkit: game timer, map and team layout, build limit, bridge lengths and more.',
    updatedFrom: '1.5.3'
  },
  {
    id: 'ewoptimizer',
    mono: 'EO',
    name: 'Eggwars Optimizer',
    version: '1.1.0',
    description: 'Client-side EggWars tweaks, like generator particles that follow your Particles setting.'
  },
  {
    id: 'armorhud',
    mono: 'AH',
    name: 'Armor Dura HUD',
    version: '1.0.0',
    description: 'Shows your armor and how much durability it has left. Press H to move it anywhere.'
  },
  {
    id: 'cubecraft-autovote',
    mono: 'AV',
    name: 'CubeCraft AutoVote',
    version: '1.2.0',
    description: 'Automatically votes for your preferred game options in pre-game lobbies.'
  },
  {
    id: 'cubecraft-level-rank',
    mono: 'LR',
    name: 'CubeCraft Level + Rank',
    version: 'V5',
    description: 'Shows each player’s CubeCraft level next to their nametag in EggWars.'
  }
]

export interface ExtraMod {
  id: string
  mono: string
  name: string
  version: string
  description: string
  defaultOn: boolean
  /** Libraries this mod needs. They are added automatically while it is on. */
  needs?: string[]
}

export const extraMods: ExtraMod[] = [
  {
    id: 'zoomify',
    mono: 'Z',
    name: 'Zoomify',
    version: '2.17.0',
    description: 'Zoom in with a key.',
    defaultOn: true,
    needs: ['YetAnotherConfigLib', 'Fabric Language Kotlin']
  },
  {
    id: 'morechathistory',
    mono: 'CH',
    name: 'More Chat History',
    version: '2.0.0',
    description: 'Keeps more of your chat history.',
    defaultOn: true
  },
  {
    id: 'cubes-without-borders',
    mono: 'CB',
    name: 'Cubes Without Borders',
    version: '5.0.0',
    description: 'Borderless fullscreen window.',
    defaultOn: true
  },
  {
    id: 'freelook',
    mono: 'FL',
    name: 'Freelook',
    version: '1.4.4',
    description: 'Look around without changing direction. Check CubeCraft’s rules first.',
    defaultOn: false
  },
  {
    id: 'variants-cit',
    mono: 'VC',
    name: 'Variants-CIT',
    version: '5.8.1',
    description: 'Custom item textures from resource packs.',
    defaultOn: false
  }
]

/** Performance mods and libraries that are always installed. */
export const alwaysOn: string[] = [
  'Sodium',
  'Lithium',
  'ImmediatelyFast',
  'Entity Culling',
  'FerriteCore',
  'Dynamic FPS',
  'Krypton',
  'Sodium Extra',
  'Reese’s Sodium Options',
  'Fabric API',
  'Mod Menu',
  'Cloth Config',
  'Text Placeholder API'
]

export type ExtrasState = Record<string, boolean>

export function defaultExtras(): ExtrasState {
  return Object.fromEntries(extraMods.map((m) => [m.id, m.defaultOn]))
}

/** Libraries pulled in by the extras that are switched on, labeled with the mod that needs them. */
export function extraLibraries(extras: ExtrasState): string[] {
  const libraries = new Map<string, string>()
  for (const mod of extraMods) {
    if (!extras[mod.id]) continue
    for (const lib of mod.needs ?? []) if (!libraries.has(lib)) libraries.set(lib, mod.name)
  }
  return [...libraries].map(([lib, mod]) => `${lib} (for ${mod})`)
}

export function modCount(extras: ExtrasState): number {
  const enabled = extraMods.filter((m) => extras[m.id])
  const libraries = new Set(enabled.flatMap((m) => m.needs ?? []))
  return ownMods.length + alwaysOn.length + enabled.length + libraries.size
}
