# Root vs post-root trust boundary

The application treats `KernelSU control channel verified` as the boundary between the critical root operation and convenience automation.

Before that boundary:

- Auto Root is offline.
- Auto Root uses standalone execution.
- Wireless ADB is not required.
- Shizuku is not required.
- No post-root notification/pairing work is allowed to determine exploit success.

After that boundary:

- the success History entry is checkpointed;
- Wireless Debugging may be enabled;
- local ADB may connect with the persisted key;
- Shizuku may be started;
- KernelSU lifecycle stages and zygote restart may run.

Exceptions in the second group are logged as post-root failures and must not rewrite the verified root outcome as failed.
