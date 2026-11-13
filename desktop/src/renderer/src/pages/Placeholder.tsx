interface PlaceholderProps {
  title: string
  empty: string
}

export function Placeholder({ title, empty }: PlaceholderProps) {
  return (
    <section>
      <h1 className="text-2xl font-semibold">{title}</h1>
      <p className="mt-2 text-muted-foreground">{empty}</p>
    </section>
  )
}
