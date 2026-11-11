import { useState } from 'react'
import { Check, Download, ExternalLink, LoaderCircle, Search, X } from 'lucide-react'
import type { ModrinthHit } from '../native'
import type { InstallJob, ModrinthState } from '../state/useModrinth'

const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 })

/** Two letters for a mod without an icon: "Sample HUD" → "SH". */
export function monogram(name: string): string {
  const words = name
    .replace(/\(.*?\)/g, ' ')
    .split(/[^\p{L}\p{N}]+/u)
    .filter(Boolean)
  if (words.length >= 2) return `${words[0]?.[0] ?? ''}${words[1]?.[0] ?? ''}`.toUpperCase()
  return (words[0] ?? '?').slice(0, 2).toUpperCase()
}

/** A mod's icon from Modrinth, or its initials when it has none (or it doesn't load). */
export function ModIcon({ url, name, className }: { url: string | null; name: string; className: string }) {
  const [broken, setBroken] = useState(false)
  if (url && !broken) {
    return <img src={url} alt="" loading="lazy" referrerPolicy="no-referrer" onError={() => setBroken(true)} className={`shrink-0 bg-surface-2 object-cover ${className}`} />
  }
  return (
    <span aria-hidden="true" className={`flex shrink-0 items-center justify-center bg-teal-bright/12 text-sm font-extrabold text-teal-bright ${className}`}>
      {monogram(name)}
    </span>
  )
}

interface Props {
  browser: ModrinthState
  /** Mods already installed from Modrinth, so results show it even after removing in the other tab. */
  installedIds: Set<string>
}

/** Browse Modrinth: mods that work with this pack, installed straight into the mods folder. */
export function ModrinthBrowser({ browser, installedIds }: Props) {
  const { query, setQuery, hits, hasMore, minecraft, loading, loadingMore, error, retry, loadMore, jobs, install, openPage } = browser

  return (
    <section aria-label="Browse Modrinth" className="flex flex-col gap-4">
      <div>
        <div className="relative">
          <Search aria-hidden="true" className="pointer-events-none absolute left-4 top-1/2 size-[18px] -translate-y-1/2 text-text-3" strokeWidth={1.8} />
          <input
            type="search"
            aria-label="Search Modrinth"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={`Search Modrinth for ${minecraft} Fabric mods`}
            spellCheck={false}
            autoComplete="off"
            className="h-12 w-full rounded-[14px] border border-line-strong bg-surface-1 pl-11 pr-12 text-[15px] text-text outline-none transition-colors placeholder:text-text-3 hover:border-white/18 focus:border-gold/60 [&::-webkit-search-cancel-button]:appearance-none"
          />
          {query && (
            <button
              type="button"
              onClick={() => setQuery('')}
              aria-label="Clear the search"
              className="absolute right-2.5 top-1/2 flex size-8 -translate-y-1/2 cursor-pointer items-center justify-center rounded-lg text-text-3 transition-colors hover:bg-white/6 hover:text-text focus-visible:outline-2 focus-visible:outline-gold-bright"
            >
              <X aria-hidden="true" className="size-4" strokeWidth={2} />
            </button>
          )}
        </div>
        <p className="mt-2 text-[13px] leading-[19px] text-text-3">
          Straight from Modrinth, and only mods that work with this pack. They install into your mods folder; on CubeCraft, use only mods its rules allow.
        </p>
      </div>

      {error && (
        <div role="alert" className="flex flex-wrap items-center gap-3 rounded-[14px] border border-crimson/30 bg-crimson/8 px-4 py-3 text-[13px] text-[#f2a3b4]">
          <span className="min-w-0 flex-1">{error}</span>
          <button
            type="button"
            onClick={retry}
            className="h-8 cursor-pointer rounded-[9px] border border-white/12 px-3 text-[13px] font-semibold text-text hover:border-white/25"
          >
            Try again
          </button>
        </div>
      )}

      {loading && !error && (
        <ul aria-hidden="true" className="flex flex-col gap-2">
          {[0, 1, 2, 3].map((i) => (
            <li key={i} className="h-[92px] animate-pulse rounded-[14px] border border-line bg-surface-1" />
          ))}
        </ul>
      )}

      {!loading && !error && hits.length === 0 && (
        <p className="rounded-[14px] border border-line bg-surface-1 p-5 text-sm text-text-2">
          {query.trim() ? `No ${minecraft} Fabric mods match “${query.trim()}”.` : 'No mods to show right now.'}
        </p>
      )}

      {!loading && hits.length > 0 && (
        <>
          <ul className="flex flex-col gap-2" aria-busy={loadingMore}>
            {hits.map((hit) => (
              <ModRow key={hit.projectId} hit={hit} job={jobs[hit.projectId]} installed={installedIds.has(hit.projectId)} onInstall={() => install(hit)} onOpen={() => openPage(hit.projectId)} />
            ))}
          </ul>
          {hasMore && (
            <button
              type="button"
              onClick={loadMore}
              disabled={loadingMore}
              className="mx-auto inline-flex h-10 cursor-pointer items-center gap-2 rounded-[10px] border border-white/10 bg-surface-2 px-5 text-sm font-semibold text-text-2 enabled:hover:border-white/20 enabled:hover:text-text disabled:cursor-wait"
            >
              {loadingMore && <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />}
              {loadingMore ? 'Loading…' : 'Show more'}
            </button>
          )}
        </>
      )}
    </section>
  )
}

interface RowProps {
  hit: ModrinthHit
  job: InstallJob | undefined
  installed: boolean
  onInstall: () => void
  onOpen: () => void
}

function ModRow({ hit, job, installed, onInstall, onOpen }: RowProps) {
  const done = installed || job?.state === 'done'
  return (
    <li className="flex items-center gap-4 rounded-[14px] border border-line bg-surface-1 px-4 py-3.5 transition-colors hover:border-white/12">
      <ModIcon url={hit.iconUrl} name={hit.title} className="size-12 rounded-xl" />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
          <h3 className="text-[15px] font-semibold">{hit.title}</h3>
          {hit.author && <span className="text-xs text-text-3">by {hit.author}</span>}
        </div>
        {hit.description && <p className="mt-0.5 line-clamp-2 text-[13px] leading-[19px] text-text-2">{hit.description}</p>}
        <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-text-3">
          <span className="inline-flex items-center gap-1.5">
            <Download aria-hidden="true" className="size-3.5" strokeWidth={1.8} />
            {compact.format(hit.downloads)} downloads
          </span>
          <button type="button" onClick={onOpen} className="inline-flex cursor-pointer items-center gap-1 rounded hover:text-text focus-visible:outline-1 focus-visible:outline-gold-bright">
            Modrinth page
            <ExternalLink aria-hidden="true" className="size-3" strokeWidth={1.8} />
            <span className="sr-only">for {hit.title} (opens your browser)</span>
          </button>
        </div>
        {job && job.state !== 'installing' && (
          <p role={job.state === 'failed' ? 'alert' : 'status'} className={`mt-1.5 text-[13px] leading-[19px] ${job.state === 'failed' ? 'text-[#f2a3b4]' : 'text-green'}`}>
            {job.message}
          </p>
        )}
      </div>
      <div className="shrink-0">
        {hit.status === 'inPack' ? (
          <span className="inline-flex h-9 items-center rounded-[10px] px-1 text-[13px] font-semibold text-text-3">In the pack</span>
        ) : done ? (
          <span className="inline-flex h-9 items-center gap-1.5 rounded-[10px] px-1 text-[13px] font-semibold text-green">
            <Check aria-hidden="true" className="size-4" strokeWidth={2.2} />
            Installed
          </span>
        ) : (
          <button
            type="button"
            onClick={onInstall}
            disabled={job?.state === 'installing'}
            aria-label={`Install ${hit.title}`}
            className="inline-flex h-9 w-[118px] cursor-pointer items-center justify-center gap-2 rounded-[10px] border border-gold/40 bg-gold/10 text-sm font-semibold text-gold-bright transition-colors enabled:hover:bg-gold/18 disabled:cursor-wait focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-gold-bright"
          >
            {job?.state === 'installing' ? (
              <>
                <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />
                Installing
              </>
            ) : (
              <>
                <Download aria-hidden="true" className="size-4" strokeWidth={1.8} />
                Install
              </>
            )}
          </button>
        )}
      </div>
    </li>
  )
}
