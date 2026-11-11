export function Logo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 40 40" fill="none" aria-hidden="true" className={className}>
      <path d="M18 6.5 30.5 13.7v14.6L18 35.5 5.5 28.3V13.7z" stroke="#CDA76A" strokeWidth="1.6" strokeLinejoin="round" />
      <path d="M5.5 13.7 18 21l12.5-7.3M18 21v14.5" stroke="#CDA76A" strokeWidth="1.6" strokeLinejoin="round" />
      <path d="M33.5 2.5l1.2 3.1 3.1 1.2-3.1 1.2-1.2 3.1-1.2-3.1-3.1-1.2 3.1-1.2z" fill="#E4C084" />
    </svg>
  )
}
