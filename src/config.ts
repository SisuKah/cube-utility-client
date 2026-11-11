/**
 * Brand text for the UI, kept in one place. Anything security-related (manifest URL,
 * signing key, Azure client ID) lives on the Rust side, in src-tauri/src/config.rs.
 */
export const config = {
  brand: {
    name: 'Cube Utility Client',
    wordmarkTop: 'Cube Utility',
    wordmarkBottom: 'Client'
  },
  quickPlayServer: 'play.cubecraft.net',
  disclaimer:
    'Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft. Not affiliated with CubeCraft Games.'
} as const
