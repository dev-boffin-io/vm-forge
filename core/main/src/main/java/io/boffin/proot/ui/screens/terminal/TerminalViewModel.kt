package io.boffin.proot.ui.screens.terminal

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import com.rk.settings.Settings
import io.boffin.proot.service.SessionService
import io.boffin.proot.ui.activities.terminal.MainActivity
import io.boffin.proot.ui.screens.settings.WorkingMode
import io.boffin.proot.ui.screens.terminal.virtualkeys.VirtualKeysListener
import io.boffin.proot.ui.screens.terminal.virtualkeys.VirtualKeysView
import com.termux.view.TerminalView
import java.lang.ref.WeakReference

class TerminalViewModel : ViewModel() {
    private var terminalViewRef = WeakReference<TerminalView>(null)
    private var virtualKeysViewRef = WeakReference<VirtualKeysView>(null)

    val terminalView: TerminalView? get() = terminalViewRef.get()
    val virtualKeysView: VirtualKeysView? get() = virtualKeysViewRef.get()

    fun setTerminalView(view: TerminalView?) { terminalViewRef = WeakReference(view) }
    fun setVirtualKeysView(view: VirtualKeysView?) { virtualKeysViewRef = WeakReference(view) }

    var bitmap by mutableStateOf<ImageBitmap?>(null)
    var wallAlpha by mutableFloatStateOf(Settings.wallTransparency)
    var backgroundBlur by mutableFloatStateOf(Settings.background_blur)

    var showToolbar by mutableStateOf(Settings.toolbar)
    var showVirtualKeys by mutableStateOf(Settings.virtualKeys)
    var showHorizontalToolbar by mutableStateOf(Settings.toolbar)

    fun setFont(typeface: Typeface) {
        TerminalUtils.typeface = typeface
        terminalView?.apply {
            setTypeface(typeface)
            onScreenUpdated()
        }
    }

    fun changeSession(context: Context, sessionBinder: SessionService.SessionBinder, sessionId: String) {
        val terminal = terminalView ?: return
        val activity = context as? MainActivity ?: return
        val client = TerminalBackEnd(terminal, activity)
        
        val session = sessionBinder.getSession(sessionId)
            ?: run {
                val service = sessionBinder.getService()
                val custom = if (sessionId == service.currentSession.value.first) service.currentCustomSession else null
                if (custom != null) {
                    val pendingCommand = MkSession.buildCustomPendingCommand(context, custom)
                    sessionBinder.createSession(sessionId, client, WorkingMode.ALPINE, pendingCommand)
                } else {
                    sessionBinder.createSession(sessionId, client, Settings.working_Mode)
                }
            }
            
        session.updateTerminalSessionClient(client)
        terminal.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        terminal.attachSession(session)
        terminal.setTerminalViewClient(client)
        
        terminal.post {
            val fgColor = TerminalUtils.getViewColor()
            val bgColor = TerminalUtils.getBackgroundColor()
            terminal.keepScreenOn = true
            terminal.requestFocus()
            terminal.isFocusableInTouchMode = true

            terminal.mEmulator?.mColors?.mCurrentColors?.apply {
                set(256, fgColor)
                set(257, bgColor)
                set(258, fgColor)
            }
        }
        
        virtualKeysView?.apply {
            virtualKeysViewClient = terminal.mTermSession?.let { VirtualKeysListener(it) }
        }
        
        sessionBinder.getService().currentSession.value = Pair(sessionId, sessionBinder.getService().sessionList[sessionId] ?: Settings.working_Mode)
    }
}
