// AIDL interface implemented by ShellUserService, which Shizuku runs as
// shell UID (ADB-level privilege) rather than this app's own sandboxed
// UID — needed because this device denies exec() of files under the
// app's own private data directory. See RootfsImporter/PRootLauncher
// class docs for the full story.
package io.boffin.vmforge;

import android.os.ParcelFileDescriptor;

interface IShellService {
    // Special transaction code Shizuku's ShizukuBinderWrapper recognizes to
    // tear down the remote user service process. Must be exactly this
    // value — it's part of Shizuku's own convention, not something we chose.
    void destroy() = 16777114;

    // Runs `sh -c script` as shell UID, waits for it to finish, and
    // returns "<exitCode>\n<combined stdout+stderr>". For one-shot
    // commands (rootfs extraction) where we don't need live streaming.
    String runShell(in String script);

    // Starts cmd/env as a long-running child process of this (shell UID)
    // service and returns [stdinWriteEnd, stdoutReadEnd] as real pipe
    // file descriptors — wrap them with ParcelFileDescriptor.
    // AutoCloseOutputStream / AutoCloseInputStream on the caller side for
    // genuine streaming I/O, no polling. Only one such process is tracked
    // at a time; starting a new one replaces the previous reference.
    ParcelFileDescriptor[] startProcess(in String[] cmd, in String[] env);

    boolean isProcessAlive();
    int waitForProcess();
    void destroyProcess();
}
