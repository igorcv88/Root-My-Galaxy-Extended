# Dependency graph

`AutoRootBootReceiver` → `AutoRootService` → fresh `AutoRootExecutorService` → standalone `AutoRootRunner` → exploit → KernelSU verification.

Only after KernelSU verification:

`PostRootAutomation` → Wireless Debugging setting → saved ADB key + mDNS → local adbd → KernelSU shell root → Shizuku `start.sh` → optional `ksud post-fs-data/services/boot-completed` → zygote restart.

No edge from Wireless ADB or Shizuku points back into exploit acquisition.
