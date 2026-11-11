/** The striped bar borrowed from the reference image. The stripes slide while active. */
export function StripedProgress({ percent, label }: { percent: number; label: string }) {
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      className="rounded-lg border border-line-strong bg-[#0b1213] p-[3px]"
    >
      <div className="stripes-dark relative h-3.5 overflow-hidden rounded-[5px]">
        <div
          className="stripes-light absolute inset-y-0 left-0 animate-stripes transition-[width] duration-200 ease-linear"
          style={{ width: `${percent}%` }}
        />
      </div>
    </div>
  )
}
