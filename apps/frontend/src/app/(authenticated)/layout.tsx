import { AuthProvider } from '@/contexts/AuthContext'
import { AppShell } from '@/components/briefy/AppShell'

export default function AuthenticatedLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <AuthProvider redirectOnUnauthenticated="/">
      <AppShell>{children}</AppShell>
    </AuthProvider>
  )
}
