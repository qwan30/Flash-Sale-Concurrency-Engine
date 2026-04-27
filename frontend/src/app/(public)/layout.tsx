import Link from "next/link";

const publicLinks = [
  { href: "/", label: "Lab Overview" },
  { href: "/events", label: "Fixtures" },
  { href: "/booking", label: "Order Probe" },
  { href: "/my-orders", label: "Order Traces" },
  { href: "/admin/control-desk", label: "Control Desk" },
];

export default function PublicLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <div className="min-h-screen bg-white text-[#242424]">
      <header className="border-b border-black/[0.06] bg-white">
        <div className="mx-auto flex w-full max-w-[1200px] flex-col gap-4 px-4 py-4 sm:px-6 lg:flex-row lg:items-center lg:justify-between lg:px-8">
          <Link
            href="/"
            className="font-display text-xl font-semibold leading-none text-[#242424]"
          >
            Flash-Sale Lab
          </Link>
          <nav aria-label="Public navigation" className="flex flex-wrap items-center gap-2">
            {publicLinks.map((link) => (
              <Link
                key={link.href}
                href={link.href}
                className="rounded-full px-3 py-2 text-sm font-medium text-[#111111] transition hover:bg-[#f7f7f7] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500/50"
              >
                {link.label}
              </Link>
            ))}
          </nav>
        </div>
      </header>
      {children}
    </div>
  );
}
