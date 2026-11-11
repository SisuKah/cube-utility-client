import type { Cue } from './emotes'

// The little extras emotes throw out: floating text, a heart blown at you, and a
// knock on the glass. Plain DOM elements over the canvas, animated with the Web
// Animations API and removed when they finish.

const TEXT_CLASS =
  'pointer-events-none absolute left-0 top-0 select-none whitespace-nowrap text-[22px] leading-none font-extrabold tracking-wide text-gold-bright [text-shadow:0_2px_0_rgb(10_17_18/0.85),0_0_16px_rgb(10_17_18/0.7)]'

/** A 7×6 pixel heart, to match the blocky character. */
const HEART_SVG =
  '<svg viewBox="0 0 7 6" width="28" height="24" shape-rendering="crispEdges" aria-hidden="true">' +
  '<path fill="#ef5a7a" d="M1 0h2v1h1V0h2v1h1v2H6v1H5v1H4v1H3V5H2V4H1V3H0V1h1z"/>' +
  '<path fill="#f7a3b5" d="M1 1h1v1H1z"/></svg>'

function place(layer: HTMLElement, x: number, y: number, className: string): HTMLElement {
  const el = document.createElement('div')
  el.setAttribute('aria-hidden', 'true')
  el.className = className
  el.style.left = `${x}px`
  el.style.top = `${y}px`
  layer.appendChild(el)
  return el
}

function removeWhenDone(el: HTMLElement, animation: Animation): void {
  animation.onfinish = () => el.remove()
  animation.oncancel = () => el.remove()
}

/** Shows `cue` at (x, y) in the layer's coordinates. `shake` is moved a little for a knock. */
export function spawnCue(layer: HTMLElement, shake: HTMLElement, cue: Cue, x: number, y: number): void {
  if (cue.kind === 'text') {
    const el = place(layer, x, y, TEXT_CLASS)
    el.textContent = cue.text ?? ''
    const drift = (Math.random() - 0.3) * 24
    removeWhenDone(
      el,
      el.animate(
        [
          { transform: 'translate(-50%, -50%) translateY(8px) scale(0.4)', opacity: 0 },
          { transform: 'translate(-50%, -50%) translateY(-6px) scale(1.15)', opacity: 1, offset: 0.18 },
          { transform: 'translate(-50%, -50%) translateY(-16px) scale(1)', opacity: 1, offset: 0.55 },
          { transform: `translate(-50%, -50%) translate(${drift}px, -42px) scale(0.92)`, opacity: 0 }
        ],
        { duration: 1400, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' }
      )
    )
    return
  }

  if (cue.kind === 'heart') {
    const el = place(layer, x, y, 'pointer-events-none absolute left-0 top-0 drop-shadow-[0_4px_10px_rgb(10_17_18/0.6)]')
    el.innerHTML = HEART_SVG
    // Flies out of the hand and toward you, growing as it comes.
    removeWhenDone(
      el,
      el.animate(
        [
          { transform: 'translate(-50%, -50%) scale(0.5)', opacity: 0 },
          { transform: 'translate(-50%, -50%) translateY(-10px) scale(1)', opacity: 1, offset: 0.15 },
          { transform: 'translate(-50%, -50%) translate(-14px, -34px) scale(1.8)', opacity: 1, offset: 0.6 },
          { transform: 'translate(-50%, -50%) translate(-24px, -46px) scale(4.2)', opacity: 0 }
        ],
        { duration: 1300, easing: 'cubic-bezier(0.4, 0, 0.7, 1)' }
      )
    )
    return
  }

  // A knock: a ring where the fist meets the glass, and the screen jolts a little.
  const ring = place(layer, x, y, 'pointer-events-none absolute left-0 top-0 size-12 rounded-full border-2 border-white/70')
  removeWhenDone(
    ring,
    ring.animate(
      [
        { transform: 'translate(-50%, -50%) scale(0.25)', opacity: 0.9 },
        { transform: 'translate(-50%, -50%) scale(1.5)', opacity: 0 }
      ],
      { duration: 420, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' }
    )
  )
  shake.animate([{ translate: '0 0' }, { translate: '-3px 1px' }, { translate: '2px -1px' }, { translate: '-1px 0' }, { translate: '0 0' }], {
    duration: 160,
    easing: 'linear'
  })
}
