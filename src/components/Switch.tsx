interface Props {
  checked: boolean
  onChange: (checked: boolean) => void
  label: string
}

export function Switch({ checked, onChange, label }: Props) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      onClick={() => onChange(!checked)}
      className={`relative h-[30px] w-[50px] shrink-0 cursor-pointer rounded-full border transition-colors ${
        checked ? 'border-gold-bright/90 bg-linear-135 from-gold-bright to-[#b8935a]' : 'border-white/15 bg-[#1c2728]'
      }`}
    >
      <span
        aria-hidden="true"
        className={`absolute top-1 size-5 rounded-full transition-[left] duration-150 ${checked ? 'left-6 bg-ink' : 'left-1 bg-text-2'}`}
      />
    </button>
  )
}
