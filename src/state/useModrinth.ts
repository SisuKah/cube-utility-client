import { useCallback, useEffect, useRef, useState } from 'react'
import { inApp, modrinth, type ModrinthHit, type ModrinthPage } from '../native'
import { sampleModrinthPage } from '../data/pack'

/** What an install is doing, per mod. */
export type InstallJob = { state: 'installing' } | { state: 'done'; message: string } | { state: 'failed'; message: string }

export interface ModrinthState {
  query: string
  setQuery: (query: string) => void
  hits: ModrinthHit[]
  /** Modrinth has more results than shown. */
  hasMore: boolean
  /** The Minecraft version the results are for. */
  minecraft: string
  loading: boolean
  loadingMore: boolean
  error: string
  retry: () => void
  loadMore: () => void
  jobs: Record<string, InstallJob>
  install: (hit: ModrinthHit) => void
  /** Clears what an install said, once the mod is removed again. */
  forget: (projectId: string) => void
  openPage: (projectId: string) => void
}

const api = inApp ? modrinth : demoApi()

/**
 * Browse Modrinth: popular mods first, search as the player types, install with one click.
 * Nothing is asked of Modrinth until the panel is first opened (`active`).
 */
export function useModrinth(active: boolean): ModrinthState {
  const [started, setStarted] = useState(active)
  useEffect(() => {
    if (active) setStarted(true)
  }, [active])
  const [query, setQuery] = useState('')
  const [page, setPage] = useState<ModrinthPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState('')
  const [jobs, setJobs] = useState<Record<string, InstallJob>>({})
  const [attempt, setAttempt] = useState(0)
  // Only the newest search may fill the list; answers to older ones arrive late and are dropped.
  const latest = useRef(0)

  useEffect(() => {
    if (!started) return
    const request = ++latest.current
    setLoading(true)
    setError('')
    // Wait until the player stops typing for a moment.
    const timer = window.setTimeout(
      () => {
        api
          .search(query, 0)
          .then((next) => {
            if (request === latest.current) setPage(next)
          })
          .catch((reason: unknown) => {
            if (request === latest.current) setError(String(reason))
          })
          .finally(() => {
            if (request === latest.current) setLoading(false)
          })
      },
      query ? 300 : 0
    )
    return () => window.clearTimeout(timer)
  }, [query, attempt, started])

  const loadMore = useCallback(() => {
    if (!page || loadingMore) return
    const request = latest.current
    setLoadingMore(true)
    api
      .search(query, page.nextOffset)
      .then((next) => {
        if (request !== latest.current) return
        // Popular lists shift between requests: skip mods already shown.
        setPage((current) => {
          if (!current) return next
          const shown = new Set(current.hits.map((h) => h.projectId))
          return { ...next, hits: [...current.hits, ...next.hits.filter((h) => !shown.has(h.projectId))] }
        })
      })
      .catch((reason: unknown) => {
        if (request === latest.current) setError(String(reason))
      })
      .finally(() => setLoadingMore(false))
  }, [page, query, loadingMore])

  const install = useCallback((hit: ModrinthHit) => {
    setJobs((current) => ({ ...current, [hit.projectId]: { state: 'installing' } }))
    api
      .install(hit.projectId)
      .then((outcome) => {
        const libraries = outcome.installed.slice(1)
        const message = [
          libraries.length > 0 ? `Installed, with ${listNames(libraries)}.` : 'Installed.',
          outcome.nextLaunch ? 'It loads the next time you start Minecraft.' : 'It’s in your next game.'
        ].join(' ')
        setJobs((current) => ({ ...current, [hit.projectId]: { state: 'done', message } }))
      })
      .catch((reason: unknown) => setJobs((current) => ({ ...current, [hit.projectId]: { state: 'failed', message: String(reason) } })))
  }, [])

  return {
    query,
    setQuery,
    hits: page?.hits ?? [],
    hasMore: page ? page.nextOffset < page.total : false,
    minecraft: page?.minecraft ?? '26.3',
    loading,
    loadingMore,
    error,
    retry: () => setAttempt((n) => n + 1),
    loadMore,
    jobs,
    install,
    forget: (projectId) =>
      setJobs((current) => {
        const { [projectId]: _gone, ...rest } = current
        return rest
      }),
    openPage: (projectId) => void api.openPage(projectId).catch(() => {})
  }
}

export function listNames(names: string[]): string {
  if (names.length <= 1) return names.join('')
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

/** The browser preview: sample mods and a pretend install, for design work. */
function demoApi(): typeof modrinth {
  const wait = (ms: number) => new Promise((resolve) => window.setTimeout(resolve, ms))
  return {
    search: async (query, offset) => {
      await wait(250)
      const words = query.trim().toLowerCase()
      const hits = sampleModrinthPage.hits.filter((h) => !words || `${h.title} ${h.description}`.toLowerCase().includes(words))
      return { ...sampleModrinthPage, hits: hits.slice(offset, offset + 20), total: hits.length, offset, nextOffset: Math.min(hits.length, offset + 20) }
    },
    install: async (projectId) => {
      await wait(900)
      return { installed: projectId === 'demo-shaders' ? ['Sample Shaders', 'Sample Library'] : ['Sample'], nextLaunch: false }
    },
    remove: async () => wait(300).then(() => undefined),
    openPage: async () => undefined
  }
}
