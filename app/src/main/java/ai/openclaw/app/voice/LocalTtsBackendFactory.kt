package ai.openclaw.app.voice

import android.content.Context

internal object LocalTtsBackendFactory {
  fun create(context: Context): LocalTtsBackend = LocalMnnTtsEngine(context.applicationContext)
}
