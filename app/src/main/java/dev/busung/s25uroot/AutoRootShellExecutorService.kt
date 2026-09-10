package dev.busung.s25uroot

/**
 * Shell-required Auto Root executor.
 *
 * This component intentionally stays in the default application process, where
 * ShizukuProvider receives and owns the client Binder. The actual exploit still
 * executes remotely as shell through Shizuku, so keeping this coordinator out of
 * :autoroot_exec does not move the exploit itself into the app process.
 */
class AutoRootShellExecutorService : AutoRootExecutorService()
